package com.transfer.navegador;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/** Instala archivos APK, pidiendo el permiso de "instalar apps desconocidas" cuando hace falta. */
final class Installer {
    private static Uri pending;

    private Installer() {}

    static boolean canInstall(Activity a) {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    static void install(final Activity a, final Uri apk) {
        if (apk == null) { Toast.makeText(a, "No se encontro el archivo.", Toast.LENGTH_LONG).show(); return; }
        if (!canInstall(a)) {
            new AlertDialog.Builder(a)
                    .setTitle("Permitir instalar apps")
                    .setMessage("Para instalar apps desde aqui, activa \"Permitir de esta fuente\" para Navegador "
                            + "en la pantalla que se abrira, y luego vuelve con Atras. La instalacion sigue sola.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> {
                        pending = apk;
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            pending = null;
                            Toast.makeText(a, "Busca \"Fuentes desconocidas\" en Ajustes > Seguridad.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        launch(a, apk);
    }

    /** Llamar desde onResume: si se estaba esperando el permiso y ya se dio, instala. */
    static void resume(Activity a) {
        if (pending != null && canInstall(a)) {
            Uri u = pending;
            pending = null;
            launch(a, u);
        }
    }

    private static void launch(Activity a, Uri apk) {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "Este equipo no permite instalar apps desde archivos.", Toast.LENGTH_LONG).show();
        }
    }
}
