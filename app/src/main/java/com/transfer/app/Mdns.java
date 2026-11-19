package com.transfer.app;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;

/** Responde a las preguntas mDNS por "<nombre>.local" con la IP del telefono. */
class Mdns {
    private static final int PORT = 5353;
    private final String fqdn;
    private final InetAddress ip;
    private MulticastSocket sock;
    private volatile boolean running;

    Mdns(String name, InetAddress ip) {
        this.fqdn = name.toLowerCase() + ".local";
        this.ip = ip;
    }

    void start() throws Exception {
        InetAddress group = InetAddress.getByName("224.0.0.251");
        sock = new MulticastSocket(null);
        sock.setReuseAddress(true);
        sock.bind(new InetSocketAddress(PORT));
        sock.setTimeToLive(255);
        NetworkInterface nif = NetworkInterface.getByInetAddress(ip);
        if (nif != null) {
            sock.setNetworkInterface(nif);
            sock.joinGroup(new InetSocketAddress(group, PORT), nif);
        } else {
            sock.joinGroup(group);
        }
        running = true;
        Thread t = new Thread(() -> loop(group), "mdns");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        if (sock != null) sock.close();
    }

    private void loop(InetAddress group) {
        byte[] buf = new byte[9000];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                byte[] d = p.getData();
                int len = p.getLength();
                if (len < 12) continue;
                int id = u16(d, 0), flags = u16(d, 2), qd = u16(d, 4);
                if ((flags & 0x8000) != 0) continue;
                int[] off = {12};
                boolean ask = false, unicast = false;
                for (int i = 0; i < qd && off[0] < len; i++) {
                    String name = readName(d, off);
                    int qtype = u16(d, off[0]), qclass = u16(d, off[0] + 2);
                    off[0] += 4;
                    if (name.equals(fqdn) && (qtype == 1 || qtype == 255)) {
                        ask = true;
                        unicast |= (qclass & 0x8000) != 0;
                    }
                }
                if (!ask) continue;
                boolean legacy = p.getPort() != PORT;
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                w16(o, legacy ? id : 0); w16(o, 0x8400); w16(o, 0); w16(o, 1); w16(o, 0); w16(o, 0);
                for (String part : fqdn.split("\\.")) {
                    byte[] b = part.getBytes("UTF-8");
                    o.write(b.length); o.write(b, 0, b.length);
                }
                o.write(0);
                w16(o, 1); w16(o, 0x8001); w16(o, 0); w16(o, 120); w16(o, 4);
                byte[] a = ip.getAddress();
                o.write(a, 0, 4);
                byte[] pkt = o.toByteArray();
                if (legacy || unicast)
                    sock.send(new DatagramPacket(pkt, pkt.length, p.getAddress(), p.getPort()));
                if (!legacy)
                    sock.send(new DatagramPacket(pkt, pkt.length, group, PORT));
            } catch (Exception e) {
                if (!running) return;
            }
        }
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
