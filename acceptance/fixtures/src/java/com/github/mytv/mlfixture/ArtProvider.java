package com.github.mytv.mlfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;

/** content://PKG.art/FILE, read-only posters and logos stored uncompressed in assets/art/. */
public class ArtProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private static String name(Uri uri) throws FileNotFoundException {
        String n = uri.getLastPathSegment();
        if (n == null || n.startsWith(".") || n.contains("/") || n.contains("\\")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return n;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        String n = name(uri);
        try {
            return getContext().getAssets().openFd("art/" + n);
        } catch (IOException e) {
            throw new FileNotFoundException(n);
        }
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return null;
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg")) return "image/jpeg";
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            AssetFileDescriptor fd = openAssetFile(uri, "r");
            long len = fd.getLength();
            fd.close();
            MatrixCursor m = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            m.addRow(new Object[]{uri.getLastPathSegment(), len});
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
