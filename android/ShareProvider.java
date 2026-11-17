package fr.brasselet.plaud;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * v1.6 — Partage des longs entretiens en fichier joint.
 * Donne aux autres applications (Claude) un accès en lecture seule, temporaire et
 * limité au seul fichier partagé, rangé dans le dossier cache/partage de l'application.
 * Écrit sans bibliothèque externe pour garder la compilation simple (pas d'AndroidX).
 */
public final class ShareProvider extends ContentProvider {

    static final String AUTHORITY = "fr.brasselet.plaud.partage";

    /** Dossier des fichiers partagés ; créé au besoin. */
    static File dir(android.content.Context c) {
        File d = new File(c.getCacheDir(), "partage");
        d.mkdirs();
        return d;
    }

    /** Fichier désigné par l'URI, uniquement s'il est bien dans le dossier de partage. */
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) return null;
        try {
            File d = dir(getContext());
            File f = new File(d, name);
            if (!f.getCanonicalPath().startsWith(d.getCanonicalPath() + File.separator)) return null;
            return f.isFile() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(uri);
        if (f == null) return null;
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
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = file(uri);
        if (f == null || !"r".equals(mode)) throw new java.io.FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;   // lecture seule
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;      // lecture seule
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;      // lecture seule
    }
}
