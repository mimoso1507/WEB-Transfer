package com.transfer.navegador;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Servidor HTTP minimo: pagina, lista, subida, descarga y borrado. */
class Server {
    interface Listener { void onReceived(String name); }

    private final int port;
    private final File folder;
    private final byte[] page;
    private final Listener listener;
    private ServerSocket ss;
    private volatile boolean running;

    Server(int port, File folder, String page, Listener listener) {
        this.port = port;
        this.folder = folder;
        this.page = page.getBytes(StandardCharsets.UTF_8);
        this.listener = listener;
    }

    void start() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s)).start();
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }, "http");
        t.start();
    }

    void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(60000);
            sock.setTcpNoDelay(true);
            sock.setKeepAlive(true);
            try { sock.setSendBufferSize(512 * 1024); } catch (Exception ignored) {}
            InputStream in = new BufferedInputStream(sock.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 65536);
            while (true) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) return;
                String[] rq = line.split(" ");
                if (rq.length < 2) return;
                String method = rq[0], target = rq[1];
                Map<String, String> h = new HashMap<>();
                String hl;
                while ((hl = readLine(in)) != null && !hl.isEmpty()) {
                    int c = hl.indexOf(':');
                    if (c > 0) h.put(hl.substring(0, c).trim().toLowerCase(), hl.substring(c + 1).trim());
                }
                boolean keep = !"close".equalsIgnoreCase(h.get("connection"));
                route(method, target, h, in, out);
                out.flush();
                if (!keep) return;
            }
        } catch (Exception ignored) {
        }
    }

    private void route(String method, String target, Map<String, String> h,
                       InputStream in, OutputStream out) throws IOException {
        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        boolean head = method.equals("HEAD");

        if ((method.equals("GET") || head) && (path.equals("/") || path.equals("/index.html"))) {
            send(out, 200, "text/html; charset=utf-8", page, head);
        } else if (method.equals("GET") && path.equals("/api/files")) {
            send(out, 200, "application/json", listJson().getBytes(StandardCharsets.UTF_8), false);
        } else if ((method.equals("GET") || head) && path.startsWith("/f/")) {
            File f = new File(folder, safe(dec(path.substring(3))));
            if (!f.isFile()) { send(out, 404, "text/plain", "No encontrado".getBytes(), head); return; }
            boolean view = ("&" + query + "&").contains("&view&") || query.startsWith("view");
            String ct = view ? URLConnection.guessContentTypeFromName(f.getName()) : null;
            if (ct == null) ct = "application/octet-stream";
            String enc = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
            long size = f.length(), from = 0, to = size - 1;
            boolean partial = false;
            String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(f.lastModified()) + "\"";
            String range = h.get("range");
            String ifRange = h.get("if-range");
            if (range != null && range.startsWith("bytes=") && (ifRange == null || ifRange.equals(etag))) {
                // Se permite reanudar y pedir partes: asi los navegadores pueden continuar una descarga detenida.
                String r = range.substring(6).trim();
                int comma = r.indexOf(',');
                if (comma >= 0) r = r.substring(0, comma).trim();
                int dash = r.indexOf('-');
                try {
                    String a1 = r.substring(0, dash).trim(), b1 = r.substring(dash + 1).trim();
                    if (a1.isEmpty()) {            // "-N": los ultimos N bytes
                        long n = Long.parseLong(b1);
                        from = Math.max(0, size - n);
                    } else {
                        from = Long.parseLong(a1);
                        if (!b1.isEmpty()) to = Math.min(size - 1, Long.parseLong(b1));
                    }
                    partial = true;
                } catch (Exception e) {
                    partial = false;
                    from = 0; to = size - 1;
                }
                if (partial && (from > to || from >= size)) {
                    String bad = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size
                            + "\r\nContent-Length: 0\r\n\r\n";
                    out.write(bad.getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            long length = to - from + 1;
            String hdr = (partial ? "HTTP/1.1 206 Partial Content" : "HTTP/1.1 200 OK")
                    + "\r\nContent-Type: " + ct + "\r\nContent-Length: " + length
                    + (partial ? "\r\nContent-Range: bytes " + from + "-" + to + "/" + size : "")
                    + "\r\nAccept-Ranges: bytes\r\nETag: " + etag
                    + "\r\nContent-Disposition: " + (view ? "inline" : "attachment") + "; filename*=UTF-8''" + enc
                    + "\r\nCache-Control: no-store\r\n\r\n";
            out.write(hdr.getBytes(StandardCharsets.UTF_8));
            if (!head) {
                try (InputStream fi = new FileInputStream(f)) {
                    long skipped = 0;
                    while (skipped < from) {
                        long k = fi.skip(from - skipped);
                        if (k <= 0) break;
                        skipped += k;
                    }
                    copy(fi, out, length);
                }
            }
        } else if (method.equals("POST") && path.equals("/api/upload")) {
            String name = "archivo";
            for (String kv : query.split("&"))
                if (kv.startsWith("name=")) name = dec(kv.substring(5));
            long len;
            try { len = Long.parseLong(h.get("content-length")); }
            catch (Exception e) { send(out, 411, "text/plain", "Falta Content-Length".getBytes(), false); return; }
            File fin = unique(safe(name));
            File tmp = new File(fin.getPath() + ".part");
            try (OutputStream fo = new FileOutputStream(tmp)) {
                copy(in, fo, len);
            } catch (IOException e) {
                tmp.delete();
                throw e;
            }
            if (!tmp.renameTo(fin)) { tmp.delete(); send(out, 500, "text/plain", "Error".getBytes(), false); return; }
            if (listener != null) listener.onReceived(fin.getName());
            String js = "{\"name\":" + jstr(fin.getName()) + "}";
            send(out, 200, "application/json", js.getBytes(StandardCharsets.UTF_8), false);
        } else if (method.equals("DELETE") && path.startsWith("/f/")) {
            File f = new File(folder, safe(dec(path.substring(3))));
            boolean ok = f.isFile() && f.delete();
            send(out, ok ? 200 : 404, "text/plain", (ok ? "ok" : "No encontrado").getBytes(), false);
        } else {
            if (h.containsKey("content-length")) {
                try { in.skip(Long.parseLong(h.get("content-length"))); } catch (Exception ignored) {}
            }
            send(out, 404, "text/plain", "No encontrado".getBytes(), head);
        }
    }

    private String listJson() {
        File[] fs = folder.listFiles();
        List<File> list = new ArrayList<>();
        if (fs != null) for (File f : fs) if (f.isFile() && !f.getName().endsWith(".part")) list.add(f);
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            File f = list.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(jstr(f.getName()))
              .append(",\"size\":").append(f.length())
              .append(",\"mtime\":").append(f.lastModified() / 1000.0).append('}');
        }
        return sb.append(']').toString();
    }

    private File unique(String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        File f = new File(folder, name);
        for (int i = 1; f.exists(); i++) f = new File(folder, base + " (" + i + ")" + ext);
        return f;
    }

    static String safe(String name) {
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) if ("<>:\"|?*\0".indexOf(c) < 0) sb.append(c);
        String r = sb.toString().replaceAll("^[. ]+|[. ]+$", "");
        return r.isEmpty() ? "archivo" : r;
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String jstr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static void send(OutputStream out, int code, String ct, byte[] body, boolean head) throws IOException {
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : "Error";
        String hdr = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + ct
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        if (!head) out.write(body);
    }

    private static void copy(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new EOFException("subida interrumpida");
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("linea muy larga");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }
}
