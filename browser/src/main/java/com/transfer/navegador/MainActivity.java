package com.transfer.navegador;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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
import android.text.TextUtils;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Navegador sencillo pensado para apps de la red local (como Transfer):
 * entiende nombres .local, encuentra servidores en el Wi-Fi, sube y descarga
 * archivos y se maneja con el control remoto de la TV.
 */
public class MainActivity extends Activity {
    private static final String HOME = "nav://inicio/";
    private static final String ERROR_TITLE = "No se pudo abrir";
    private static final int PICK_FILES = 1, ASK_STORAGE = 2;

    private WebView web;
    private EditText address;
    private Button favButton;
    private ProgressBar progress;
    private FrameLayout root, fullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Nombres .local ya resueltos: nombre -> IP, e IP:puerto -> nombre para mostrarlo. */
    private final Map<String, String> nameToIp = new HashMap<>();
    private final Map<String, String> ipToName = new HashMap<>();

    private final Map<String, Scanner.Found> found = Collections.synchronizedMap(new LinkedHashMap<String, Scanner.Found>());
    private volatile boolean scanning;
    private String[] pendingDownload;
    private Bitmap pageIcon;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("navegador", MODE_PRIVATE);
        buildUi();
        configureWeb();
        scan();
        Intent i = getIntent();
        if (i != null && i.getData() != null) go(i.getDataString());
        else showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) go(intent.getDataString());
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

    private void buildUi() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackgroundColor(Color.parseColor("#F4F6FA"));

        bar.addView(button("‹", v -> { if (web.canGoBack()) web.goBack(); }));
        bar.addView(button("›", v -> { if (web.canGoForward()) web.goForward(); }));
        bar.addView(button("↻", v -> {
            if (isHome()) scan(); else web.reload();
        }));
        bar.addView(button("Inicio", v -> showHome()));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Direccion, por ejemplo transfer.local:4747");
        address.setTextSize(17);
        address.setTextColor(Color.BLACK);
        address.setSelectAllOnFocus(true);
        address.setBackgroundResource(R.drawable.url_bg);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
        address.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submitAddress();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(44), 1);
        alp.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(address, alp);

        bar.addView(button("Ir", v -> submitAddress()));
        favButton = button("☆", v -> toggleFavorite());
        bar.addView(favButton);
        bar.addView(button("\uD83D\uDCCC", v -> createShortcut()));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);

        web = new WebView(this);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);

        root = new FrameLayout(this);
        root.addView(col);
        root.addView(fullscreen);
        setContentView(root);
    }

    // ---------------------------------------------------------------- WebView

    @SuppressWarnings({"SetJavaScriptEnabled", "deprecation"})
    private void configureWeb() {
        WebSettings s = web.getSettings();
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
        s.setSupportMultipleWindows(false); // los enlaces "nueva ventana" se abren aqui mismo
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUserAgentString(s.getUserAgentString() + " NavegadorLocal/1.0");
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageIcon = favicon;
                showAddress(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                showAddress(url);
                if (url != null && url.startsWith("http")) {
                    if (!ERROR_TITLE.equals(view.getTitle())) remember(url, view.getTitle());
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
                showError(failingUrl, description);
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Certificado no valido")
                        .setMessage("La conexion segura de " + Uri.parse(error.getUrl()).getHost()
                                + " no tiene un certificado valido. Es normal en apps de tu red local, "
                                + "pero no continues si es un sitio de internet como un banco.")
                        .setPositiveButton("Continuar", (d, w) -> handler.proceed())
                        .setNegativeButton("Cancelar", (d, w) -> handler.cancel())
                        .setOnCancelListener(d -> handler.cancel())
                        .show();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                pageIcon = icon;
            }

            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
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

        web.setDownloadListener((url, userAgent, disposition, mime, length) -> download(url, userAgent, disposition, mime));
    }

    // ---------------------------------------------------------------- navegacion

    private boolean isHome() {
        String u = web.getUrl();
        return u == null || u.startsWith("nav:") || u.startsWith("data:") || u.startsWith("about:");
    }

    private void submitAddress() {
        String text = address.getText().toString().trim();
        if (text.isEmpty()) return;
        hideKeyboard();
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
            String ip = nameToIp.get(host);
            if (ip != null) { web.loadUrl(replaceHost(u, ip)); return true; }
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
                        web.loadUrl(replaceHost(u, resolved));
                    } else {
                        web.loadUrl(url); // por si el sistema si sabe resolverlo
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
        if (!address.hasFocus()) address.setText(display(url));
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

    // ---------------------------------------------------------------- pagina de inicio

    private static String esc(String s) {
        return TextUtils.htmlEncode(s == null ? "" : s);
    }

    private static String q(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
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
        if (!isHome()) return;
        web.evaluateJavascript("(function(){var e=document.getElementById('red');if(e)e.innerHTML="
                + org.json.JSONObject.quote(networkHtml()) + ";})()", null);
    }

    private void showHome() {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
         .append("<style>body{font-family:sans-serif;margin:0;padding:16px 20px;background:#F4F6FA;color:#1b2333}")
         .append("h1{font-size:24px;margin:4px 0 2px}h2{font-size:18px;margin:22px 0 8px;color:#3b4660}")
         .append("p{margin:4px 0;color:#566078}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:10px}")
         .append("a.card{display:block;background:#fff;border-radius:12px;padding:14px 16px;text-decoration:none;color:inherit;border:2px solid #dfe4ee}")
         .append("a.card:focus,a.card:hover{border-color:#1E5BD8;outline:3px solid #1E5BD8;background:#EEF3FF}")
         .append(".t{font-size:18px;font-weight:bold}.u{font-size:14px;color:#566078;word-break:break-all;margin-top:4px}")
         .append(".tag{display:inline-block;background:#1E5BD8;color:#fff;border-radius:6px;padding:1px 8px;font-size:13px;margin-left:6px}")
         .append("a.small{display:inline-block;margin:4px 8px 0 0;padding:8px 14px;border-radius:8px;background:#E6EAF2;color:#1b2333;text-decoration:none}")
         .append("a.small:focus{outline:3px solid #1E5BD8;background:#d4defa}.row{display:flex;gap:8px;align-items:stretch}.row a.card{flex:1}")
         .append("</style></head><body>");
        h.append("<h1>Navegador local</h1><p>Abre las apps de tu Wi-Fi, como Transfer. Escribe arriba la direccion (por ejemplo <b>transfer.local:4747</b>) o elige una de aqui.</p>");

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
         .append("Sube con las flechas hasta la barra para escribir una direccion. Los archivos descargados quedan en Descargas/Navegador.</p>");
        h.append("</body></html>");

        web.loadDataWithBaseURL(HOME, h.toString(), "text/html", "utf-8", HOME);
        address.setText("");
        favButton.setText("☆");
    }

    private void handleNav(Uri u) {
        String what = u.getHost() == null ? "" : u.getHost();
        switch (what) {
            case "buscar": scan(); break;
            case "quitar":
                removeEntry("favoritos", u.getQueryParameter("u"));
                showHome();
                break;
            case "borrar-recientes":
                prefs.edit().remove("recientes").apply();
                showHome();
                break;
            default: showHome();
        }
    }

    private void showError(String url, String why) {
        String shown = display(url);
        String h = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + ERROR_TITLE + "</title><style>body{font-family:sans-serif;padding:20px;background:#F4F6FA;color:#1b2333}"
                + "a{display:inline-block;margin:8px 8px 0 0;padding:10px 16px;border-radius:8px;background:#1E5BD8;color:#fff;text-decoration:none}"
                + "a:focus{outline:3px solid #0b2a6b}li{margin:6px 0}</style></head><body>"
                + "<h2>No se pudo abrir la pagina</h2><p><b>" + esc(shown) + "</b><br>" + esc(why) + "</p><ul>"
                + "<li>Revisa que la app del otro equipo este iniciada (en Transfer: boton Iniciar).</li>"
                + "<li>Ambos equipos deben estar en el mismo Wi-Fi.</li>"
                + "<li>Revisa el numero de puerto (lo que va despues de los dos puntos, por ejemplo :4747).</li>"
                + "<li>Si usas un nombre .local y no abre, prueba con la IP que muestra la app.</li></ul>"
                + "<a href='" + esc(url) + "'>Reintentar</a><a href='nav://inicio'>Buscar en mi red</a></body></html>";
        web.loadDataWithBaseURL(url, h, "text/html", "utf-8", url);
    }

    // ---------------------------------------------------------------- favoritos y recientes

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
        List<String[]> list = load("recientes");
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0].equals(shown)) list.remove(i);
        list.add(0, new String[]{shown, title == null ? "" : title});
        while (list.size() > 12) list.remove(list.size() - 1);
        save("recientes", list);
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
        if (pageIcon != null) {
            p.setColor(Color.WHITE);
            c.drawRoundRect(new android.graphics.RectF(16, 16, size - 16, size - 16), 28, 28, p);
            c.drawBitmap(Bitmap.createScaledBitmap(pageIcon, size - 56, size - 56, true), 28, 28, null);
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

    /** Crea un acceso directo a la pagina actual en la pantalla de inicio del equipo. */
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
                .setMessage("Nombre que tendra en el menu de apps:")
                .setView(wrap)
                .setPositiveButton("Crear", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Pagina";
                    Intent open = new Intent(this, MainActivity.class)
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
                                toast("Confirma el acceso directo en la ventana que aparece.");
                                return;
                            }
                        }
                        Intent add = new Intent("com.android.launcher.action.INSTALL_SHORTCUT")
                                .putExtra(Intent.EXTRA_SHORTCUT_INTENT, open)
                                .putExtra(Intent.EXTRA_SHORTCUT_NAME, name)
                                .putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
                        sendBroadcast(add);
                        toast("Acceso directo enviado a la pantalla de inicio.");
                    } catch (Exception e) {
                        toast("Este equipo no permite crear accesos directos. Usa favoritos (la estrella).");
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

    // ---------------------------------------------------------------- descargas

    private void download(String url, String ua, String disposition, String mime) {
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
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SEARCH) {
            address.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (address.hasFocus()) { web.requestFocus(); showAddress(web.getUrl()); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        android.webkit.CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
