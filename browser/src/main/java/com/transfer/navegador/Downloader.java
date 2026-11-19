package com.transfer.navegador;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descarga archivos a Descargas/Navegador mostrando el progreso en una notificacion. */
final class Downloader {
    private static final String CHANNEL = "descargas";
    private static int nextId = 100;

    private Downloader() {}

    static void start(final Activity act, final String url, final String userAgent,
                      final String disposition, final String mime) {
        if (url.startsWith("blob:")) {
            Toast.makeText(act, "Esta pagina genera el archivo de una forma que no se puede descargar aqui.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String name = fileName(url, disposition, mime);
        Toast.makeText(act, "Descargando " + name, Toast.LENGTH_SHORT).show();
        final String cookies = url.startsWith("http") ? CookieManager.getInstance().getCookie(url) : null;
        new Thread(() -> {
            final int id = nextId++;
            NotificationManager nm = (NotificationManager) act.getSystemService(Activity.NOTIFICATION_SERVICE);
            String result;
            try {
                long total;
                InputStream in;
                HttpURLConnection c = null;
                if (url.startsWith("data:")) {
                    int comma = url.indexOf(',');
                    String meta = url.substring(5, comma);
                    String body = url.substring(comma + 1);
                    byte[] bytes = meta.endsWith(";base64") ? Base64.decode(body, Base64.DEFAULT)
                            : URLDecoder.decode(body, "UTF-8").getBytes("UTF-8");
                    total = bytes.length;
                    in = new java.io.ByteArrayInputStream(bytes);
                } else {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
                    if (cookies != null) c.setRequestProperty("Cookie", cookies);
                    int code = c.getResponseCode();
                    if (code >= 400) throw new Exception("el servidor respondio " + code);
                    total = c.getContentLength();
                    in = c.getInputStream();
                }
                Target t = open(act, name, mime);
                byte[] buf = new byte[65536];
                long done = 0, lastShown = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    t.out.write(buf, 0, r);
                    done += r;
                    long now = System.currentTimeMillis();
                    if (now - lastShown > 700) {
                        lastShown = now;
                        notify(act, nm, id, name, total > 0 ? (int) (done * 100 / total) : -1, null);
                    }
                }
                t.out.close();
                in.close();
                if (c != null) c.disconnect();
                t.finish();
                result = "Guardado en " + t.where;
            } catch (Exception e) {
                result = "No se pudo descargar: " + e.getMessage();
            }
            final String msg = result;
            notify(act, nm, id, name, 100, msg);
            act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_LONG).show());
        }, "descarga").start();
    }

    private static final Pattern FN_STAR = Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)");
    private static final Pattern FN = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?");

    static String fileName(String url, String disposition, String mime) {
        String n = null;
        if (disposition != null) {
            try {
                Matcher m = FN_STAR.matcher(disposition);
                if (m.find()) n = URLDecoder.decode(m.group(1).trim(), "UTF-8");
                else {
                    m = FN.matcher(disposition);
                    if (m.find()) n = m.group(1).trim();
                }
            } catch (Exception ignored) {
            }
        }
        if (n == null || n.isEmpty()) n = URLUtil.guessFileName(url, disposition, mime);
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.isEmpty() ? "archivo" : n;
    }

    /** Destino de la descarga. */
    private static final class Target {
        OutputStream out;
        String where;
        Uri uri;
        ContentResolver cr;
        void finish() {
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            }
        }
    }

    private static Target open(Activity act, String name, String mime) throws Exception {
        Target t = new Target();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Navegador");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            t.cr = act.getContentResolver();
            t.uri = t.cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (t.uri == null) throw new Exception("no se pudo crear el archivo");
            t.out = t.cr.openOutputStream(t.uri);
            t.where = "Descargas/Navegador";
            return t;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Navegador");
        try {
            dir.mkdirs();
            File f = unique(dir, name);
            t.out = new FileOutputStream(f);
            t.where = "Descargas/Navegador";
            return t;
        } catch (Exception noPermission) {
            dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = act.getFilesDir();
            File f = unique(dir, name);
            t.out = new FileOutputStream(f);
            t.where = f.getParent();
            return t;
        }
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    @SuppressWarnings("deprecation")
    private static void notify(Activity act, NotificationManager nm, int id, String name, int pct, String done) {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CHANNEL) == null)
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Descargas",
                            NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(act, CHANNEL);
            } else {
                b = new Notification.Builder(act);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(name);
            if (done == null) {
                b.setContentText("Descargando...").setOngoing(true).setProgress(100, Math.max(pct, 0), pct < 0);
            } else {
                b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText(done).setAutoCancel(true);
                Intent i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
                b.setContentIntent(PendingIntent.getActivity(act, id, i, flags));
            }
            nm.notify(id, b.build());
        } catch (Exception ignored) {
        }
    }
}
