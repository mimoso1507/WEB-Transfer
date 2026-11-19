package com.transfer.navegador;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Busca paginas web (apps locales como Transfer) en la red Wi-Fi. */
final class Scanner {
    /** Puertos que se revisan en cada equipo de la red. 4747 es el de Transfer. */
    static final int[] PORTS = {4747, 8080, 8000, 8888, 5000, 3000, 80};

    static final class Found {
        final String url, title;
        final boolean transfer;
        Found(String url, String title, boolean transfer) {
            this.url = url; this.title = title; this.transfer = transfer;
        }
    }

    interface Listener { void onFound(Found f); }

    private Scanner() {}

    /** Direcciones IPv4 propias en redes locales (Wi-Fi, cable o punto de acceso). */
    static List<String> localIps() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return ips;
            for (NetworkInterface nif : Collections.list(ifs)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }

    /** Revisa toda la red (/24) y avisa por cada pagina encontrada. Bloquea hasta terminar. */
    static void scan(Listener listener) {
        Set<String> targets = new LinkedHashSet<>();
        for (String ip : localIps()) {
            String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
            for (int i = 1; i < 255; i++) targets.add(prefix + i);
        }
        if (targets.isEmpty()) targets.add("127.0.0.1");
        String transferIp = MdnsResolver.resolve("transfer.local");
        if (transferIp != null) targets.add(transferIp);

        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (final String host : targets) {
            for (final int port : PORTS) {
                pool.execute(() -> {
                    if (!open(host, port)) return;
                    Found f = probe(host, port);
                    if (f != null) listener.onFound(f);
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(90, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static boolean open(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);

    private static Found probe(String host, int port) {
        String base = "http://" + host + (port == 80 ? "" : ":" + port);
        String html = get(base + "/", 65536);
        if (html == null) return null;
        String title = "";
        Matcher m = TITLE.matcher(html);
        if (m.find()) title = m.group(1).trim();
        boolean transfer = false;
        if (title.toLowerCase().contains("transfer")) {
            String api = get(base + "/api/files", 4096);
            String a = api == null ? "" : api.trim();
            transfer = a.startsWith("[") || a.startsWith("{");
        }
        if (title.isEmpty()) title = "Pagina en " + host + ":" + port;
        return new Found(base + "/", title, transfer);
    }

    /** Lee el inicio de una respuesta HTTP (cualquier codigo menor a 500 cuenta como pagina). */
    private static String get(String url, int max) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 500) return null;
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return "";
            byte[] buf = new byte[max];
            int n = 0, r;
            while (n < max && (r = in.read(buf, n, max - n)) > 0) n += r;
            in.close();
            return new String(buf, 0, n, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
