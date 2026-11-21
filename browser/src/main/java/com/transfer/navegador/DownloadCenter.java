package com.transfer.navegador;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Lista compartida de descargas (en curso y terminadas) para mostrar su estado en la app. */
final class DownloadCenter {
    static final int RUNNING = 0, DONE = 1, ERROR = 2, CANCELLED = 3;

    static final class Item {
        final int id;
        final String name, url, mime;
        volatile int state = RUNNING;
        volatile long done, total, speed;
        volatile String error = "";
        volatile Uri uri;
        volatile File file;
        volatile boolean cancel;
        final long started = System.currentTimeMillis();

        Item(int id, String name, String url, String mime) {
            this.id = id; this.name = name; this.url = url; this.mime = mime == null ? "" : mime;
        }

        boolean isApk() {
            return name.toLowerCase(Locale.ROOT).endsWith(".apk") || mime.equals("application/vnd.android.package-archive");
        }

        boolean isMedia() {
            String n = name.toLowerCase(Locale.ROOT);
            return mime.startsWith("video/") || mime.startsWith("audio/")
                    || n.matches(".*\\.(mp4|mkv|webm|avi|mov|ts|m4v|mp3|flac|ogg|opus|m4a|aac|wav|m3u8|mpd)$");
        }

        /** Direccion para abrir o instalar el archivo descargado. */
        Uri openUri() {
            File f = file;
            if (f != null && f.exists()) return Build.VERSION.SDK_INT < 24 ? Uri.fromFile(f) : FileProvider.uriFor(f);
            return uri;
        }
    }

    interface Listener {
        void changed();
        void finished(Item item);
    }

    static final List<Item> items = new CopyOnWriteArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static int nextId = 1;

    private DownloadCenter() {}

    static synchronized Item add(String name, String url, String mime) {
        Item it = new Item(nextId++, name, url, mime);
        items.add(0, it);
        changed();
        return it;
    }

    static Item find(int id) {
        for (Item i : items) if (i.id == id) return i;
        return null;
    }

    static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    static void changed() {
        main.post(() -> { for (Listener l : listeners) l.changed(); });
    }

    static void finished(final Item item) {
        main.post(() -> {
            for (Listener l : listeners) l.finished(item);
            for (Listener l : listeners) l.changed();
        });
    }

    static int active() {
        int n = 0;
        for (Item i : items) if (i.state == RUNNING) n++;
        return n;
    }

    static void clearFinished() {
        for (Item i : items) if (i.state != RUNNING) items.remove(i);
        changed();
    }

    static void remove(Item i) {
        if (i.state == RUNNING) return;
        items.remove(i);
        changed();
    }

}
