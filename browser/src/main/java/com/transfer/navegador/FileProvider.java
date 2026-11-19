package com.transfer.navegador;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** Comparte archivos con otras apps (abrir, instalar APK, compartir) sin librerias externas. */
public class FileProvider extends ContentProvider {
    static final String AUTHORITY = "com.transfer.navegador.archivos";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getAbsolutePath()).build();
    }

    static String mimeOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static File fileOf(Uri uri) throws FileNotFoundException {
        String p = uri.getPath();
        if (p == null) throw new FileNotFoundException();
        try {
            File f = new File(p).getCanonicalFile();
            if (!f.isFile()) throw new FileNotFoundException(p);
            return f;
        } catch (java.io.IOException e) {
            throw new FileNotFoundException(p);
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        try { return mimeOf(fileOf(uri)); } catch (Exception e) { return null; }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = fileOf(uri); } catch (Exception e) { return null; }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }

}
