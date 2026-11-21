package com.transfer.navegador;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
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

/** Descarga archivos a Descargas/Navegador y reporta el estado en DownloadCenter y en una notificacion. */
final class Downloader {
    private static final String CHANNEL = "descargas";

    private Downloader() {}

    static void start(final Activity act, final String url, final String userAgent,
                      final String disposition, final String mime) {
        final Context ctx = act.getApplicationContext();
        if (url.startsWith("blob:")) {
            Toast.makeText(ctx, "Esta pagina genera el archivo de una forma que no se puede descargar aqui.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String name = fileName(url, disposition, mime);
        final DownloadCenter.Item item = DownloadCenter.add(name, url, mime);
        Toast.makeText(ctx, "Descargando " + name, Toast.LENGTH_SHORT).show();
        final String cookies = url.startsWith("http") ? CookieManager.getInstance().getCookie(url) : null;
        new Thread(() -> run(ctx, item, userAgent, cookies), "descarga").start();
    }

    private static void run(Context ctx, DownloadCenter.Item it, String userAgent, String cookies) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Target t = null;
        InputStream in = null;
        HttpURLConnection c = null;
        try {
            if (it.url.startsWith("data:")) {
                int comma = it.url.indexOf(',');
                String meta = it.url.substring(5, comma);
                String body = it.url.substring(comma + 1);
                byte[] bytes = meta.endsWith(";base64") ? Base64.decode(body, Base64.DEFAULT)
                        : URLDecoder.decode(body, "UTF-8").getBytes("UTF-8");
                it.total = bytes.length;
                in = new java.io.ByteArrayInputStream(bytes);
            } else {
                c = (HttpURLConnection) new URL(it.url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                int code = c.getResponseCode();
                if (code >= 400) throw new Exception("el servidor respondio " + code);
                it.total = c.getContentLengthLong();
                in = c.getInputStream();
            }
            t = open(ctx, it.name, it.mime);
            byte[] buf = new byte[65536];
            long lastShown = 0, lastBytes = 0, lastTime = System.currentTimeMillis();
            int r;
            while ((r = in.read(buf)) > 0) {
                if (it.cancel) break;
                t.out.write(buf, 0, r);
                it.done += r;
                long now = System.currentTimeMillis();
                if (now - lastShown > 500) {
                    it.speed = (it.done - lastBytes) * 1000 / Math.max(1, now - lastTime);
                    lastBytes = it.done;
                    lastTime = now;
                    lastShown = now;
                    notify(ctx, nm, it);
                    DownloadCenter.changed();
                }
            }
            t.out.close();
            if (it.cancel) {
                t.abort();
                it.state = DownloadCenter.CANCELLED;
            } else {
                t.finish();
                it.uri = t.uri;
                it.file = t.resolveFile();
                it.done = Math.max(it.done, 0);
                if (it.total <= 0) it.total = it.done;
                it.state = DownloadCenter.DONE;
            }
        } catch (Exception e) {
            if (t != null) { try { t.out.close(); } catch (Exception ignored) {} t.abort(); }
            it.error = e.getMessage() == null ? e.toString() : e.getMessage();
            it.state = DownloadCenter.ERROR;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
        }
        it.speed = 0;
        notify(ctx, nm, it);
        DownloadCenter.finished(it);
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
        Uri uri;
        File file;
        ContentResolver cr;

        void finish() {
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            }
        }

        void abort() {
            try {
                if (uri != null) cr.delete(uri, null, null);
                else if (file != null) file.delete();
            } catch (Exception ignored) {
            }
        }

        /** Ruta real del archivo (para poder abrirlo e instalarlo), si se puede saber. */
        File resolveFile() {
            if (file != null) return file;
            if (uri == null) return null;
            try (Cursor q = cr.query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (q != null && q.moveToFirst()) {
                    String p = q.getString(0);
                    if (p != null) {
                        File f = new File(p);
                        if (f.exists() && f.canRead()) return f;
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static Target open(Context ctx, String name, String mime) throws Exception {
        Target t = new Target();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Navegador");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            t.cr = ctx.getContentResolver();
            t.uri = t.cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (t.uri == null) throw new Exception("no se pudo crear el archivo");
            t.out = t.cr.openOutputStream(t.uri);
            return t;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Navegador");
        try {
            dir.mkdirs();
            t.file = unique(dir, name);
            t.out = new FileOutputStream(t.file);
            return t;
        } catch (Exception noPermission) {
            dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = ctx.getFilesDir();
            t.file = unique(dir, name);
            t.out = new FileOutputStream(t.file);
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
    private static void notify(Context ctx, NotificationManager nm, DownloadCenter.Item it) {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CHANNEL) == null)
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Descargas",
                            NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(ctx, CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setContentTitle(it.name);
            if (it.state == DownloadCenter.RUNNING) {
                int pct = it.total > 0 ? (int) (it.done * 100 / it.total) : -1;
                b.setSmallIcon(android.R.drawable.stat_sys_download).setContentText("Descargando...")
                        .setOngoing(true).setProgress(100, Math.max(pct, 0), pct < 0);
            } else {
                String text = it.state == DownloadCenter.DONE
                        ? (it.isApk() ? "Descarga completa. Toca para instalar." : "Descarga completa")
                        : it.state == DownloadCenter.CANCELLED ? "Descarga cancelada" : "Error: " + it.error;
                b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText(text).setAutoCancel(true);
            }
            Intent open = new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("nav://descargas")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            b.setContentIntent(PendingIntent.getActivity(ctx, it.id, open, flags));
            nm.notify(100 + it.id, b.build());
        } catch (Exception ignored) {
        }
    }
}
