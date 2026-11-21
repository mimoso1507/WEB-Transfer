package com.transfer.navegador;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Navegador pensado para apps de la red local (como Transfer): entiende nombres .local, encuentra
 * servidores en el Wi-Fi, sube y descarga archivos, instala APK, tiene pestanas y se maneja con
 * el control remoto de la TV.
 */
public class MainActivity extends Activity {
    private static final String HOME = "nav://inicio/";
    private static final String ERROR_TITLE = "No se pudo abrir";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";
    private static final int PICK_FILES = 1, ASK_STORAGE = 2;

    /** Una pestana: su propio WebView y su estado. */
    private final class Tab {
        WebView web;
        Bitmap icon;
        String title = "";
        String page = "";       // "inicio", "descargas", "historial" o "" (pagina normal)
        boolean desktop;
        String lastDownloadsSig = "";
    }

    private final List<Tab> tabs = new ArrayList<>();
    private Tab current;
    private WebView web; // WebView de la pestana actual

    private EditText address;
    private Button favButton, dlButton, dlBar;
    private ProgressBar progress;
    private FrameLayout root, fullscreen, holder;
    private LinearLayout topArea, tabStrip, findBar;
    private HorizontalScrollView tabScroll;
    private TextView findInfo;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String defaultUa;
    private boolean barsHidden;

    /** Nombres .local ya resueltos: nombre -> IP, e IP:puerto -> nombre para mostrarlo. */
    private final Map<String, String> nameToIp = new HashMap<>();
    private final Map<String, String> ipToName = new HashMap<>();

    private final Map<String, Scanner.Found> found = Collections.synchronizedMap(new LinkedHashMap<String, Scanner.Found>());
    private volatile boolean scanning;
    private String[] pendingDownload;

    private final DownloadCenter.Listener downloadListener = new DownloadCenter.Listener() {
        @Override public void changed() { updateDownloadsUi(); }

        @Override
        public void finished(DownloadCenter.Item item) {
            if (item.state == DownloadCenter.DONE && item.isApk()) {
                final DownloadCenter.Item apk = item;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Descarga completa")
                        .setMessage(item.name + "\n\n¿Instalar esta app ahora?")
                        .setPositiveButton("Instalar", (d, w) -> Installer.install(MainActivity.this, apk.openUri()))
                        .setNegativeButton("Más tarde", null)
                        .show();
            } else if (item.state == DownloadCenter.DONE) {
                toast("Descarga completa: " + item.name);
            } else if (item.state == DownloadCenter.ERROR) {
                toast("No se pudo descargar " + item.name + ": " + item.error);
            }
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("navegador", MODE_PRIVATE);
        buildUi();
        scan();
        Intent i = getIntent();
        Tab t = newTab();
        if (i != null && i.getData() != null) go(i.getDataString());
        else showHome();
        if (t == null) finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri d = intent.getData();
        if (d == null) return;
        // Los accesos directos y enlaces externos se abren en una pestana nueva (o en la actual si esta en el inicio).
        if (!"nav".equals(d.getScheme()) && !isHome()) newTab();
        go(intent.getDataString());
    }

    // ---------------------------------------------------------------- interfaz

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, Color.parseColor("#1b2333")}));
        b.setBackgroundResource(R.drawable.btn_bg);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setFocusable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void primary(Button b) {
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setTextColor(Color.WHITE);
    }

    private void buildUi() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        bar.addView(button("‹", v -> { if (web.canGoBack()) web.goBack(); }));
        bar.addView(button("›", v -> { if (web.canGoForward()) web.goForward(); }));
        bar.addView(button("↻", v -> {
            if (isHome()) scan(); else web.reload();
        }));
        bar.addView(button("Inicio", v -> showHome()));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Toca aqui para escribir una direccion");
        address.setTextColor(Color.BLACK);
        address.setSelectAllOnFocus(true);
        // Al tocar o pulsar OK se abre un cuadro para escribir (funciona igual con teclado, control remoto o tactil).
        address.setKeyListener(null);
        address.setCursorVisible(false);
        address.setFocusableInTouchMode(false);
        address.setFocusable(true);
        address.setOnClickListener(v -> askAddress());
        address.setBackgroundResource(R.drawable.url_bg);
        address.setTextSize(18);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setMinWidth(dp(160));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(44), 1);
        alp.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(address, alp);

        Button write = button("✎  Escribir direccion", v -> askAddress());
        primary(write);
        write.setTextSize(19);
        write.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        write.setPadding(dp(18), 0, dp(18), 0);
        write.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        ((LinearLayout.LayoutParams) write.getLayoutParams()).setMargins(dp(6), 0, dp(6), 0);
        bar.addView(write);
        favButton = button("☆", v -> toggleFavorite());
        bar.addView(favButton);
        dlButton = button("⬇", v -> showDownloads());
        bar.addView(dlButton);
        bar.addView(button("⋮  Menu", v -> showMenu()));

        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.setFillViewport(true);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setBackgroundColor(Color.parseColor("#F4F6FA"));
        barScroll.addView(bar, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(6), dp(2), dp(6), dp(4));
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(Color.parseColor("#E9EDF5"));
        tabScroll.addView(tabStrip);
        tabScroll.setVisibility(View.GONE);

        dlBar = button("", v -> showDownloads());
        primary(dlBar);
        dlBar.setTextSize(16);
        dlBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        dlp.setMargins(dp(6), dp(3), dp(6), dp(3));
        dlBar.setLayoutParams(dlp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);

        topArea = new LinearLayout(this);
        topArea.setOrientation(LinearLayout.VERTICAL);
        topArea.addView(barScroll);
        topArea.addView(tabScroll);
        topArea.addView(dlBar);

        holder = new FrameLayout(this);

        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(Color.parseColor("#F4F6FA"));
        findBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        findInfo = new TextView(this);
        findInfo.setTextSize(16);
        findInfo.setTextColor(Color.parseColor("#1b2333"));
        findInfo.setPadding(dp(8), 0, dp(12), 0);
        findBar.addView(findInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        findBar.addView(button("Buscar otra", v -> askFind()));
        findBar.addView(button("↑", v -> web.findNext(false)));
        findBar.addView(button("↓", v -> web.findNext(true)));
        findBar.addView(button("Cerrar", v -> closeFind()));
        findBar.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        col.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        col.addView(findBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);

        root = new FrameLayout(this);
        root.addView(col);
        root.addView(fullscreen);
        setContentView(root);
    }

    // ---------------------------------------------------------------- pestanas

    private Tab tabOf(WebView w) {
        Object t = w.getTag();
        return t instanceof Tab ? (Tab) t : null;
    }

    /** Crea una pestana nueva y la muestra. */
    private Tab newTab() {
        Tab t = new Tab();
        t.web = new WebView(this);
        t.web.setTag(t);
        t.web.setFocusable(true);
        t.web.setFocusableInTouchMode(true);
        configureWeb(t.web, t);
        tabs.add(t);
        switchTo(t);
        return t;
    }

    private void switchTo(Tab t) {
        if (findBar.getVisibility() == View.VISIBLE) closeFind();
        current = t;
        web = t.web;
        holder.removeAllViews();
        ViewGroup old = (ViewGroup) web.getParent();
        if (old != null) old.removeView(web);
        holder.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.requestFocus();
        showAddress(web.getUrl());
        progress.setVisibility(View.GONE);
        rebuildTabs();
    }

    private void closeTab(Tab t) {
        if (tabs.size() <= 1) { showHome(); return; }
        int i = tabs.indexOf(t);
        tabs.remove(t);
        holder.removeView(t.web);
        t.web.destroy();
        if (t == current) switchTo(tabs.get(Math.max(0, i - 1)));
        else rebuildTabs();
    }

    private String tabLabel(Tab t) {
        String s = t.page.equals("inicio") || (t.title.isEmpty() && t.web.getUrl() == null) ? "Inicio"
                : t.page.equals("descargas") ? "Descargas" : t.page.equals("historial") ? "Historial"
                : t.title.isEmpty() ? "Cargando..." : t.title;
        return s.length() > 18 ? s.substring(0, 17) + "…" : s;
    }

    private void rebuildTabs() {
        tabStrip.removeAllViews();
        tabScroll.setVisibility(tabs.size() > 1 && !barsHidden ? View.VISIBLE : View.GONE);
        for (final Tab t : tabs) {
            Button b = button(tabLabel(t), v -> switchTo(t));
            b.setTextSize(15);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            lp.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(lp);
            if (t == current) primary(b);
            tabStrip.addView(b);
            if (t == current) {
                Button x = button("✕", v -> closeTab(t));
                x.setTextSize(15);
                x.setLayoutParams(lp);
                tabStrip.addView(x);
            }
        }
        Button plus = button("＋", v -> { newTab(); showHome(); });
        plus.setTextSize(15);
        plus.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        tabStrip.addView(plus);
    }

    private void tabsDialog() {
        String[] names = new String[tabs.size()];
        for (int i = 0; i < names.length; i++) names[i] = (tabs.get(i) == current ? "▶ " : "") + tabLabel(tabs.get(i));
        new AlertDialog.Builder(this).setTitle("Pestañas").setItems(names, (d, w) -> switchTo(tabs.get(w))).show();
    }

    // ---------------------------------------------------------------- WebView

    @SuppressWarnings({"SetJavaScriptEnabled", "deprecation"})
    private void configureWeb(final WebView w, final Tab tab) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true); // los enlaces "nueva ventana" se abren en una pestana nueva
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(prefs.getInt("zoom", 100));
        if (defaultUa == null) defaultUa = s.getUserAgentString() + " NavegadorLocal/1.0";
        s.setUserAgentString(defaultUa);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.icon = favicon;
                if (view == web) showAddress(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == web) showAddress(url);
                if (url != null && url.startsWith("http")) {
                    tab.page = "";
                    if (!ERROR_TITLE.equals(view.getTitle())) remember(url, view.getTitle());
                    rebuildTabs();
                    // Marca bien lo que esta seleccionado, para usar la pagina con el control remoto.
                    view.evaluateJavascript("(function(){if(document.getElementById('__navfoco'))return;"
                            + "var s=document.createElement('style');s.id='__navfoco';"
                            + "s.textContent='a:focus,button:focus,input:focus,select:focus,textarea:focus,[tabindex]:focus,label:focus'"
                            + "+'{outline:3px solid #1E5BD8 !important;outline-offset:2px}';"
                            + "(document.head||document.documentElement).appendChild(s);"
                            // Las zonas para soltar archivos suelen ser <label> o <div>: las hacemos seleccionables.
                            + "document.querySelectorAll('label[for],[onclick]').forEach(function(e){if(!e.hasAttribute('tabindex'))e.tabIndex=0;});"
                            + "document.addEventListener('keydown',function(e){var t=e.target;if((e.key==='Enter'||e.keyCode===23)&&t&&t.tabIndex>=0&&!/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/.test(t.tagName)){t.click();}},true);"
                            + "})()", null);
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                showError(view, failingUrl, description);
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Certificado no valido")
                        .setMessage("La conexion segura de " + Uri.parse(error.getUrl()).getHost()
                                + " no tiene un certificado valido. Es normal en apps de tu red local, "
                                + "pero no continues si es un sitio de internet como un banco.")
                        .setPositiveButton("Continuar", (d, x) -> handler.proceed())
                        .setNegativeButton("Cancelar", (d, x) -> handler.cancel())
                        .setOnCancelListener(d -> handler.cancel())
                        .show();
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                tab.icon = icon;
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                tab.title = title == null ? "" : title;
                rebuildTabs();
            }

            @Override
            public void onProgressChanged(WebView view, int p) {
                if (view != web) return;
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (!isUserGesture) return false; // bloquea ventanas emergentes que nadie pidio
                Tab t = newTab();
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(t.web);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                Tab t = tabOf(window);
                if (t != null) closeTab(t);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return chooseFiles(callback, params);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        w.setDownloadListener((url, userAgent, disposition, mime, length) -> download(url, userAgent, disposition, mime));

        // Pulsacion larga (o OK mantenido) sobre un enlace o imagen: menu con opciones.
        w.setOnLongClickListener(v -> {
            WebView.HitTestResult r = w.getHitTestResult();
            String extra = r.getExtra();
            if (extra == null) return false;
            int type = r.getType();
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                linkMenu(extra, false);
                return true;
            }
            if (type == WebView.HitTestResult.IMAGE_TYPE) {
                linkMenu(extra, true);
                return true;
            }
            return false;
        });
    }

    private void linkMenu(final String url, final boolean image) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> runs = new ArrayList<>();
        if (!image) {
            labels.add("Abrir"); runs.add(() -> go(url));
            labels.add("Abrir en pestaña nueva"); runs.add(() -> { newTab(); go(url); });
        } else {
            labels.add("Ver imagen"); runs.add(() -> { newTab(); go(url); });
            labels.add("Descargar imagen"); runs.add(() -> startDownload(url, defaultUa, null, null));
        }
        labels.add("Copiar enlace"); runs.add(() -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("enlace", url));
            toast("Enlace copiado");
        });
        labels.add("Compartir enlace"); runs.add(() -> shareText(url));
        if (!image) { labels.add("Descargar"); runs.add(() -> startDownload(url, defaultUa, null, null)); }
        new AlertDialog.Builder(this).setTitle(url.length() > 60 ? url.substring(0, 60) + "…" : url)
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run()).show();
    }

    // ---------------------------------------------------------------- navegacion

    private boolean isHome() {
        String u = web.getUrl();
        return u == null || u.startsWith("nav:") || u.startsWith("data:") || u.startsWith("about:");
    }

    /** Cuadro para escribir o pegar una direccion; tambien sirve para buscar en internet. */
    private void askAddress() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(18);
        input.setHint("https://ejemplo.com  o  transfer.local:4747");
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        String cur = isHome() || web.getUrl() == null ? "" : display(web.getUrl());
        input.setText(cur);
        input.setSelectAllOnFocus(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Escribir direccion")
                .setView(wrap)
                .setPositiveButton("Ir", (d, w) -> submitAddress(input.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .create();
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                dlg.dismiss();
                submitAddress(input.getText().toString());
                return true;
            }
            return false;
        });
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        input.requestFocus();
    }

    private void submitAddress(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) return;
        web.requestFocus();
        go(toUrl(text));
    }

    /** Convierte lo que escribe la persona en una direccion. */
    static String toUrl(String text) {
        if (text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return text;
        boolean looksLikeHost = !text.contains(" ") && (text.contains(".") || text.contains(":")
                || text.equalsIgnoreCase("localhost"));
        if (looksLikeHost) return "http://" + text;
        try {
            return "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return "http://" + text;
        }
    }

    /** Abre una direccion; los nombres .local se resuelven antes por mDNS. */
    private void go(String url) {
        if (url == null) return;
        if (url.startsWith("nav:")) { handleNav(Uri.parse(url)); return; }
        if (!handleNavigation(url)) web.loadUrl(url);
    }

    /** true si nosotros nos encargamos de abrir la direccion. */
    private boolean handleNavigation(final String url) {
        if (url == null) return false;
        final Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (scheme.equals("nav")) { handleNav(u); return true; }
        if (scheme.equals("http") || scheme.equals("https")) {
            final String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            if (!host.endsWith(".local")) return false;
            final WebView target = web;
            String ip = nameToIp.get(host);
            if (ip != null) { target.loadUrl(replaceHost(u, ip)); return true; }
            address.setText(url);
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(10);
            new Thread(() -> {
                WifiManager.MulticastLock lock = multicastLock();
                String resolved = MdnsResolver.resolve(host);
                if (lock != null) lock.release();
                ui.post(() -> {
                    if (resolved != null) {
                        nameToIp.put(host, resolved);
                        ipToName.put(resolved, host);
                        target.loadUrl(replaceHost(u, resolved));
                    } else {
                        target.loadUrl(url); // por si el sistema si sabe resolverlo
                    }
                });
            }, "mdns").start();
            return true;
        }
        if (scheme.equals("about") || scheme.equals("data") || scheme.equals("javascript") || scheme.equals("blob")) return false;
        // tel:, mailto:, intent:, market:, etc. van a otras apps
        try {
            Intent i = scheme.equals("intent") ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            startActivity(i);
        } catch (Exception e) {
            toast("Ninguna app puede abrir este enlace.");
        }
        return true;
    }

    private static String replaceHost(Uri u, String ip) {
        String auth = ip + (u.getPort() > 0 ? ":" + u.getPort() : "");
        return u.buildUpon().encodedAuthority(auth).build().toString();
    }

    /** Muestra la direccion con el nombre .local en lugar de la IP cuando lo sabemos. */
    private String display(String url) {
        if (url == null || url.startsWith("nav:") || url.startsWith("data:") || url.startsWith("about:")) return "";
        Uri u = Uri.parse(url);
        String name = u.getHost() == null ? null : ipToName.get(u.getHost());
        return name == null ? url : u.buildUpon().encodedAuthority(name + (u.getPort() > 0 ? ":" + u.getPort() : "")).build().toString();
    }

    private void showAddress(String url) {
        address.setText(display(url));
        favButton.setText(url != null && isFavorite(display(url)) ? "★" : "☆");
    }

    private WifiManager.MulticastLock multicastLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock l = wm.createMulticastLock("navegador");
            l.acquire();
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> runs = new ArrayList<>();
        labels.add("＋  Nueva pestaña"); runs.add(() -> { newTab(); showHome(); });
        if (tabs.size() > 1) {
            labels.add("Pestañas abiertas (" + tabs.size() + ")"); runs.add(this::tabsDialog);
            labels.add("Cerrar esta pestaña"); runs.add(() -> closeTab(current));
        }
        int active = DownloadCenter.active();
        labels.add("⬇  Descargas" + (active > 0 ? " (" + active + " en curso)" : "")); runs.add(this::showDownloads);
        labels.add("Historial"); runs.add(this::showHistory);
        labels.add("Buscar en la página"); runs.add(this::askFind);
        labels.add(current.desktop ? "Volver a la versión para móvil" : "Ver como computadora (sitio de escritorio)");
        runs.add(this::toggleDesktop);
        labels.add("Compartir enlace"); runs.add(() -> {
            if (isHome()) toast("Abre una pagina primero."); else shareText(display(web.getUrl()));
        });
        labels.add("Abrir en otra app"); runs.add(() -> {
            if (isHome()) { toast("Abre una pagina primero."); return; }
            try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())), "Abrir con")); }
            catch (Exception e) { toast("Ninguna app puede abrirlo."); }
        });
        labels.add("📌  Crear acceso directo a esta página"); runs.add(this::createShortcut);
        labels.add("Texto de las páginas: " + prefs.getInt("zoom", 100) + "%  (cambiar)"); runs.add(this::zoomDialog);
        labels.add(barsHidden ? "Mostrar las barras" : "Pantalla completa (ocultar barras)"); runs.add(this::toggleBars);
        labels.add("Borrar datos de navegación"); runs.add(this::clearDataDialog);
        labels.add("Abrir Archivos"); runs.add(() -> startActivity(new Intent(this, FilesActivity.class)));
        new AlertDialog.Builder(this).setTitle("Menu")
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run()).show();
    }

    private void shareText(String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try { startActivity(Intent.createChooser(i, "Compartir")); } catch (Exception e) { toast("No hay apps para compartir."); }
    }

    private void toggleDesktop() {
        current.desktop = !current.desktop;
        WebSettings s = web.getSettings();
        s.setUserAgentString(current.desktop ? DESKTOP_UA : defaultUa);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        toast(current.desktop ? "Sitio de escritorio activado" : "Sitio para móvil");
        if (!isHome()) web.reload();
    }

    private void toggleBars() {
        barsHidden = !barsHidden;
        topArea.setVisibility(barsHidden ? View.GONE : View.VISIBLE);
        progress.setVisibility(View.GONE);
        if (barsHidden) toast("Barras ocultas. Pulsa Atras o Menu para mostrarlas.");
        rebuildTabs();
    }

    private void zoomDialog() {
        final int[] values = {75, 100, 125, 150, 200};
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) names[i] = values[i] + "%";
        new AlertDialog.Builder(this).setTitle("Tamaño del texto").setItems(names, (d, w) -> {
            prefs.edit().putInt("zoom", values[w]).apply();
            for (Tab t : tabs) t.web.getSettings().setTextZoom(values[w]);
        }).show();
    }

    private void clearDataDialog() {
        new AlertDialog.Builder(this).setTitle("Borrar datos de navegación")
                .setMessage("Se borran cookies, sesiones iniciadas, caché e historial. Los favoritos se conservan.")
                .setPositiveButton("Borrar", (d, w) -> {
                    web.clearCache(true);
                    for (Tab t : tabs) t.web.clearHistory();
                    WebStorage.getInstance().deleteAllData();
                    android.webkit.CookieManager.getInstance().removeAllCookies(null);
                    android.webkit.CookieManager.getInstance().flush();
                    prefs.edit().remove("recientes").remove("historial").apply();
                    toast("Datos borrados");
                    showHome();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // ---------------------------------------------------------------- buscar en la pagina

    private void askFind() {
        if (isHome()) { toast("Abre una pagina para buscar texto en ella."); return; }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(18);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Buscar en la página").setView(wrap)
                .setPositiveButton("Buscar", (d, w) -> find(input.getText().toString()))
                .setNegativeButton("Cancelar", null).create();
        input.setOnEditorActionListener((v, a, e) -> { dlg.dismiss(); find(input.getText().toString()); return true; });
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        input.requestFocus();
    }

    private void find(String text) {
        if (text.trim().isEmpty()) return;
        findBar.setVisibility(View.VISIBLE);
        findInfo.setText("Buscando \"" + text + "\"...");
        web.setFindListener((active, count, done) -> {
            if (!done) return;
            findInfo.setText(count == 0 ? "Sin resultados para \"" + text + "\"" : (active + 1) + " de " + count + "  ·  \"" + text + "\"");
        });
        web.findAllAsync(text);
    }

    private void closeFind() {
        findBar.setVisibility(View.GONE);
        web.clearMatches();
        web.setFindListener(null);
    }

    // ---------------------------------------------------------------- buscar en la red

    private void scan() {
        if (scanning) return;
        scanning = true;
        found.clear();
        refreshNetwork();
        new Thread(() -> {
            WifiManager.MulticastLock lock = multicastLock();
            Scanner.scan(f -> {
                found.put(f.url, f);
                ui.post(this::refreshNetwork);
            });
            if (lock != null) lock.release();
            scanning = false;
            ui.post(this::refreshNetwork);
        }, "buscar").start();
    }

    // ---------------------------------------------------------------- paginas internas (HTML)

    private static String esc(String s) {
        return TextUtils.htmlEncode(s == null ? "" : s);
    }

    private static String q(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static final String CSS = "<style>body{font-family:sans-serif;margin:0;padding:16px 20px;background:#F4F6FA;color:#1b2333}"
            + "h1{font-size:24px;margin:4px 0 2px}h2{font-size:18px;margin:22px 0 8px;color:#3b4660}"
            + "p{margin:4px 0;color:#566078}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:10px}"
            + "a.card{display:block;background:#fff;border-radius:12px;padding:14px 16px;text-decoration:none;color:inherit;border:2px solid #dfe4ee}"
            + "a.card:focus,a.card:hover{border-color:#1E5BD8;outline:3px solid #1E5BD8;background:#EEF3FF}"
            + ".t{font-size:18px;font-weight:bold;word-break:break-word}.u{font-size:14px;color:#566078;word-break:break-all;margin-top:4px}"
            + ".tag{display:inline-block;background:#1E5BD8;color:#fff;border-radius:6px;padding:1px 8px;font-size:13px;margin-left:6px}"
            + "a.small{display:inline-block;margin:4px 8px 0 0;padding:8px 14px;border-radius:8px;background:#E6EAF2;color:#1b2333;text-decoration:none}"
            + "a.small:focus{outline:3px solid #1E5BD8;background:#d4defa}a.main{background:#1E5BD8;color:#fff}a.main:focus{background:#0B3FA8}"
            + ".row{display:flex;gap:8px;align-items:stretch}.row a.card{flex:1}"
            + ".bar{height:12px;background:#dfe4ee;border-radius:6px;overflow:hidden;margin:8px 0 4px}.bar div{height:100%;background:#1E5BD8}"
            + ".ok .bar div{background:#2E9D5B}.bad .bar div{background:#C0392B}.dl{background:#fff;border:2px solid #dfe4ee;border-radius:12px;padding:12px 16px;margin:10px 0}"
            + ".app{display:flex;flex-direction:column;align-items:center;text-align:center}.ic{width:56px;height:56px;border-radius:14px;background:#1E5BD8;color:#fff;font-size:30px;font-weight:bold;line-height:56px;margin-bottom:6px}"
            + "</style>";

    private static String head(String title) {
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(title) + "</title>" + CSS + "</head><body>";
    }

    /** Lista de lo encontrado en la red (se actualiza sola mientras se busca). */
    private String networkHtml() {
        StringBuilder h = new StringBuilder();
        List<Scanner.Found> list;
        synchronized (found) { list = new ArrayList<>(found.values()); }
        Collections.sort(list, (a, b) -> a.transfer == b.transfer ? a.url.compareTo(b.url) : (a.transfer ? -1 : 1));
        if (scanning) h.append("<p>Buscando en la red... (tarda unos segundos)</p>");
        else h.append("<a class='small' href='nav://buscar'>Buscar de nuevo</a>");
        if (list.isEmpty() && !scanning) {
            h.append("<p>No se encontro nada. Revisa que la app (por ejemplo Transfer) este iniciada y que ambos equipos esten en el mismo Wi-Fi.</p>");
        }
        h.append("<div class='grid'>");
        for (Scanner.Found f : list) {
            h.append("<a class='card' href='").append(esc(f.url)).append("'><div class='t'>").append(esc(f.title));
            if (f.transfer) h.append("<span class='tag'>Transfer</span>");
            h.append("</div><div class='u'>").append(esc(f.url)).append("</div></a>");
        }
        h.append("</div>");
        List<String> ips = Scanner.localIps();
        if (!ips.isEmpty()) h.append("<p style='margin-top:8px'>Este equipo: ").append(esc(TextUtils.join(", ", ips))).append("</p>");
        return h.toString();
    }

    /** Actualiza solo la seccion "En tu red" sin recargar la pagina (no pierde la seleccion). */
    private void refreshNetwork() {
        if (current == null || !current.page.equals("inicio")) return;
        web.evaluateJavascript("(function(){var e=document.getElementById('red');if(e)e.innerHTML="
                + org.json.JSONObject.quote(networkHtml()) + ";})()", null);
    }

    private void showHome() {
        StringBuilder h = new StringBuilder(head("Inicio"));
        h.append("<h1>Navegador local</h1>")
         .append("<a class='card' style='background:#1E5BD8;border-color:#0B3FA8;color:#fff;padding:20px 22px;box-shadow:0 4px 14px rgba(30,91,216,.45)' href='nav://escribir'><div class='t' style='font-size:24px'>✎ Escribir direccion</div>")
         .append("<div class='u' style='color:#dbe6ff;font-size:16px'>Cualquier pagina http o https, un nombre .local o una busqueda</div></a>")
         .append("<p style='margin-top:10px'><a class='small' href='nav://descargas'>⬇ Descargas</a>")
         .append("<a class='small' href='nav://historial'>Historial</a></p>");

        List<String[]> apps = load("accesos");
        if (!apps.isEmpty()) {
            h.append("<h2>Mis apps web</h2><div class='grid'>");
            for (String[] f : apps) {
                String name = f[1].isEmpty() ? f[0] : f[1];
                h.append("<div class='row'><a class='card app' href='").append(esc(f[0])).append("'><div class='ic'>")
                 .append(esc(name.substring(0, 1).toUpperCase())).append("</div><div class='t'>").append(esc(name))
                 .append("</div></a><a class='small' style='margin:0;display:flex;align-items:center' href='nav://quitar-acceso?u=")
                 .append(esc(q(f[0]))).append("'>Quitar</a></div>");
            }
            h.append("</div>");
        }

        h.append("<h2>En tu red</h2><div id='red'>").append(networkHtml()).append("</div>");

        List<String[]> favs = load("favoritos");
        if (!favs.isEmpty()) {
            h.append("<h2>Favoritos</h2><div class='grid'>");
            for (String[] f : favs) {
                h.append("<div class='row'><a class='card' href='").append(esc(f[0])).append("'><div class='t'>")
                 .append(esc(f[1].isEmpty() ? f[0] : f[1])).append("</div><div class='u'>").append(esc(f[0]))
                 .append("</div></a><a class='small' style='margin:0;display:flex;align-items:center' href='nav://quitar?u=")
                 .append(esc(q(f[0]))).append("'>Quitar</a></div>");
            }
            h.append("</div>");
        }

        List<String[]> recent = load("recientes");
        if (!recent.isEmpty()) {
            h.append("<h2>Recientes</h2><div class='grid'>");
            for (String[] f : recent) {
                h.append("<a class='card' href='").append(esc(f[0])).append("'><div class='t'>")
                 .append(esc(f[1].isEmpty() ? f[0] : f[1])).append("</div><div class='u'>").append(esc(f[0])).append("</div></a>");
            }
            h.append("</div><a class='small' href='nav://borrar-recientes'>Borrar recientes</a>");
        }
        h.append("<h2>Ayuda</h2><p>Con el control remoto: las flechas mueven la seleccion, OK abre, Atras vuelve. ")
         .append("Los archivos descargados quedan en Descargas/Navegador. Abre el menu (⋮) para pestañas, buscar en la pagina y mas.</p>");
        h.append("</body></html>");
        loadPage("inicio", HOME, h.toString());
    }

    private void loadPage(String page, String base, String html) {
        current.page = page;
        current.title = "";
        web.loadDataWithBaseURL(base, html, "text/html", "utf-8", base);
        address.setText("");
        favButton.setText("☆");
        rebuildTabs();
    }

    // ---- historial

    private void showHistory() {
        if (!isHome()) newTab();
        StringBuilder h = new StringBuilder(head("Historial"));
        h.append("<h1>Historial</h1><p><a class='small' href='nav://inicio'>Inicio</a></p>");
        List<String[]> list = load("historial");
        if (list.isEmpty()) h.append("<p>Todavia no hay paginas visitadas.</p>");
        else {
            h.append("<div class='grid'>");
            for (String[] f : list) {
                String[] parts = f[1].split("\u0001", 2);
                String title = parts.length > 1 ? parts[1] : parts[0];
                h.append("<a class='card' href='").append(esc(f[0])).append("'><div class='t'>")
                 .append(esc(title.isEmpty() ? f[0] : title)).append("</div><div class='u'>").append(esc(f[0])).append("</div></a>");
            }
            h.append("</div><p><a class='small' href='nav://borrar-historial'>Borrar historial</a></p>");
        }
        h.append("</body></html>");
        loadPage("historial", "nav://historial/", h.toString());
    }

    // ---- descargas

    private static String dlStatus(Context c, DownloadCenter.Item it) {
        switch (it.state) {
            case DownloadCenter.RUNNING:
                String size = it.total > 0 ? Formatter.formatShortFileSize(c, it.done) + " de " + Formatter.formatShortFileSize(c, it.total)
                        : Formatter.formatShortFileSize(c, it.done);
                int pct = it.total > 0 ? (int) (it.done * 100 / it.total) : -1;
                return (pct >= 0 ? pct + "%  ·  " : "") + size + (it.speed > 0 ? "  ·  " + Formatter.formatShortFileSize(c, it.speed) + "/s" : "");
            case DownloadCenter.DONE:
                return "Completa  ·  " + Formatter.formatShortFileSize(c, it.total > 0 ? it.total : it.done);
            case DownloadCenter.CANCELLED:
                return "Cancelada";
            default:
                return "Error: " + it.error;
        }
    }

    private static int dlPercent(DownloadCenter.Item it) {
        if (it.state == DownloadCenter.DONE) return 100;
        return it.total > 0 ? (int) Math.min(100, it.done * 100 / it.total) : 0;
    }

    private String downloadsSig() {
        StringBuilder sb = new StringBuilder();
        for (DownloadCenter.Item i : DownloadCenter.items) sb.append(i.id).append(':').append(i.state).append(',');
        return sb.toString();
    }

    private String downloadsListHtml() {
        StringBuilder h = new StringBuilder();
        List<DownloadCenter.Item> items = DownloadCenter.items;
        if (items.isEmpty()) {
            return "<p>No hay descargas. Cuando descargues algo desde una pagina aparecera aqui, con su avance.</p>";
        }
        for (DownloadCenter.Item it : items) {
            String cls = it.state == DownloadCenter.DONE ? "ok" : it.state == DownloadCenter.RUNNING ? "" : "bad";
            h.append("<div class='dl ").append(cls).append("'><div class='t'>").append(esc(it.name)).append("</div>")
             .append("<div class='bar'><div id='p").append(it.id).append("' style='width:").append(dlPercent(it)).append("%'></div></div>")
             .append("<div class='u' id='s").append(it.id).append("'>").append(esc(dlStatus(this, it))).append("</div><p>");
            String id = "nav://dl?id=" + it.id + "&a=";
            if (it.state == DownloadCenter.RUNNING) {
                h.append("<a class='small' href='").append(id).append("cancel'>Cancelar</a>");
            } else {
                if (it.state == DownloadCenter.DONE) {
                    if (it.isApk()) h.append("<a class='small main' href='").append(id).append("install'>Instalar</a>");
                    if (it.isMedia()) h.append("<a class='small main' href='").append(id).append("play'>Reproducir</a>");
                    if (!it.isApk()) h.append("<a class='small' href='").append(id).append("open'>Abrir</a>");
                    h.append("<a class='small' href='").append(id).append("folder'>Ver en Archivos</a>");
                } else {
                    h.append("<a class='small main' href='nav://dl?id=").append(it.id).append("&a=retry'>Reintentar</a>");
                }
                h.append("<a class='small' href='").append(id).append("remove'>Quitar de la lista</a>");
            }
            h.append("</p></div>");
        }
        h.append("<a class='small' href='nav://dl?a=clear'>Quitar las terminadas</a>");
        return h.toString();
    }

    private void showDownloads() {
        if (!isHome() && !current.page.equals("descargas")) newTab();
        StringBuilder h = new StringBuilder(head("Descargas"));
        h.append("<h1>Descargas</h1><p><a class='small' href='nav://inicio'>Inicio</a></p><div id='lista'>")
         .append(downloadsListHtml()).append("</div></body></html>");
        loadPage("descargas", "nav://descargas/", h.toString());
        current.lastDownloadsSig = downloadsSig();
    }

    /** Actualiza el avance en la pagina de descargas sin perder la seleccion. */
    private void refreshDownloads() {
        if (current == null || !current.page.equals("descargas")) return;
        String sig = downloadsSig();
        if (!sig.equals(current.lastDownloadsSig)) {
            current.lastDownloadsSig = sig;
            web.evaluateJavascript("(function(){var e=document.getElementById('lista');if(e)e.innerHTML="
                    + org.json.JSONObject.quote(downloadsListHtml()) + ";})()", null);
            return;
        }
        StringBuilder js = new StringBuilder("(function(){var e;");
        for (DownloadCenter.Item it : DownloadCenter.items) {
            if (it.state != DownloadCenter.RUNNING) continue;
            js.append("e=document.getElementById('p").append(it.id).append("');if(e)e.style.width='").append(dlPercent(it)).append("%';")
              .append("e=document.getElementById('s").append(it.id).append("');if(e)e.textContent=")
              .append(org.json.JSONObject.quote(dlStatus(this, it))).append(";");
        }
        js.append("})()");
        web.evaluateJavascript(js.toString(), null);
    }

    /** Muestra el estado de las descargas en la barra superior. */
    private void updateDownloadsUi() {
        int active = DownloadCenter.active();
        dlButton.setText(active > 0 ? "⬇ " + active : "⬇");
        if (active > 0 && !barsHidden) {
            long done = 0, total = 0, speed = 0;
            boolean unknown = false;
            String first = "";
            for (DownloadCenter.Item it : DownloadCenter.items) {
                if (it.state != DownloadCenter.RUNNING) continue;
                if (first.isEmpty()) first = it.name;
                done += it.done;
                speed += it.speed;
                if (it.total > 0) total += it.total; else unknown = true;
            }
            String pct = total > 0 && !unknown ? (int) (done * 100 / total) + "%  ·  " : "";
            String what = active == 1 ? first : active + " descargas";
            dlBar.setText("⬇  " + what + "  ·  " + pct + Formatter.formatShortFileSize(this, speed) + "/s   (ver)");
            dlBar.setVisibility(View.VISIBLE);
        } else {
            dlBar.setVisibility(View.GONE);
        }
        refreshDownloads();
    }

    private void handleDl(Uri u) {
        String a = u.getQueryParameter("a");
        String idText = u.getQueryParameter("id");
        if ("clear".equals(a)) { DownloadCenter.clearFinished(); return; }
        DownloadCenter.Item it = null;
        try { it = DownloadCenter.find(Integer.parseInt(idText)); } catch (Exception ignored) {}
        if (it == null || a == null) return;
        switch (a) {
            case "cancel": it.cancel = true; break;
            case "remove": DownloadCenter.remove(it); break;
            case "retry":
                DownloadCenter.remove(it);
                startDownload(it.url, defaultUa, null, it.mime);
                break;
            case "install": Installer.install(this, it.openUri()); break;
            case "play": {
                Uri uri = it.file != null && it.file.exists() ? Uri.fromFile(it.file) : it.openUri();
                if (uri == null) { toast("No se encontro el archivo."); break; }
                PlayerActivity.play(this, Collections.singletonList(uri.toString()), Collections.singletonList(it.name), 0);
                break;
            }
            case "open": {
                Uri uri = it.openUri();
                if (uri == null) { toast("No se encontro el archivo."); break; }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, it.file != null ? FileProvider.mimeOf(it.file) : (it.mime.isEmpty() ? "*/*" : it.mime))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (Exception e) {
                    toast("Ninguna app puede abrir este archivo.");
                }
                break;
            }
            case "folder": {
                Intent i = new Intent(this, FilesActivity.class);
                if (it.file != null && it.file.getParentFile() != null) i.putExtra("dir", it.file.getParentFile().getAbsolutePath());
                else i.putExtra("dir", android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/Navegador");
                startActivity(i);
                break;
            }
        }
        updateDownloadsUi();
    }

    private void handleNav(Uri u) {
        String what = u.getHost() == null ? "" : u.getHost();
        switch (what) {
            case "buscar": scan(); break;
            case "escribir": askAddress(); break;
            case "descargas": showDownloads(); break;
            case "historial": showHistory(); break;
            case "dl": handleDl(u); break;
            case "quitar":
                removeEntry("favoritos", u.getQueryParameter("u"));
                showHome();
                break;
            case "quitar-acceso":
                removeEntry("accesos", u.getQueryParameter("u"));
                showHome();
                break;
            case "borrar-recientes":
                prefs.edit().remove("recientes").apply();
                showHome();
                break;
            case "borrar-historial":
                prefs.edit().remove("historial").apply();
                showHistory();
                break;
            default: showHome();
        }
    }

    private void showError(WebView view, String url, String why) {
        Tab t = tabOf(view);
        if (t == null) return;
        String shown = display(url);
        String h = head(ERROR_TITLE)
                + "<h2>No se pudo abrir la pagina</h2><p><b>" + esc(shown) + "</b><br>" + esc(why) + "</p><ul>"
                + "<li>Revisa que la app del otro equipo este iniciada (en Transfer: boton Iniciar).</li>"
                + "<li>Ambos equipos deben estar en el mismo Wi-Fi.</li>"
                + "<li>Revisa el numero de puerto (lo que va despues de los dos puntos, por ejemplo :4747).</li>"
                + "<li>Si usas un nombre .local y no abre, prueba con la IP que muestra la app.</li></ul>"
                + "<a class='small main' href='" + esc(url) + "'>Reintentar</a><a class='small main' href='nav://inicio'>Buscar en mi red</a></body></html>";
        view.loadDataWithBaseURL(url, h, "text/html", "utf-8", url);
    }

    // ---------------------------------------------------------------- favoritos, recientes e historial

    private List<String[]> load(String key) {
        List<String[]> out = new ArrayList<>();
        String raw = prefs.getString(key, "");
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            int t = line.indexOf('\t');
            out.add(t < 0 ? new String[]{line, ""} : new String[]{line.substring(0, t), line.substring(t + 1)});
        }
        return out;
    }

    private void save(String key, List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] e : list) sb.append(e[0]).append('\t').append(e[1].replace('\n', ' ').replace('\t', ' ')).append('\n');
        prefs.edit().putString(key, sb.toString()).apply();
    }

    private void removeEntry(String key, String url) {
        List<String[]> list = load(key);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0].equals(url)) list.remove(i);
        save(key, list);
    }

    private boolean isFavorite(String url) {
        for (String[] f : load("favoritos")) if (f[0].equals(url)) return true;
        return false;
    }

    private void remember(String url, String title) {
        String shown = display(url);
        if (shown.isEmpty()) return;
        String t = title == null ? "" : title;
        List<String[]> list = load("recientes");
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0].equals(shown)) list.remove(i);
        list.add(0, new String[]{shown, t});
        while (list.size() > 12) list.remove(list.size() - 1);
        save("recientes", list);

        List<String[]> hist = load("historial");
        for (int i = hist.size() - 1; i >= 0; i--) if (hist.get(i)[0].equals(shown)) hist.remove(i);
        hist.add(0, new String[]{shown, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()) + "\u0001" + t});
        while (hist.size() > 300) hist.remove(hist.size() - 1);
        save("historial", hist);
    }

    private void toggleFavorite() {
        if (isHome()) { toast("Abre una pagina para guardarla en favoritos."); return; }
        String shown = display(web.getUrl());
        if (isFavorite(shown)) {
            removeEntry("favoritos", shown);
            favButton.setText("☆");
            toast("Quitado de favoritos");
        } else {
            List<String[]> list = load("favoritos");
            list.add(new String[]{shown, web.getTitle() == null ? "" : web.getTitle()});
            save("favoritos", list);
            favButton.setText("★");
            toast("Guardado en favoritos");
        }
    }

    // ---------------------------------------------------------------- accesos directos

    /** Icono del acceso directo: el de la pagina si lo hay, o una letra sobre fondo azul. */
    private Bitmap shortcutIcon(String title) {
        int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#1E5BD8"));
        c.drawRoundRect(new android.graphics.RectF(0, 0, size, size), 40, 40, p);
        Bitmap icon = current.icon;
        if (icon != null) {
            p.setColor(Color.WHITE);
            c.drawRoundRect(new android.graphics.RectF(16, 16, size - 16, size - 16), 28, 28, p);
            c.drawBitmap(Bitmap.createScaledBitmap(icon, size - 56, size - 56, true), 28, 28, null);
        } else {
            p.setColor(Color.WHITE);
            p.setTextSize(110);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setFakeBoldText(true);
            String letter = title.isEmpty() ? "N" : title.substring(0, 1).toUpperCase();
            c.drawText(letter, size / 2f, size / 2f + 38, p);
        }
        return bmp;
    }

    /**
     * Crea un acceso directo a la pagina actual. Siempre abre este navegador (la intencion apunta a
     * esta app, no a "el navegador predeterminado"), y se guarda tambien en "Mis apps web" del inicio.
     */
    @SuppressWarnings("deprecation")
    private void createShortcut() {
        if (isHome()) { toast("Abre una pagina para crear su acceso directo."); return; }
        final String url = display(web.getUrl());
        String t = web.getTitle();
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(t == null || t.isEmpty() ? Uri.parse(url).getHost() : t);
        input.setSelectAllOnFocus(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Crear acceso directo")
                .setMessage("Nombre que tendra. Se abrira siempre con este navegador.")
                .setView(wrap)
                .setPositiveButton("Crear", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Pagina";
                    // Tambien queda en el inicio del navegador (sirve en TV, donde el menu de apps no acepta accesos nuevos).
                    List<String[]> apps = load("accesos");
                    for (int i = apps.size() - 1; i >= 0; i--) if (apps.get(i)[0].equals(url)) apps.remove(i);
                    apps.add(new String[]{url, name});
                    save("accesos", apps);
                    Intent open = new Intent(this, MainActivity.class) // explicita: solo esta app
                            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    Bitmap icon = shortcutIcon(name);
                    try {
                        if (Build.VERSION.SDK_INT >= 26) {
                            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
                            if (sm != null && sm.isRequestPinShortcutSupported()) {
                                android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "web-" + url.hashCode())
                                        .setShortLabel(name).setLongLabel(name)
                                        .setIcon(android.graphics.drawable.Icon.createWithBitmap(icon))
                                        .setIntent(open).build();
                                sm.requestPinShortcut(info, null);
                                toast("Confirma el acceso directo en la ventana que aparece. Tambien esta en Inicio > Mis apps web.");
                                return;
                            }
                        }
                        Intent add = new Intent("com.android.launcher.action.INSTALL_SHORTCUT")
                                .putExtra(Intent.EXTRA_SHORTCUT_INTENT, open)
                                .putExtra(Intent.EXTRA_SHORTCUT_NAME, name)
                                .putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
                        sendBroadcast(add);
                        toast("Acceso directo enviado. Tambien esta en Inicio > Mis apps web.");
                    } catch (Exception e) {
                        toast("Este equipo no permite accesos directos en el menu de apps. Lo guarde en Inicio > Mis apps web.");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------------------------------------------------------------- subir archivos

    private boolean chooseFiles(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        boolean multiple = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        Intent pick;
        try {
            pick = params.createIntent();
        } catch (Exception e) {
            pick = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        }
        if (pick.getType() == null || pick.getType().isEmpty()) pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        Intent[] tries = {
                pick,
                new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple),
                new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple),
        };
        for (Intent i : tries) {
            try {
                startActivityForResult(Intent.createChooser(i, "Elegir archivo"), PICK_FILES);
                return true;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        fileCallback = null;
        new AlertDialog.Builder(this)
                .setTitle("No hay explorador de archivos")
                .setMessage("Este equipo no tiene una app para elegir archivos. Instala un explorador "
                        + "(por ejemplo \"Files\" de Google o \"X-plore\") desde la tienda y vuelve a intentarlo.")
                .setPositiveButton("Entendido", null)
                .show();
        return false;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || fileCallback == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < uris.length; i++) uris[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }

    // ---------------------------------------------------------------- descargas (inicio)

    private void download(final String url, final String ua, final String disposition, final String mime) {
        boolean media = (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")
                || mime.contains("mpegurl") || mime.equals("application/dash+xml")))
                || (url.startsWith("http") && PlayerActivity.isStreamUrl(url));
        if (media) {
            new AlertDialog.Builder(this)
                    .setTitle("Video o audio")
                    .setItems(new String[]{"Reproducir", "Descargar"}, (d, w) -> {
                        if (w == 0) PlayerActivity.play(this, Collections.singletonList(url),
                                Collections.singletonList(Downloader.fileName(url, disposition, mime)), 0);
                        else startDownload(url, ua, disposition, mime);
                    }).show();
            return;
        }
        startDownload(url, ua, disposition, mime);
    }

    private void startDownload(String url, String ua, String disposition, String mime) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, ua, disposition, mime};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("pidio-notif", false)) {
            prefs.edit().putBoolean("pidio-notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        Downloader.start(this, url, ua, disposition, mime);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        if (request == ASK_STORAGE && pendingDownload != null) {
            String[] p = pendingDownload;
            pendingDownload = null;
            Downloader.start(this, p[0], p[1], p[2], p[3]);
        }
    }

    // ---------------------------------------------------------------- pantalla completa (videos)

    @SuppressWarnings("deprecation")
    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) { cb.onCustomViewHidden(); return; }
        customView = view;
        customCallback = cb;
        fullscreen.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreen.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @SuppressWarnings("deprecation")
    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        customView = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.setSystemUiVisibility(0);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    // ---------------------------------------------------------------- teclas

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (barsHidden) toggleBars();
            showMenu();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            askAddress();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
        if (barsHidden) { toggleBars(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        if (tabs.size() > 1) { closeTab(current); return; }
        super.onBackPressed();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DownloadCenter.removeListener(downloadListener);
        for (Tab t : tabs) t.web.onPause();
        android.webkit.CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Tab t : tabs) t.web.onResume();
        DownloadCenter.addListener(downloadListener);
        updateDownloadsUi();
        Installer.resume(this);
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) t.web.destroy();
        super.onDestroy();
    }
}
