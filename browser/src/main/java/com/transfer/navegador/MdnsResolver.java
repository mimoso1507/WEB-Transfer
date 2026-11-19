package com.transfer.navegador;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Random;

/**
 * Resuelve nombres "algo.local" preguntando por mDNS en la red local.
 * Muchas TV y algunos Android no saben hacerlo; este navegador si.
 */
final class MdnsResolver {
    private MdnsResolver() {}

    /** Devuelve la IP (texto) de "nombre.local", o null si nadie responde. */
    static String resolve(String host) {
        String name = host.toLowerCase();
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(700);
            int id = new Random().nextInt(0xffff);
            byte[] q = query(name, id);
            InetAddress group = InetAddress.getByName("224.0.0.251");
            byte[] buf = new byte[9000];
            for (int attempt = 0; attempt < 4; attempt++) {
                sock.send(new DatagramPacket(q, q.length, group, 5353));
                long until = System.currentTimeMillis() + 700;
                while (System.currentTimeMillis() < until) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    String ip = parse(p.getData(), p.getLength(), name);
                    if (ip != null) return ip;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
        return null;
    }

    private static byte[] query(String name, int id) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w16(o, id); w16(o, 0); w16(o, 1); w16(o, 0); w16(o, 0); w16(o, 0);
        for (String part : name.split("\\.")) {
            byte[] b = part.getBytes("UTF-8");
            o.write(b.length); o.write(b, 0, b.length);
        }
        o.write(0);
        w16(o, 1);       // tipo A
        w16(o, 0x8001);  // clase IN, pidiendo respuesta directa (QU)
        return o.toByteArray();
    }

    private static String parse(byte[] d, int len, String want) {
        try {
            if (len < 12) return null;
            int flags = u16(d, 2);
            if ((flags & 0x8000) == 0) return null; // no es respuesta
            int qd = u16(d, 4);
            int rr = u16(d, 6) + u16(d, 8) + u16(d, 10);
            int[] off = {12};
            for (int i = 0; i < qd; i++) { readName(d, off); off[0] += 4; }
            for (int i = 0; i < rr && off[0] + 10 <= len; i++) {
                String n = readName(d, off);
                int type = u16(d, off[0]);
                int rdlen = u16(d, off[0] + 8);
                int data = off[0] + 10;
                off[0] = data + rdlen;
                if (type == 1 && rdlen == 4 && n.equals(want) && data + 4 <= len) {
                    return (d[data] & 0xff) + "." + (d[data + 1] & 0xff) + "."
                            + (d[data + 2] & 0xff) + "." + (d[data + 3] & 0xff);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int u16(byte[] d, int i) { return ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff); }

    private static void w16(ByteArrayOutputStream o, int v) { o.write((v >> 8) & 0xff); o.write(v & 0xff); }

    private static String readName(byte[] d, int[] off) throws Exception {
        StringBuilder sb = new StringBuilder();
        int pos = off[0], end = -1;
        for (int guard = 0; guard < 64; guard++) {
            int n = d[pos] & 0xff;
            if (n == 0) { pos++; break; }
            if ((n & 0xC0) == 0xC0) {
                if (end < 0) end = pos + 2;
                pos = ((n & 0x3F) << 8) | (d[pos + 1] & 0xff);
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, pos + 1, n, "UTF-8"));
            pos += 1 + n;
        }
        off[0] = end >= 0 ? end : pos;
        return sb.toString().toLowerCase();
    }
}
