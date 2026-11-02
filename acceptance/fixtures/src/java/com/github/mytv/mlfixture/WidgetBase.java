package com.github.mytv.mlfixture;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Base AppWidgetProvider: logs widget_update / widget_options / widget_deleted. */
public abstract class WidgetBase extends AppWidgetProvider {
    abstract RemoteViews build(Context c, int appWidgetId, int count);

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        render(c, m, getClass(), ids, "system");
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        JSONObject e = Fx.j(
                "provider", getClass().getSimpleName(),
                "appWidgetId", id,
                "minWidth", o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1),
                "minHeight", o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, -1),
                "maxWidth", o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, -1),
                "maxHeight", o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, -1),
                "category", o.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, -1));
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<Parcelable> sizes = o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            JSONArray a = new JSONArray();
            if (sizes != null) for (Parcelable s : sizes) a.put(String.valueOf(s));
            Fx.put(e, "sizes", a);
        }
        Fx.log(c, "widget_options", e);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        Fx.log(c, "widget_deleted", Fx.j("provider", getClass().getSimpleName(), "appWidgetIds", arr(ids)));
    }

    static JSONArray arr(int[] ids) {
        JSONArray a = new JSONArray();
        if (ids != null) for (int i : ids) a.put(i);
        return a;
    }

    static JSONObject render(Context c, AppWidgetManager m, Class<?> cls, int[] ids, String source) {
        int count = Fx.bump(c, "count:" + cls.getSimpleName());
        String error = null;
        try {
            WidgetBase w = (WidgetBase) cls.newInstance();
            for (int id : ids) m.updateAppWidget(id, w.build(c, id, count));
        } catch (Exception e) {
            error = e.toString();
        }
        JSONObject o = Fx.j("provider", cls.getSimpleName(), "appWidgetIds", arr(ids),
                "count", count, "source", source, "error", error);
        Fx.log(c, "widget_update", o);
        return o;
    }

    /** "widget_update" command: refresh every widget of this app once. */
    static JSONObject updateAll(Context c, String source) throws Exception {
        JSONArray ws = Fx.cfg(c).optJSONArray("widgets");
        JSONArray out = new JSONArray();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (ws == null || m == null) {
            return Fx.j("widgets", out, "error", m == null ? "no_appwidget_service" : null);
        }
        for (int i = 0; i < ws.length(); i++) {
            Class<?> cls = Fx.widgetClass(ws.getJSONObject(i).getString("class"));
            int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
            out.put(render(c, m, cls, ids, source));
        }
        return Fx.j("widgets", out);
    }

    static Intent clickIntent(Context c, Class<?> cls, int id, String button) {
        Intent i = new Intent(c, ClickReceiver.class)
                .setData(Uri.parse("mlfixture://click/" + cls.getSimpleName() + "/" + id + "/" + button))
                .putExtra("provider", cls.getSimpleName())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        if (button != null) i.putExtra("button", button);
        return i;
    }

    static PendingIntent click(Context c, Class<?> cls, int id, String button) {
        return PendingIntent.getBroadcast(c, 0, clickIntent(c, cls, id, button), Fx.piFlags(false));
    }

    static String label(Context c, Class<?> cls) {
        JSONArray ws = Fx.cfg(c).optJSONArray("widgets");
        if (ws != null) {
            for (int i = 0; i < ws.length(); i++) {
                JSONObject w = ws.optJSONObject(i);
                if (w != null && cls.getSimpleName().equals(w.optString("class"))) return w.optString("label");
            }
        }
        return cls.getSimpleName();
    }
}
