package com.github.mytv.mlfixture;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** TvProvider writes. Only called on API 26+ with a TvProvider present. */
@TargetApi(26)
final class Tv26 {
    private Tv26() {}

    /** Own channel rows: internal_provider_id -> {_id, browsable}. */
    static Map<String, long[]> ownChannels(ContentResolver cr) {
        Map<String, long[]> m = new LinkedHashMap<String, long[]>();
        Cursor cur = cr.query(TvContract.Channels.CONTENT_URI,
                new String[]{"_id", "internal_provider_id", "browsable"}, null, null, null);
        if (cur == null) return m;
        try {
            while (cur.moveToNext()) {
                String key = cur.isNull(1) ? "_" + cur.getLong(0) : cur.getString(1);
                m.put(key, new long[]{cur.getLong(0), cur.getLong(2)});
            }
        } finally {
            cur.close();
        }
        return m;
    }

    static List<Long> ownIds(ContentResolver cr, Uri table) {
        List<Long> ids = new ArrayList<Long>();
        Cursor cur = cr.query(table, new String[]{"_id"}, null, null, null);
        if (cur == null) return ids;
        try {
            while (cur.moveToNext()) ids.add(cur.getLong(0));
        } finally {
            cur.close();
        }
        return ids;
    }

    static int deleteAll(ContentResolver cr, Uri table, boolean watchNext) {
        int n = 0;
        for (long id : ownIds(cr, table)) {
            Uri u = watchNext ? TvContract.buildWatchNextProgramUri(id) : TvContract.buildPreviewProgramUri(id);
            n += cr.delete(u, null, null);
        }
        return n;
    }

    static void writeLogo(Context c, ContentResolver cr, long channelId, String asset) {
        if (asset == null || asset.length() == 0) return;
        try {
            InputStream in = c.getAssets().open("art/" + asset);
            OutputStream out = cr.openOutputStream(TvContract.buildChannelLogoUri(channelId));
            try {
                out.write(Fx.readAll(in));
            } finally {
                in.close();
                out.close();
            }
        } catch (Exception e) {
            Fx.log(c, "logo_error", Fx.j("channelId", channelId, "error", e.toString()));
        }
    }

    static JSONObject publish(Context c, long base) throws Exception {
        ContentResolver cr = c.getContentResolver();
        JSONArray chs = Rows.channelsCfg(c);
        Map<String, long[]> existing = ownChannels(cr);
        Map<String, Long> ids = new LinkedHashMap<String, Long>();
        JSONArray outCh = new JSONArray();
        Set<String> keys = new HashSet<String>();
        for (int i = 0; i < chs.length(); i++) {
            JSONObject ch = chs.getJSONObject(i);
            String key = ch.getString("key");
            keys.add(key);
            ContentValues cv = Rows.channel(c, ch);
            long[] ex = existing.get(key);
            long id;
            if (ex != null) {
                id = ex[0];
                cr.update(TvContract.buildChannelUri(id), cv, null, null);
            } else {
                Uri u = cr.insert(TvContract.Channels.CONTENT_URI, cv);
                id = ContentUris.parseId(u);
            }
            ids.put(key, id);
            writeLogo(c, cr, id, ch.optString("logo"));
        }
        int removedChannels = 0;
        for (Map.Entry<String, long[]> e : existing.entrySet()) {
            if (!keys.contains(e.getKey())) {
                removedChannels += cr.delete(TvContract.buildChannelUri(e.getValue()[0]), null, null);
            }
        }

        int deletedPrograms = deleteAll(cr, TvContract.PreviewPrograms.CONTENT_URI, false);
        JSONArray outP = new JSONArray();
        for (int i = 0; i < chs.length(); i++) {
            JSONObject ch = chs.getJSONObject(i);
            long chId = ids.get(ch.getString("key"));
            JSONArray ps = ch.getJSONArray("programs");
            for (int k = 0; k < ps.length(); k++) {
                JSONObject p = ps.getJSONObject(k);
                Uri u = cr.insert(TvContract.PreviewPrograms.CONTENT_URI, Rows.preview(c, p, chId));
                outP.put(Fx.j("key", p.getString("key"), "id", u == null ? -1 : ContentUris.parseId(u)));
            }
        }

        int deletedWatchNext = deleteAll(cr, TvContract.WatchNextPrograms.CONTENT_URI, true);
        JSONArray outW = new JSONArray();
        JSONArray wn = Rows.watchNextCfg(c);
        for (int i = 0; i < wn.length(); i++) {
            JSONObject w = wn.getJSONObject(i);
            ContentValues v = Rows.watchNext(c, w, base);
            Uri u = cr.insert(TvContract.WatchNextPrograms.CONTENT_URI, v);
            outW.put(Fx.j("key", w.getString("key"), "id", u == null ? -1 : ContentUris.parseId(u),
                    "lastEngagement", v.getAsLong("last_engagement_time_utc_millis")));
        }

        for (int i = 0; i < chs.length(); i++) {
            JSONObject ch = chs.getJSONObject(i);
            String key = ch.getString("key");
            String req = null;
            if (ch.optBoolean("request")) {
                try {
                    TvContract.requestChannelBrowsable(c, ids.get(key));
                    req = "called";
                } catch (Exception e) {
                    req = e.toString();
                }
            }
            outCh.put(Fx.j("key", key, "id", ids.get(key), "requestBrowsable", req));
        }
        Map<String, long[]> after = ownChannels(cr);
        for (int i = 0; i < outCh.length(); i++) {
            JSONObject o = outCh.getJSONObject(i);
            long[] a = after.get(o.getString("key"));
            o.put("browsable", a == null ? JSONObject.NULL : a[1]);
        }
        return Fx.j("channels", outCh, "programs", outP, "watchNext", outW,
                "removedChannels", removedChannels, "deletedPrograms", deletedPrograms,
                "deletedWatchNext", deletedWatchNext);
    }

    static JSONObject unpublish(Context c) {
        ContentResolver cr = c.getContentResolver();
        int p = deleteAll(cr, TvContract.PreviewPrograms.CONTENT_URI, false);
        int w = deleteAll(cr, TvContract.WatchNextPrograms.CONTENT_URI, true);
        int ch = 0;
        for (long[] v : ownChannels(cr).values()) {
            ch += cr.delete(TvContract.buildChannelUri(v[0]), null, null);
        }
        return Fx.j("channels", ch, "programs", p, "watchNext", w);
    }

    static JSONObject requestBrowsable(Context c, String key) {
        long[] v = ownChannels(c.getContentResolver()).get(key);
        if (v == null) return Fx.j("key", key, "error", "channel_not_found");
        try {
            TvContract.requestChannelBrowsable(c, v[0]);
            return Fx.j("key", key, "id", v[0], "result", "called");
        } catch (Exception e) {
            return Fx.j("key", key, "id", v[0], "error", e.toString());
        }
    }
}
