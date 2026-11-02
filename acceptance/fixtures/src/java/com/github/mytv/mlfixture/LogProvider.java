package com.github.mytv.mlfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** content://PKG.log/events, one column "line" holding a single-line JSON event. */
public class LogProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!"events".equals(uri.getLastPathSegment())) throw new IllegalArgumentException("unknown uri " + uri);
        MatrixCursor m = new MatrixCursor(new String[]{"line"});
        for (String l : Fx.readLog(getContext())) m.addRow(new Object[]{l});
        return m;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.mlfixture.event";
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
