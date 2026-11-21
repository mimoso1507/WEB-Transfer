package com.transfer.navegador;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pantalla inicial: pregunta que herramienta usar (Navegador o Archivos). */
public class StartActivity extends Activity {

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        boolean wide = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(Color.parseColor("#F4F6FA"));
        page.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("¿Qué quieres usar?");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1b2333"));
        title.setGravity(Gravity.CENTER);
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(wide ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(24);
        page.addView(row, rlp);

        View web = card("🌐", "Navegador", "Abrir apps de tu red y páginas web", MainActivity.class);
        View files = card("\uD83D\uDCC2", "Archivos", "Ver, copiar, borrar, instalar apps y reproducir", FilesActivity.class);
        View transfer = card("\uD83D\uDD04", "Transfer", "Compartir archivos con otros equipos por Wi-Fi", TransferActivity.class);
        for (View v : new View[]{web, files, transfer}) {
            LinearLayout.LayoutParams clp = wide ? new LinearLayout.LayoutParams(0, dp(220), 1)
                    : new LinearLayout.LayoutParams(dp(280), dp(150));
            clp.setMargins(dp(10), dp(10), dp(10), dp(10));
            row.addView(v, clp);
        }
        TextView ver = new TextView(this);
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver.setText("Versión " + pi.versionName + "  (compilación " + pi.versionCode + ")");
        } catch (Exception e) {
            ver.setText("");
        }
        ver.setTextSize(14);
        ver.setTextColor(Color.parseColor("#566078"));
        ver.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(12);
        page.addView(ver, vlp);
        setContentView(page);
        web.requestFocus();
    }

    private View card(String icon, String name, String what, final Class<?> target) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setOnClickListener(v -> startActivity(new Intent(this, target)));

        TextView i = new TextView(this);
        i.setText(icon);
        i.setTextSize(48);
        i.setGravity(Gravity.CENTER);
        c.addView(i);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(22);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(Color.parseColor("#1b2333"));
        n.setGravity(Gravity.CENTER);
        c.addView(n);

        TextView w = new TextView(this);
        w.setText(what);
        w.setTextSize(15);
        w.setTextColor(Color.parseColor("#566078"));
        w.setGravity(Gravity.CENTER);
        c.addView(w);
        return c;
    }
}
