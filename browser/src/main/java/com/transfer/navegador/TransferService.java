package com.transfer.navegador;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Servicio en primer plano: mantiene el servidor activo con la pantalla apagada. */
public class TransferService extends Service {
    static final String EXTRA_PORT = "port";
    static final String NAME = "transfer";
    private static final String CHANNEL = "transfer";

    // Estado que lee la pantalla principal
    static volatile boolean running;
    static volatile String url = "", ipUrl = "", folderPath = "", error = "";

    private Server server;
    private Mdns mdns;
    private WifiManager.MulticastLock mlock;
    private WifiManager.WifiLock wlock;
    private PowerManager.WakeLock cpu;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = intent != null ? intent.getIntExtra(EXTRA_PORT, 4747) : 4747;
        stopAll();
        error = "";
        try {
            File folder = folder(this);
            folder.mkdirs();
            folderPath = folder.getAbsolutePath();
            InetAddress ip = localIp();
            String ipStr = ip != null ? ip.getHostAddress() : "127.0.0.1";
            String suffix = port == 80 ? "" : ":" + port;
            url = "http://" + NAME + ".local" + suffix;
            ipUrl = "http://" + ipStr + suffix;

            String page = readAsset("index.html")
                    .replace("__URL__", url).replace("__IPURL__", ipUrl);
            server = new Server(port, folder, page, name -> notifyText("Recibido: " + name));
            server.start();

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("transfer-mdns");
                mlock.setReferenceCounted(false);
                mlock.acquire();
                wlock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "transfer-wifi");
                wlock.setReferenceCounted(false);
                wlock.acquire();
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transfer:cpu");
            cpu.setReferenceCounted(false);
            cpu.acquire();

            if (ip != null) {
                try { mdns = new Mdns(NAME, ip); mdns.start(); }
                catch (Exception e) { mdns = null; }
            }
            running = true;
            startFg(ipUrl);
        } catch (Exception e) {
            error = "No se pudo usar el puerto " + port + ": " + e.getMessage();
            running = false;
            stopAll();
            startFg("Error");
            stopForegroundCompat();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAll();
        running = false;
        super.onDestroy();
    }

    private void stopAll() {
        if (server != null) { server.stop(); server = null; }
        if (mdns != null) { mdns.stop(); mdns = null; }
        if (mlock != null && mlock.isHeld()) mlock.release();
        if (wlock != null && wlock.isHeld()) wlock.release();
        if (cpu != null && cpu.isHeld()) cpu.release();
    }

    static File folder(Context c) {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File f = new File(dl, "Transfer");
        if (f.isDirectory() || f.mkdirs()) return f;
        return new File(c.getExternalFilesDir(null), "Transfer");
    }

    /** IPv4 de la red Wi-Fi o del punto de acceso del telefono. */
    static InetAddress localIp() {
        InetAddress best = null;
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String nm = n.getName();
                    if (nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan")) return a;
                    if (best == null && !nm.startsWith("rmnet")) best = a;
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return o.toString("UTF-8");
        }
    }

    private Notification build(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Transfer", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, TransferActivity.class), fl);
        return b.setContentTitle("Transfer activo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void startFg(String text) {
        Notification n = build(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, build(ipUrl + "  ·  " + text));
    }
}
