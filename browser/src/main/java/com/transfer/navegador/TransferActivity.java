package com.transfer.navegador;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class TransferActivity extends Activity {
    private TextView status, url, ipUrl, folder;
    private EditText port;
    private Button toggle, openBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("transfer", MODE_PRIVATE);
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(36), pad, pad);
        root.setBackgroundColor(Color.parseColor("#F4F6FA"));

        TextView title = text("\uD83D\uDCC1 Transfer", 28, true, "#151820");
        root.addView(title);
        TextView sub = text("Comparte archivos con la TV y otros dispositivos de tu Wi-Fi.", 15, false, "#5D6675");
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        LinearLayout card = card();
        status = text("Detenido", 16, true, "#5D6675");
        card.addView(status);
        TextView hint = text("Abre esta dirección en el navegador del otro dispositivo:", 14, false, "#5D6675");
        hint.setPadding(0, dp(10), 0, dp(4));
        card.addView(hint);
        url = text("—", 22, true, "#4F8CFF");
        url.setTextIsSelectable(true);
        card.addView(url);
        ipUrl = text("", 18, false, "#151820");
        ipUrl.setTextIsSelectable(true);
        card.addView(ipUrl);
        TextView ipHint = text("Si la TV no abre la primera, usa la de números.", 13, false, "#5D6675");
        ipHint.setPadding(0, dp(2), 0, 0);
        card.addView(ipHint);
        root.addView(card);

        LinearLayout card2 = card();
        card2.addView(text("Puerto", 14, true, "#151820"));
        port = new EditText(this);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(prefs.getInt("port", 4747)));
        port.setTextSize(20);
        card2.addView(port);
        TextView ph = text("Por defecto 4747. Usa un número entre 1024 y 65535.", 13, false, "#5D6675");
        card2.addView(ph);
        root.addView(card2);

        toggle = new Button(this);
        toggle.setTextSize(18);
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, new LinearLayout.LayoutParams(-1, dp(56)));

        openBtn = new Button(this);
        openBtn.setText("Abrir la página en este equipo (con el Navegador)");
        openBtn.setAllCaps(false);
        openBtn.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(TransferService.ipUrl))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        root.addView(openBtn, lp);

        folder = text("", 13, false, "#5D6675");
        folder.setPadding(0, dp(16), 0, 0);
        root.addView(folder);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        else if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);

        refresh();
    }

    private void onToggle() {
        Intent svc = new Intent(this, TransferService.class);
        if (TransferService.running) {
            stopService(svc);
            TransferService.running = false;
        } else {
            int p;
            try { p = Integer.parseInt(port.getText().toString().trim()); }
            catch (Exception e) { p = -1; }
            if (p < 1024 || p > 65535) {
                Toast.makeText(this, "Escribe un puerto entre 1024 y 65535", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putInt("port", p).apply();
            svc.putExtra(TransferService.EXTRA_PORT, p);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        }
        ui.postDelayed(this::refresh, 400);
    }

    private void refresh() {
        boolean on = TransferService.running;
        status.setText(on ? "● Activo" : (TransferService.error.isEmpty() ? "Detenido" : TransferService.error));
        status.setTextColor(Color.parseColor(on ? "#1E9E57" : TransferService.error.isEmpty() ? "#5D6675" : "#D93636"));
        url.setText(on ? TransferService.url : "—");
        ipUrl.setText(on ? TransferService.ipUrl : "");
        toggle.setText(on ? "Detener" : "Iniciar");
        port.setEnabled(!on);
        openBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        folder.setText(on ? "Los archivos se guardan en: " + TransferService.folderPath : "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private TextView text(String s, int sp, boolean bold, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#DFE3EA"));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
