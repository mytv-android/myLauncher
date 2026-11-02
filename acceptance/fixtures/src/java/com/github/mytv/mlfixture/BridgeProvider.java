package com.github.mytv.mlfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Series bridge v1: action com.github.mytv.action.LAUNCHER_BRIDGE, authority PKG.launcherbridge.
 * call(): hello / init / approve / hide. query(): /channels, /preview_programs,
 * /watch_next_programs, with rows only on API < 26 or without TvProvider.
 * Every access is logged with the caller package and certificate.
 */
public class BridgeProvider extends ContentProvider {
    static final int VERSION = 1;

    @Override
    public boolean onCreate() {
        return true;
    }

    static Uri base(Context c) {
        return Uri.parse("content://" + c.getPackageName() + ".launcherbridge");
    }

    static void notifyRows(Context c) {
        if (!Fx.cfg(c).optBoolean("bridge")) return;
        c.getContentResolver().notifyChange(base(c), null);
    }

    private String callerPackage() {
        String p = null;
        try {
            p = getCallingPackage();
        } catch (SecurityException ignored) {
        }
        if (p == null) {
            String[] ps = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (ps != null && ps.length > 0) p = ps[0];
        }
        return p;
    }

    /** Fills caller fields into e and returns whether the caller passes the pinned-cert check. */
    private boolean check(JSONObject e) {
        Context c = getContext();
        String caller = callerPackage();
        String cert;
        long id = Binder.clearCallingIdentity();
        try {
            cert = Fx.certSha256(c, caller);
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        String pinned = Fx.prefs(c).getString("launcher_cert", null);
        boolean ok = pinned == null || pinned.equals(cert);
        Fx.put(e, "caller", caller);
        Fx.put(e, "callerUid", Binder.getCallingUid());
        Fx.put(e, "callerCert", cert);
        Fx.put(e, "pinned", pinned != null);
        Fx.put(e, "certOk", pinned == null ? null : ok);
        return ok;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context c = getContext();
        JSONObject e = Fx.j("method", method, "arg", arg, "extras", Fx.bundleToJson(extras));
        boolean ok = check(e);
        if (!ok) {
            Fx.put(e, "accepted", false);
            Fx.log(c, "bridge_call", e);
            throw new SecurityException("launcher certificate mismatch");
        }
        Bundle r = new Bundle();
        long token = Binder.clearCallingIdentity();
        try {
            SharedPreferences sp = Fx.prefs(c);
            if ("hello".equals(method)) {
                r.putInt("version", VERSION);
                r.putString("package", c.getPackageName());
            } else if ("init".equals(method)) {
                JSONObject p = Tv.publish(c, null, "bridge_init");
                r.putBoolean("ok", true);
                r.putString("published", p.has("skipped") ? p.optString("skipped") : "tv_provider");
            } else if ("approve".equals(method)) {
                boolean approved = extras == null || extras.getBoolean("approved", true);
                sp.edit().putBoolean("approved:" + arg, approved).commit();
                r.putBoolean("ok", true);
                r.putBoolean("approved", approved);
                notifyRows(c);
            } else if ("hide".equals(method)) {
                Set<String> hidden = new HashSet<String>(sp.getStringSet("hidden", new HashSet<String>()));
                hidden.add(String.valueOf(arg));
                sp.edit().putStringSet("hidden", hidden).commit();
                r.putBoolean("ok", true);
                notifyRows(c);
            } else {
                Fx.put(e, "accepted", false);
                Fx.put(e, "error", "unknown_method");
                Fx.log(c, "bridge_call", e);
                throw new IllegalArgumentException("unknown method " + method);
            }
        } catch (IllegalArgumentException x) {
            throw x;
        } catch (Exception x) {
            Fx.put(e, "error", x.toString());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        Fx.put(e, "accepted", true);
        Fx.put(e, "result", Fx.bundleToJson(r));
        Fx.log(c, "bridge_call", e);
        return r;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context c = getContext();
        String table = uri.getLastPathSegment();
        JSONObject e = Fx.j("path", uri.getPath());
        boolean ok = check(e);
        boolean serve = Build.VERSION.SDK_INT < 26 || !Fx.hasTvProvider(c);
        Fx.put(e, "serve", serve);
        if (!ok) {
            Fx.put(e, "accepted", false);
            Fx.log(c, "bridge_query", e);
            throw new SecurityException("launcher certificate mismatch");
        }
        String[] cols = projection != null ? projection : Rows.colsFor(table);
        MatrixCursor m = new MatrixCursor(cols);
        int n = 0;
        try {
            if (serve) {
                List<ContentValues> rows = Rows.forTable(c, table);
                for (ContentValues v : rows) {
                    Object[] row = new Object[cols.length];
                    for (int i = 0; i < cols.length; i++) row[i] = v.get(cols[i]);
                    m.addRow(row);
                }
                n = rows.size();
            } else {
                if (!"channels".equals(table) && !"preview_programs".equals(table)
                        && !"watch_next_programs".equals(table)) {
                    throw new IllegalArgumentException("unknown table " + table);
                }
            }
        } catch (IllegalArgumentException x) {
            Fx.put(e, "error", x.getMessage());
            Fx.log(c, "bridge_query", e);
            throw x;
        } catch (Exception x) {
            Fx.put(e, "error", x.toString());
        }
        Fx.put(e, "accepted", true);
        Fx.put(e, "rows", n);
        Fx.log(c, "bridge_query", e);
        m.setNotificationUri(c.getContentResolver(), base(c));
        return m;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.mytv.bridge." + uri.getLastPathSegment();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
