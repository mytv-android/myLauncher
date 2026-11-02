package com.github.mytv.mlfixture;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Commands of the probe app (cfg.probe == true). */
final class Probe {
    static final int HOST_ID = 0x4d4c;
    static final String TV = "content://android.media.tv/";

    private Probe() {}

    static JSONObject handle(Context c, String cmd, String arg) throws Exception {
        switch (cmd) {
            case "dump_tv":
                return dumpTv(c);
            case "probe_handlers":
                return handlers(c);
            case "bind_widget":
                return bindWidget(c, arg);
            case "unbind_widget":
                return unbindWidget(c, arg);
            case "call_bridge":
                return callBridge(c, arg);
            case "query_bridge":
                return queryBridge(c, arg);
            case "read_log":
                return readLog(c, arg);
            default:
                return null;
        }
    }

    // ---- dump_tv ------------------------------------------------------------

    static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    static Object num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : (Object) c.getLong(i);
    }

    static JSONObject row(String table, Cursor c) {
        if (table.equals("channels")) {
            return Fx.j("id", num(c, "_id"), "name", str(c, "display_name"),
                    "ipid", str(c, "internal_provider_id"), "type", str(c, "type"),
                    "browsable", num(c, "browsable"));
        }
        if (table.equals("preview_programs")) {
            return Fx.j("id", num(c, "_id"), "ch", num(c, "channel_id"), "title", str(c, "title"),
                    "ipid", str(c, "internal_provider_id"), "type", num(c, "type"),
                    "w", num(c, "weight"), "ar", num(c, "poster_art_aspect_ratio"),
                    "live", num(c, "live"), "browsable", num(c, "browsable"),
                    "poster", str(c, "poster_art_uri"));
        }
        return Fx.j("id", num(c, "_id"), "title", str(c, "title"),
                "ipid", str(c, "internal_provider_id"), "type", num(c, "type"),
                "wn", num(c, "watch_next_type"), "t", num(c, "last_engagement_time_utc_millis"),
                "pos", num(c, "last_playback_position_millis"), "dur", num(c, "duration_millis"),
                "browsable", num(c, "browsable"));
    }

    static JSONObject dumpTv(Context c) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String[][] tables = {{"channels", "channel"}, {"preview_programs", "preview_program"},
                {"watch_next_programs", "watch_next_program"}};
        JSONObject summary = new JSONObject();
        for (String[] t : tables) {
            JSONObject ev = Fx.j("table", t[0]);
            try {
                Cursor cur = cr.query(Uri.parse(TV + t[1]), null, null, null, null);
                if (cur == null) {
                    ev.put("error", "null_cursor");
                } else {
                    try {
                        ev.put("total", cur.getCount());
                        JSONObject byPkg = new JSONObject();
                        while (cur.moveToNext()) {
                            String pkg = String.valueOf(str(cur, "package_name"));
                            JSONObject g = byPkg.optJSONObject(pkg);
                            if (g == null) {
                                g = Fx.j("count", 0, "rows", new JSONArray());
                                byPkg.put(pkg, g);
                            }
                            g.put("count", g.getInt("count") + 1);
                            g.getJSONArray("rows").put(row(t[0], cur));
                        }
                        ev.put("byPackage", byPkg);
                    } finally {
                        cur.close();
                    }
                }
            } catch (Exception e) {
                ev.put("error", e.getClass().getName() + ": " + e.getMessage());
            }
            Fx.log(c, "dump_tv", ev);
            summary.put(t[0], ev.has("error") ? ev.get("error") : ev.get("total"));
        }
        String[][] sel = {{"channel", "type=?", "TYPE_PREVIEW"}, {"preview_program", "weight>=?", "0"}};
        JSONArray selOut = new JSONArray();
        for (String[] s : sel) {
            JSONObject ev = Fx.j("uri", TV + s[0], "selection", s[1]);
            try {
                Cursor cur = cr.query(Uri.parse(TV + s[0]), new String[]{"_id"}, s[1], new String[]{s[2]}, null);
                ev.put("securityException", false);
                ev.put("rows", cur == null ? -1 : cur.getCount());
                if (cur != null) cur.close();
            } catch (SecurityException e) {
                ev.put("securityException", true);
                ev.put("message", e.getMessage());
            } catch (Exception e) {
                ev.put("securityException", false);
                ev.put("error", e.getClass().getName() + ": " + e.getMessage());
            }
            Fx.log(c, "dump_tv_selection", ev);
            selOut.put(ev);
        }
        summary.put("selection", selOut);
        return summary;
    }

    // ---- probe_handlers -----------------------------------------------------

    static String cn(ComponentInfo ci) {
        return new ComponentName(ci.packageName, ci.name).flattenToShortString();
    }

    static JSONArray acts(PackageManager pm, Intent i) {
        JSONArray a = new JSONArray();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) a.put(cn(r.activityInfo));
        return a;
    }

    static Object def(PackageManager pm, Intent i) {
        ResolveInfo r = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? null : cn(r.activityInfo);
    }

    static JSONArray rcvs(PackageManager pm, Intent i) {
        JSONArray a = new JSONArray();
        for (ResolveInfo r : pm.queryBroadcastReceivers(i, 0)) a.put(cn(r.activityInfo));
        return a;
    }

    static JSONObject handlers(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        Intent reqBrowsable = new Intent(RequestBrowsableActivity.ACTION);
        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        JSONObject o = new JSONObject();
        o.put("api", Build.VERSION.SDK_INT);
        o.put("fingerprint", Build.FINGERPRINT);
        o.put("requestChannelBrowsable", acts(pm, reqBrowsable));
        o.put("requestChannelBrowsableDefault", def(pm, reqBrowsable));
        o.put("appwidgetBind", acts(pm, bind));
        o.put("appwidgetBindDefault", def(pm, bind));
        o.put("home", acts(pm, home));
        o.put("homeDefault", def(pm, home));
        o.put("initializePrograms", rcvs(pm, new Intent("android.media.tv.action.INITIALIZE_PROGRAMS")));
        o.put("channelBrowsableRequested", rcvs(pm, new Intent("android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED")));
        JSONArray bridges = new JSONArray();
        for (ResolveInfo r : pm.queryIntentContentProviders(new Intent("com.github.mytv.action.LAUNCHER_BRIDGE"), 0)) {
            bridges.put(r.providerInfo.packageName + ":" + r.providerInfo.authority);
        }
        o.put("bridgeProviders", bridges);
        JSONObject f = new JSONObject();
        for (String n : new String[]{"android.software.leanback", "android.software.leanback_only",
                "android.software.app_widgets", "android.software.live_tv",
                "com.google.android.feature.AMATI_EXPERIENCE"}) {
            f.put(n, pm.hasSystemFeature(n));
        }
        o.put("features", f);
        ProviderInfo tv = pm.resolveContentProvider(Fx.TV_AUTHORITY, 0);
        o.put("tvProvider", tv == null ? JSONObject.NULL : tv.packageName);
        o.put("appWidgetService", AppWidgetManager.getInstance(c) != null);
        Fx.log(c, "handlers", o);
        return o;
    }

    // ---- widgets ------------------------------------------------------------

    static List<Integer> boundIds(Context c) {
        List<Integer> ids = new ArrayList<Integer>();
        for (String s : Fx.prefs(c).getString("bound", "").split(",")) {
            if (s.length() > 0) ids.add(Integer.parseInt(s));
        }
        return ids;
    }

    static void saveIds(Context c, List<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (Integer i : ids) {
            if (b.length() > 0) b.append(',');
            b.append(i);
        }
        Fx.prefs(c).edit().putString("bound", b.toString()).commit();
    }

    static JSONObject bindWidget(Context c, String arg) throws Exception {
        JSONObject ev = Fx.j("provider", arg);
        AppWidgetManager awm = AppWidgetManager.getInstance(c);
        ComponentName cn = arg == null ? null : ComponentName.unflattenFromString(arg);
        if (awm == null || cn == null) {
            ev.put("ok", false);
            ev.put("error", awm == null ? "no_appwidget_service" : "bad_component");
            Fx.log(c, "bind_widget", ev);
            return ev;
        }
        AppWidgetHost host = new AppWidgetHost(c, HOST_ID);
        int id = host.allocateAppWidgetId();
        boolean ok = false;
        try {
            ok = awm.bindAppWidgetIdIfAllowed(id, cn);
        } catch (Exception e) {
            ev.put("error", e.toString());
        }
        ev.put("appWidgetId", id);
        ev.put("ok", ok);
        if (!ok) {
            host.deleteAppWidgetId(id);
        } else {
            List<Integer> ids = boundIds(c);
            ids.add(id);
            saveIds(c, ids);
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info != null) {
                ev.put("label", info.label);
                ev.put("configure", info.configure == null ? JSONObject.NULL : info.configure.flattenToShortString());
                ev.put("minWidth", info.minWidth);
                ev.put("minHeight", info.minHeight);
                if (Build.VERSION.SDK_INT >= 28) ev.put("widgetFeatures", info.widgetFeatures);
            }
            ProbeActivity.reloadShown();
        }
        Fx.log(c, "bind_widget", ev);
        return ev;
    }

    static JSONObject unbindWidget(Context c, String arg) {
        AppWidgetHost host = new AppWidgetHost(c, HOST_ID);
        List<Integer> ids = boundIds(c);
        List<Integer> keep = new ArrayList<Integer>();
        JSONArray removed = new JSONArray();
        for (Integer id : ids) {
            if (arg == null || arg.equals("all") || arg.equals(String.valueOf(id))) {
                host.deleteAppWidgetId(id);
                removed.put(id);
            } else {
                keep.add(id);
            }
        }
        saveIds(c, keep);
        JSONObject ev = Fx.j("removed", removed);
        Fx.log(c, "unbind_widget", ev);
        ProbeActivity.reloadShown();
        return ev;
    }

    // ---- bridge / log -------------------------------------------------------

    static JSONObject callBridge(Context c, String arg) throws Exception {
        String[] p = arg == null ? new String[0] : arg.split(":", 3);
        JSONObject ev = Fx.j("package", p.length > 0 ? p[0] : null, "method", p.length > 1 ? p[1] : null,
                "arg", p.length > 2 ? p[2] : null);
        try {
            Bundle r = c.getContentResolver().call(Uri.parse("content://" + p[0] + ".launcherbridge"),
                    p[1], p.length > 2 ? p[2] : null, null);
            ev.put("ok", true);
            ev.put("result", r == null ? JSONObject.NULL : Fx.bundleToJson(r));
        } catch (Exception e) {
            ev.put("ok", false);
            ev.put("exception", e.getClass().getName());
            ev.put("message", e.getMessage());
        }
        Fx.log(c, "call_bridge", ev);
        return ev;
    }

    static JSONObject queryBridge(Context c, String arg) throws Exception {
        String[] p = arg == null ? new String[0] : arg.split(":", 2);
        JSONObject ev = Fx.j("package", p.length > 0 ? p[0] : null, "path", p.length > 1 ? p[1] : null);
        try {
            Cursor cur = c.getContentResolver().query(
                    Uri.parse("content://" + p[0] + ".launcherbridge" + p[1]), null, null, null, null);
            if (cur == null) {
                ev.put("ok", false);
                ev.put("error", "null_cursor");
            } else {
                try {
                    ev.put("ok", true);
                    ev.put("rows", cur.getCount());
                    JSONArray titles = new JSONArray();
                    while (cur.moveToNext()) {
                        String t = str(cur, "title");
                        titles.put(t != null ? t : str(cur, "display_name"));
                    }
                    ev.put("titles", titles);
                    ev.put("columns", new JSONArray(java.util.Arrays.asList(cur.getColumnNames())));
                } finally {
                    cur.close();
                }
            }
        } catch (Exception e) {
            ev.put("ok", false);
            ev.put("exception", e.getClass().getName());
            ev.put("message", e.getMessage());
        }
        Fx.log(c, "query_bridge", ev);
        return ev;
    }

    static JSONObject readLog(Context c, String pkg) throws Exception {
        JSONObject ev = Fx.j("package", pkg);
        try {
            Cursor cur = c.getContentResolver().query(Uri.parse("content://" + pkg + ".log/events"),
                    null, null, null, null);
            ev.put("ok", true);
            ev.put("rows", cur == null ? -1 : cur.getCount());
            if (cur != null) cur.close();
        } catch (Exception e) {
            ev.put("ok", false);
            ev.put("exception", e.getClass().getName());
            ev.put("message", e.getMessage());
        }
        Fx.log(c, "read_log", ev);
        return ev;
    }
}
