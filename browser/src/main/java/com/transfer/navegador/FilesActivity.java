package com.transfer.navegador;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explorador de archivos sencillo: navegar, abrir, copiar, cortar, pegar, borrar,
 * renombrar, crear carpetas, compartir e instalar apps (APK). Funciona con el control remoto.
 */
public class FilesActivity extends Activity {
    private static final int ASK_STORAGE = 1;

    /** null = pantalla "Inicio" con los almacenamientos y carpetas frecuentes. */
    private File dir;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<File> selected = new LinkedHashSet<>();
    private final List<File> clipboard = new ArrayList<>();
    private boolean clipboardCut;

    private SharedPreferences prefs;
    private ListView list;
    private TextView path, empty;
    private LinearLayout selectionBar;
    private TextView selectionText;
    private Button pasteButton;
    private Adapter adapter;

    /** Una fila: un archivo/carpeta, o un acceso de la pantalla Inicio (con nombre propio). */
    private static final class Entry {
        final File file;
        final String label;
        Entry(File file, String label) { this.file = file; this.label = label; }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("archivos", MODE_PRIVATE);
        buildUi();
        String last = saved != null ? saved.getString("dir") : null;
        dir = last != null ? new File(last) : null;
        String start = getIntent().getStringExtra("dir");
        if (dir == null && start != null && new File(start).isDirectory()) dir = new File(start);
        askPermissionIfNeeded();
        load(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (dir != null) out.putString("dir", dir.getAbsolutePath());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(null);
        Installer.resume(this);
    }

    // ---------------------------------------------------------------- interfaz

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, Color.parseColor("#1b2333")}));
        b.setBackgroundResource(R.drawable.btn_bg);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("↑ Subir", v -> goUp()));
        bar.addView(button("Inicio", v -> open(null)));
        bar.addView(button("+ Carpeta", v -> newFolder()));
        pasteButton = button("Pegar", v -> paste());
        pasteButton.setVisibility(View.GONE);
        bar.addView(pasteButton);
        bar.addView(button("Seleccionar todo", v -> selectAll()));
        bar.addView(button("\u25B6 Enlace", v -> openLink()));
        bar.addView(button("Opciones", v -> options()));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(dp(6), dp(6), dp(6), dp(2));
        scroll.addView(bar);

        path = new TextView(this);
        path.setTextSize(14);
        path.setTextColor(Color.parseColor("#566078"));
        path.setPadding(dp(12), dp(4), dp(12), dp(6));
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.START);

        selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setBackgroundColor(Color.parseColor("#DCE6FB"));
        selectionBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        selectionText = new TextView(this);
        selectionText.setTextSize(16);
        selectionText.setTextColor(Color.parseColor("#1b2333"));
        selectionText.setPadding(dp(6), 0, dp(10), 0);
        selectionBar.addView(selectionText);
        selectionBar.addView(button("Copiar", v -> toClipboard(new ArrayList<>(selected), false)));
        selectionBar.addView(button("Cortar", v -> toClipboard(new ArrayList<>(selected), true)));
        selectionBar.addView(button("Borrar", v -> confirmDelete(new ArrayList<>(selected))));
        selectionBar.addView(button("Compartir", v -> share(new ArrayList<>(selected))));
        selectionBar.addView(button("Cancelar", v -> { selected.clear(); refresh(); }));
        HorizontalScrollView selScroll = new HorizontalScrollView(this);
        selScroll.setHorizontalScrollBarEnabled(false);
        selScroll.addView(selectionBar);
        selScroll.setVisibility(View.GONE);
        selectionBar.setTag(selScroll);

        list = new ListView(this);
        list.setSelector(R.drawable.row_bg);
        list.setDrawSelectorOnTop(false);
        list.setDividerHeight(1);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> clicked(entries.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { actions(entries.get(pos)); return true; });

        empty = new TextView(this);
        empty.setTextSize(16);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.parseColor("#566078"));
        empty.setPadding(dp(20), dp(40), dp(20), dp(20));
        empty.setText("Carpeta vacía");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.parseColor("#F4F6FA"));
        col.addView(scroll);
        col.addView(path);
        col.addView(selScroll);
        col.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView hint = new TextView(this);
        hint.setText("Mantén presionado un archivo (o el botón OK del control) para ver más opciones.");
        hint.setTextSize(13);
        hint.setTextColor(Color.parseColor("#7a849a"));
        hint.setPadding(dp(12), dp(4), dp(12), dp(6));
        col.addView(hint);
        setContentView(col);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView icon, name, info;
            if (convert == null) {
                row = new LinearLayout(FilesActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                icon = new TextView(FilesActivity.this);
                icon.setTextSize(26);
                icon.setGravity(Gravity.CENTER);
                row.addView(icon, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout texts = new LinearLayout(FilesActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(10), 0, 0, 0);
                name = new TextView(FilesActivity.this);
                name.setTextSize(17);
                name.setTextColor(Color.parseColor("#1b2333"));
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                info = new TextView(FilesActivity.this);
                info.setTextSize(13);
                info.setTextColor(Color.parseColor("#6b7590"));
                texts.addView(name);
                texts.addView(info);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(new TextView[]{icon, name, info});
            } else {
                row = (LinearLayout) convert;
            }
            TextView[] t = (TextView[]) row.getTag();
            icon = t[0]; name = t[1]; info = t[2];
            Entry e = entries.get(pos);
            File f = e.file;
            boolean sel = selected.contains(f);
            icon.setText(sel ? "✅" : iconFor(f));
            name.setText(e.label != null ? e.label : f.getName());
            name.setTypeface(f.isDirectory() ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (e.label != null) {
                info.setText(f.getAbsolutePath());
            } else if (f.isDirectory()) {
                String[] kids = f.list();
                info.setText((kids == null ? "" : kids.length + (kids.length == 1 ? " elemento · " : " elementos · ")) + date(f));
            } else {
                info.setText(Formatter.formatShortFileSize(FilesActivity.this, f.length()) + " · " + date(f));
            }
            row.setBackgroundColor(sel ? Color.parseColor("#E3EBFF") : Color.TRANSPARENT);
            return row;
        }
    }

    private String date(File f) {
        return DateFormat.format("dd/MM/yyyy HH:mm", f.lastModified()).toString();
    }

    private static String ext(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String iconFor(File f) {
        if (f.isDirectory()) return "📁";
        String e = ext(f);
        if (e.equals("apk")) return "📦";
        String m = FileProvider.mimeOf(f);
        if (m.startsWith("image/")) return "🖼";
        if (m.startsWith("video/")) return "🎬";
        if (m.startsWith("audio/")) return "🎵";
        if (Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(e)) return "🗜";
        if (Arrays.asList("html", "htm").contains(e)) return "🌐";
        return "📄";
    }

    // ---------------------------------------------------------------- cargar carpetas

    private void open(File d) {
        selected.clear();
        dir = d;
        load(null);
        list.setSelection(0);
    }

    /** Vuelve a leer la carpeta actual. focus = archivo que queda seleccionado. */
    private void load(File focus) {
        entries.clear();
        if (dir == null) {
            path.setText("Inicio");
            for (Entry e : roots()) entries.add(e);
        } else {
            path.setText(dir.getAbsolutePath());
            File[] kids = dir.listFiles();
            if (kids != null) {
                boolean hidden = prefs.getBoolean("ocultos", false);
                List<File> files = new ArrayList<>();
                for (File k : kids) if (hidden || !k.getName().startsWith(".")) files.add(k);
                Collections.sort(files, comparator());
                for (File k : files) entries.add(new Entry(k, null));
            }
            empty.setText(kids == null ? "No se puede leer esta carpeta. Revisa el permiso de archivos en Opciones." : "Carpeta vacía");
        }
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        refresh();
        if (focus != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).file.equals(focus)) { list.setSelection(i); break; }
            }
        }
    }

    private void refresh() {
        // quita de la seleccion lo que ya no existe
        for (File f : new ArrayList<>(selected)) if (!f.exists()) selected.remove(f);
        adapter.notifyDataSetChanged();
        View selScroll = (View) selectionBar.getTag();
        selScroll.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        selectionText.setText(selected.size() + (selected.size() == 1 ? " seleccionado" : " seleccionados"));
        pasteButton.setVisibility(clipboard.isEmpty() || dir == null ? View.GONE : View.VISIBLE);
        pasteButton.setText("Pegar " + clipboard.size() + (clipboardCut ? " (mover)" : ""));
    }

    private Comparator<File> comparator() {
        final String sort = prefs.getString("orden", "nombre");
        return (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            if (sort.equals("fecha")) return Long.compare(b.lastModified(), a.lastModified());
            if (sort.equals("tamano") && !a.isDirectory()) return Long.compare(b.length(), a.length());
            return a.getName().compareToIgnoreCase(b.getName());
        };
    }

    /** Almacenamientos (interno, SD, USB) y carpetas frecuentes. */
    private List<Entry> roots() {
        List<Entry> out = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        out.add(new Entry(internal, "Almacenamiento interno"));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(internal.getAbsolutePath());
        File[] appDirs = getExternalFilesDirs(null);
        if (appDirs != null) {
            for (File a : appDirs) {
                if (a == null) continue;
                String p = a.getAbsolutePath();
                int i = p.indexOf("/Android/data/");
                if (i > 0) p = p.substring(0, i);
                if (seen.add(p)) out.add(new Entry(new File(p), "Tarjeta SD / USB"));
            }
        }
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self") || !v.isDirectory() || !v.canRead()) continue;
                if (seen.add(v.getAbsolutePath())) out.add(new Entry(v, "Tarjeta SD / USB (" + n + ")"));
            }
        }
        String[][] common = {
                {Environment.DIRECTORY_DOWNLOADS, "Descargas"},
                {Environment.DIRECTORY_DOWNLOADS + "/Transfer", "Descargas / Transfer"},
                {Environment.DIRECTORY_DOWNLOADS + "/Navegador", "Descargas / Navegador"},
                {Environment.DIRECTORY_DCIM, "Fotos de la cámara (DCIM)"},
                {Environment.DIRECTORY_PICTURES, "Imágenes"},
                {Environment.DIRECTORY_MOVIES, "Videos"},
                {Environment.DIRECTORY_MUSIC, "Música"},
                {Environment.DIRECTORY_DOCUMENTS, "Documentos"},
        };
        for (String[] c : common) {
            File f = new File(internal, c[0]);
            if (f.isDirectory()) out.add(new Entry(f, c[1]));
        }
        return out;
    }

    private void goUp() {
        if (dir == null) return;
        File parent = dir.getParentFile();
        File from = dir;
        selected.clear();
        if (parent == null || !parent.canRead() || parent.list() == null) dir = null;
        else dir = parent;
        load(from);
    }

    // ---------------------------------------------------------------- acciones

    private void clicked(Entry e) {
        File f = e.file;
        if (!selected.isEmpty()) {
            if (e.label == null) { toggle(f); return; }
        }
        if (f.isDirectory()) open(f);
        else openFile(f);
    }

    private void toggle(File f) {
        if (!selected.remove(f)) selected.add(f);
        refresh();
    }

    private void selectAll() {
        if (dir == null) return;
        for (Entry e : entries) selected.add(e.file);
        refresh();
    }

    private void actions(final Entry e) {
        final File f = e.file;
        if (e.label != null) { open(f); return; }
        final List<String> labels = new ArrayList<>();
        final List<Runnable> runs = new ArrayList<>();
        if (f.isDirectory()) { labels.add("Abrir"); runs.add(() -> open(f)); }
        else if (ext(f).equals("apk")) { labels.add("Instalar app"); runs.add(() -> install(f)); }
        else if (isMedia(f)) { labels.add("Reproducir"); runs.add(() -> play(f)); }
        else { labels.add("Abrir"); runs.add(() -> openFile(f)); }
        if (f.isFile()) { labels.add("Abrir con..."); runs.add(() -> openWith(f)); }
        labels.add(selected.contains(f) ? "Quitar de la selección" : "Seleccionar (para varios)");
        runs.add(() -> toggle(f));
        labels.add("Copiar"); runs.add(() -> toClipboard(Collections.singletonList(f), false));
        labels.add("Cortar (mover)"); runs.add(() -> toClipboard(Collections.singletonList(f), true));
        labels.add("Renombrar"); runs.add(() -> rename(f));
        labels.add("Borrar"); runs.add(() -> confirmDelete(Collections.singletonList(f)));
        if (f.isFile()) { labels.add("Compartir"); runs.add(() -> share(Collections.singletonList(f))); }
        labels.add("Detalles"); runs.add(() -> details(f));
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run())
                .show();
    }

    private void options() {
        final boolean hidden = prefs.getBoolean("ocultos", false);
        String[] items = {
                hidden ? "Ocultar archivos ocultos" : "Mostrar archivos ocultos",
                "Ordenar por nombre",
                "Ordenar por fecha (recientes primero)",
                "Ordenar por tamaño",
                "Permiso para ver todos los archivos",
                "Reproducir un enlace (mp4, m3u8, mpd...)",
        };
        new AlertDialog.Builder(this).setTitle("Opciones").setItems(items, (d, w) -> {
            switch (w) {
                case 0: prefs.edit().putBoolean("ocultos", !hidden).apply(); break;
                case 1: prefs.edit().putString("orden", "nombre").apply(); break;
                case 2: prefs.edit().putString("orden", "fecha").apply(); break;
                case 3: prefs.edit().putString("orden", "tamano").apply(); break;
                case 4: openPermissionSettings(); return;
                case 5: openLink(); return;
            }
            load(null);
        }).show();
    }

    private Uri uri(File f) {
        if (Build.VERSION.SDK_INT < 24) return Uri.fromFile(f);
        return FileProvider.uriFor(f);
    }

    private static boolean isMedia(File f) {
        if (!f.isFile()) return false;
        String e = ext(f);
        if (Arrays.asList("m3u8", "mpd", "m3u", "ts", "mts", "m2ts", "vob", "mkv", "flac", "opus", "ogg", "oga").contains(e)) return true;
        String m = FileProvider.mimeOf(f);
        return m.startsWith("video/") || m.startsWith("audio/");
    }

    /** Reproduce el archivo con el reproductor propio, junto con los demas de la carpeta. */
    private void play(File f) {
        List<String> uris = new ArrayList<>(), titles = new ArrayList<>();
        int start = 0;
        File parent = f.getParentFile();
        File[] kids = parent == null ? null : parent.listFiles();
        if (kids != null) {
            List<File> media = new ArrayList<>();
            for (File k : kids) if (isMedia(k) && !ext(k).equals("m3u8") || k.equals(f)) media.add(k);
            Collections.sort(media, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File k : media) {
                if (k.equals(f)) start = uris.size();
                uris.add(Uri.fromFile(k).toString());
                titles.add(k.getName());
            }
        }
        if (uris.isEmpty()) { uris.add(Uri.fromFile(f).toString()); titles.add(f.getName()); }
        PlayerActivity.play(this, uris, titles, start);
    }

    private void openLink() {
        askName("Abrir enlace de video o audio", "https://", n -> {
            if (!n.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) n = "http://" + n;
            PlayerActivity.play(this, Collections.singletonList(n), Collections.singletonList(Uri.parse(n).getLastPathSegment()), 0);
        });
    }

    private void openFile(File f) {
        if (ext(f).equals("apk")) { install(f); return; }
        if (isMedia(f)) { play(f); return; }
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), FileProvider.mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            openWith(f);
        }
    }

    private void openWith(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Abrir con"));
        } catch (ActivityNotFoundException e) {
            toast("No hay ninguna app para abrir este archivo.");
        }
    }

    private void share(List<File> files) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) if (f.isFile()) uris.add(uri(f));
        if (uris.isEmpty()) { toast("Solo se pueden compartir archivos, no carpetas."); return; }
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0)).setType(FileProvider.mimeOf(files.get(0)));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*");
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Compartir"));
        } catch (ActivityNotFoundException e) {
            toast("No hay ninguna app para compartir.");
        }
    }

    private void details(File f) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ubicación: ").append(f.getParent()).append("\n");
        sb.append("Modificado: ").append(date(f)).append("\n");
        if (f.isFile()) {
            sb.append("Tamaño: ").append(Formatter.formatFileSize(this, f.length())).append("\n");
            sb.append("Tipo: ").append(FileProvider.mimeOf(f)).append("\n");
        } else {
            String[] k = f.list();
            sb.append("Contiene: ").append(k == null ? "?" : k.length).append(" elementos\n");
        }
        if (ext(f).equals("apk")) {
            PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi != null) {
                pi.applicationInfo.sourceDir = f.getAbsolutePath();
                pi.applicationInfo.publicSourceDir = f.getAbsolutePath();
                sb.append("\nApp: ").append(pi.applicationInfo.loadLabel(getPackageManager()))
                  .append("\nVersión: ").append(pi.versionName)
                  .append("\nPaquete: ").append(pi.packageName);
            }
        }
        new AlertDialog.Builder(this).setTitle(f.getName()).setMessage(sb.toString())
                .setPositiveButton("Cerrar", null).show();
    }

    // ---------------------------------------------------------------- instalar apps

    private void install(File apk) {
        Installer.install(this, uri(apk));
    }

    // ---------------------------------------------------------------- copiar, mover, borrar

    private void toClipboard(List<File> files, boolean cut) {
        if (files.isEmpty()) return;
        clipboard.clear();
        clipboard.addAll(files);
        clipboardCut = cut;
        selected.clear();
        refresh();
        toast((cut ? "Para mover: " : "Copiado: ") + files.size()
                + (files.size() == 1 ? " elemento" : " elementos") + ". Ve a otra carpeta y pulsa Pegar.");
    }

    private interface Job { void run(Progress p) throws Exception; }

    private interface Progress { void show(String text); }

    /** Ejecuta un trabajo largo en segundo plano con una ventana de progreso. */
    private void runJob(String title, final Job job, final String doneMsg) {
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title).setMessage("Empezando...")
                .setCancelable(false).show();
        new Thread(() -> {
            String err = null;
            try {
                job.run(text -> runOnUiThread(() -> dlg.setMessage(text)));
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String error = err;
            runOnUiThread(() -> {
                try { dlg.dismiss(); } catch (Exception ignored) {}
                load(null);
                if (error != null) {
                    new AlertDialog.Builder(this).setTitle("Hubo un problema").setMessage(error)
                            .setPositiveButton("Cerrar", null).show();
                } else if (doneMsg != null) {
                    toast(doneMsg);
                }
            });
        }, "archivos").start();
    }

    private void paste() {
        if (dir == null || clipboard.isEmpty()) return;
        final File target = dir;
        final List<File> sources = new ArrayList<>(clipboard);
        final boolean move = clipboardCut;
        for (File s : sources) {
            String sp = s.getAbsolutePath() + "/";
            if (s.isDirectory() && (target.getAbsolutePath() + "/").startsWith(sp)) {
                toast("No se puede pegar una carpeta dentro de sí misma.");
                return;
            }
        }
        if (move) clipboard.clear();
        runJob(move ? "Moviendo" : "Copiando", p -> {
            List<String> scan = new ArrayList<>();
            for (File s : sources) {
                if (!s.exists()) continue;
                if (move && s.getParentFile() != null && s.getParentFile().equals(target)) continue;
                File dest = unique(target, s.getName());
                if (move && s.renameTo(dest)) { scan.add(dest.getAbsolutePath()); continue; }
                copy(s, dest, p);
                scan.add(dest.getAbsolutePath());
                if (move) deleteTree(s, p);
            }
            MediaScannerConnection.scanFile(this, scan.toArray(new String[0]), null, null);
        }, move ? "Movido" : "Copiado");
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private void copy(File src, File dest, Progress p) throws Exception {
        if (src.isDirectory()) {
            if (!dest.mkdirs() && !dest.isDirectory()) throw new Exception("No se pudo crear la carpeta " + dest.getName());
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copy(k, new File(dest, k.getName()), p);
            return;
        }
        p.show(src.getName());
        long total = src.length(), done = 0, last = 0;
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (now - last > 400 && total > 0) {
                    last = now;
                    p.show(src.getName() + "\n" + (done * 100 / total) + "%  ("
                            + Formatter.formatShortFileSize(this, done) + " de " + Formatter.formatShortFileSize(this, total) + ")");
                }
            }
        } catch (Exception e) {
            dest.delete();
            throw new Exception("No se pudo copiar " + src.getName() + ": " + e.getMessage());
        }
        dest.setLastModified(src.lastModified());
    }

    private void deleteTree(File f, Progress p) throws Exception {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k, p);
        }
        p.show("Borrando " + f.getName());
        if (!f.delete() && f.exists()) throw new Exception("No se pudo borrar " + f.getName());
    }

    private void confirmDelete(final List<File> files) {
        if (files.isEmpty()) return;
        String what = files.size() == 1 ? "\"" + files.get(0).getName() + "\"" : files.size() + " elementos";
        new AlertDialog.Builder(this)
                .setTitle("¿Borrar " + what + "?")
                .setMessage("Se borrará para siempre (no va a una papelera).")
                .setPositiveButton("Borrar", (d, w) -> {
                    selected.removeAll(files);
                    clipboard.removeAll(files);
                    runJob("Borrando", p -> {
                        List<String> scan = new ArrayList<>();
                        for (File f : files) { deleteTree(f, p); scan.add(f.getAbsolutePath()); }
                        MediaScannerConnection.scanFile(this, scan.toArray(new String[0]), null, null);
                    }, "Borrado");
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askName(String title, String initial, final NameCallback cb) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        int dot = initial.lastIndexOf('.');
        input.setSelection(0, dot > 0 ? dot : initial.length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(title).setView(wrap)
                .setPositiveButton("Aceptar", (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty() || n.contains("/") || n.equals(".") || n.equals("..")) { toast("Nombre no válido"); return; }
                    cb.done(n);
                })
                .setNegativeButton("Cancelar", null)
                .show();
        input.requestFocus();
    }

    private interface NameCallback { void done(String name); }

    private void newFolder() {
        if (dir == null) { toast("Primero entra a una carpeta."); return; }
        askName("Nueva carpeta", "Nueva carpeta", n -> {
            File f = new File(dir, n);
            if (f.exists()) toast("Ya existe algo con ese nombre.");
            else if (f.mkdir()) load(f);
            else toast("No se pudo crear la carpeta.");
        });
    }

    private void rename(final File f) {
        askName("Renombrar", f.getName(), n -> {
            File to = new File(f.getParentFile(), n);
            if (to.exists()) { toast("Ya existe algo con ese nombre."); return; }
            if (f.renameTo(to)) {
                selected.remove(f);
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath(), to.getAbsolutePath()}, null, null);
                load(to);
            } else {
                toast("No se pudo renombrar.");
            }
        });
    }

    // ---------------------------------------------------------------- permisos

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void askPermissionIfNeeded() {
        if (hasStorageAccess()) return;
        if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this)
                    .setTitle("Permiso de archivos")
                    .setMessage("Para ver y manejar todos tus archivos, activa \"Permitir acceso para administrar "
                            + "todos los archivos\" para Navegador en la pantalla que se abrirá, y luego vuelve con Atrás.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> openPermissionSettings())
                    .setNegativeButton("Ahora no", null)
                    .show();
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
        }
    }

    private void openPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent[] tries = {
                    new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())),
                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
            };
            for (Intent i : tries) {
                try { startActivity(i); return; } catch (Exception ignored) {}
            }
            toast("No se pudo abrir la pantalla de permisos.");
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
        } else {
            toast("Ya tienes acceso a los archivos.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        load(null);
    }

    // ---------------------------------------------------------------- teclas

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = list.getSelectedItemPosition();
            if (pos >= 0 && pos < entries.size()) actions(entries.get(pos));
            else options();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (!selected.isEmpty()) { selected.clear(); refresh(); return; }
        if (dir != null) { goUp(); return; }
        super.onBackPressed();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
