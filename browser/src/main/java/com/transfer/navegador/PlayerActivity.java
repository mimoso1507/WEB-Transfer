package com.transfer.navegador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.view.Gravity;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reproductor de video y audio con controles (play, pausa, adelantar, velocidad, audio, subtitulos).
 * Lee archivos locales y enlaces de internet: MP4, MKV, WebM, AVI, TS, MP3, FLAC y mas, ademas de
 * streaming VOD (HLS .m3u8, DASH .mpd, Smooth Streaming). Se maneja con el control remoto.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerActivity extends Activity {
    static final String EXTRA_URIS = "uris";
    static final String EXTRA_TITLES = "titles";
    static final String EXTRA_INDEX = "index";

    private PlayerView view;
    private ExoPlayer player;
    private TextView message;
    private SharedPreferences prefs;
    private List<String> uris = new ArrayList<>();
    private int errorsInRow;

    /** Abre el reproductor con una lista; empieza por la posicion start. */
    static void play(Context c, List<String> uris, List<String> titles, int start) {
        Intent i = new Intent(c, PlayerActivity.class)
                .putStringArrayListExtra(EXTRA_URIS, new ArrayList<>(uris))
                .putStringArrayListExtra(EXTRA_TITLES, new ArrayList<>(titles))
                .putExtra(EXTRA_INDEX, start);
        c.startActivity(i);
    }

    static boolean isStreamUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u.endsWith(".m3u8") || u.endsWith(".mpd") || u.endsWith(".m3u") || u.endsWith(".pls")
                || u.endsWith("/manifest") || u.endsWith(".ism") || u.contains(".ism/");
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("reproductor", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setControllerShowTimeoutMs(4000);
        view.setKeepScreenOn(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setBackgroundColor(Color.parseColor("#CC000000"));
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        message.setPadding(40, 30, 40, 30);
        message.setVisibility(View.GONE);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(message, mlp);
        setContentView(root);
        hideSystemBars();

        Intent in = getIntent();
        List<String> titles = new ArrayList<>();
        int start = 0;
        if (in.getStringArrayListExtra(EXTRA_URIS) != null) {
            uris = in.getStringArrayListExtra(EXTRA_URIS);
            List<String> t = in.getStringArrayListExtra(EXTRA_TITLES);
            if (t != null) titles = t;
            start = in.getIntExtra(EXTRA_INDEX, 0);
        } else if (in.getData() != null) {
            uris.add(in.getData().toString());
        }
        if (uris.isEmpty()) { finish(); return; }
        final int startIndex = Math.max(0, Math.min(start, uris.size() - 1));
        final List<String> titleList = titles;
        // Las listas .m3u simples (una direccion por linea) se leen antes de empezar.
        new Thread(() -> {
            final List<String> flat = new ArrayList<>();
            final List<String> flatTitles = new ArrayList<>();
            int newStart = startIndex;
            for (int i = 0; i < uris.size(); i++) {
                String u = uris.get(i);
                List<String> inner = isSimplePlaylist(u) ? readPlaylist(u) : null;
                if (inner != null && !inner.isEmpty()) {
                    if (i == startIndex) newStart = flat.size();
                    for (String x : inner) { flat.add(x); flatTitles.add(null); }
                } else {
                    flat.add(u);
                    flatTitles.add(i < titleList.size() ? titleList.get(i) : null);
                }
            }
            final int s = newStart;
            runOnUiThread(() -> { if (!isDestroyed()) begin(flat, flatTitles, s); });
        }, "lista").start();
    }

    private static boolean isSimplePlaylist(String u) {
        String l = u.toLowerCase(Locale.ROOT);
        int q = l.indexOf('?');
        if (q > 0) l = l.substring(0, q);
        return l.endsWith(".m3u") || l.endsWith(".pls");
    }

    /** Lee una lista .m3u / .pls (local o de internet) y devuelve las direcciones que contiene. */
    private List<String> readPlaylist(String u) {
        List<String> out = new ArrayList<>();
        try {
            Uri uri = Uri.parse(u);
            java.io.InputStream in;
            String base;
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                in = c.getInputStream();
                base = u.substring(0, u.lastIndexOf('/') + 1);
            } else if ("file".equals(uri.getScheme())) {
                in = new java.io.FileInputStream(new File(uri.getPath()));
                base = new File(uri.getPath()).getParent() + "/";
            } else {
                in = getContentResolver().openInputStream(uri);
                base = null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 5000) {
                line = line.trim();
                if (line.toLowerCase(Locale.ROOT).startsWith("file") && line.contains("=")) line = line.substring(line.indexOf('=') + 1);
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[") || line.contains("=")) continue;
                if (line.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) out.add(line);
                else if (base != null) {
                    out.add(line.startsWith("/") ? "file://" + line : (base.startsWith("/") ? "file://" + base + line : base + line));
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return out;
    }

    private void begin(List<String> list, List<String> titles, int start) {
        uris = list;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setUserAgent("Mozilla/5.0 (Linux; Android) NavegadorLocal/1.0");
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build();
        view.setPlayer(player);

        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String u = list.get(i);
            MediaItem.Builder b = new MediaItem.Builder().setUri(u);
            String m = mimeFor(u);
            if (m != null) b.setMimeType(m);
            String t = i < titles.size() ? titles.get(i) : null;
            if (t == null) {
                t = Uri.parse(u).getLastPathSegment();
            }
            if (t != null) b.setMediaMetadata(new MediaMetadata.Builder().setTitle(t).build());
            items.add(b.build());
        }
        long pos = savedPosition(list.get(start));
        player.setMediaItems(items, start, pos);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                errorsInRow++;
                String why = e.getErrorCodeName();
                if (e.getCause() != null && e.getCause().getMessage() != null) why += "\n" + e.getCause().getMessage();
                if (player.hasNextMediaItem() && errorsInRow < 4) {
                    toast("No se pudo reproducir este archivo, paso al siguiente.");
                    player.seekToNextMediaItem();
                    player.prepare();
                    return;
                }
                showError("No se pudo reproducir\n" + why);
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                message.setVisibility(View.GONE);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { errorsInRow = 0; message.setVisibility(View.GONE); }
            }
        });
        player.prepare();
        player.setPlayWhenReady(true);
        view.requestFocus();
    }

    private static String mimeFor(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        if (u.endsWith(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (u.endsWith(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (u.endsWith(".ism") || u.endsWith("/manifest") || u.contains(".ism/")) return MimeTypes.APPLICATION_SS;
        return null;
    }

    private void showError(String text) {
        message.setText(text + "\n\nAtrás para salir");
        message.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- continuar donde quedo

    private long savedPosition(String uri) {
        if (uri.startsWith("http") && isStreamUrl(uri)) return 0; // directos: no se guarda
        long p = prefs.getLong("pos:" + uri, 0);
        return p > 5000 ? p : 0;
    }

    private void savePosition() {
        if (player == null) return;
        int i = player.getCurrentMediaItemIndex();
        if (i < 0 || i >= uris.size()) return;
        String u = uris.get(i);
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        SharedPreferences.Editor e = prefs.edit();
        if (dur > 0 && pos > dur - 15000) e.remove("pos:" + u);
        else if (pos > 5000 && dur > 0) e.putLong("pos:" + u, pos);
        e.apply();
    }

    // ---------------------------------------------------------------- ciclo de vida y teclas

    private void hideSystemBars() {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemBars();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Con los controles ocultos, izquierda/derecha adelantan y atrasan sin abrir los controles.
        if (player != null && e.getAction() == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible()) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: player.seekForward(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: player.seekBack(); return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePosition();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        savePosition();
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

}
