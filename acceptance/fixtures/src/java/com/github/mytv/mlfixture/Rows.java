package com.github.mytv.mlfixture;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TvContract rows built from fixture.json. Column names are plain strings so the same
 * values serve the TvProvider (API 26+) and the bridge provider (API 23-25).
 */
final class Rows {
    static final String[] CHANNEL_COLS = {
            "_id", "package_name", "type", "display_name", "app_link_intent_uri",
            "internal_provider_id", "browsable"};
    static final String[] PREVIEW_COLS = {
            "_id", "package_name", "channel_id", "type", "title", "short_description",
            "poster_art_uri", "poster_art_aspect_ratio", "intent_uri", "internal_provider_id",
            "weight", "live", "canonical_genre", "release_date", "season_display_number",
            "episode_display_number", "duration_millis", "last_playback_position_millis",
            "browsable"};
    static final String[] WATCH_NEXT_COLS = {
            "_id", "package_name", "type", "watch_next_type", "title", "short_description",
            "poster_art_uri", "poster_art_aspect_ratio", "intent_uri", "internal_provider_id",
            "last_engagement_time_utc_millis", "canonical_genre", "release_date",
            "season_display_number", "episode_display_number", "duration_millis",
            "last_playback_position_millis", "browsable"};

    /** Minutes between consecutive Watch Next ranks. */
    static final long RANK_STEP_MS = 10 * 60 * 1000L;

    private Rows() {}

    static JSONArray channelsCfg(Context c) {
        JSONArray a = Fx.cfg(c).optJSONArray("channels");
        return a == null ? new JSONArray() : a;
    }

    static JSONArray watchNextCfg(Context c) {
        JSONArray a = Fx.cfg(c).optJSONArray("watchNext");
        return a == null ? new JSONArray() : a;
    }

    static ContentValues channel(Context c, JSONObject ch) throws Exception {
        ContentValues v = new ContentValues();
        v.put("type", "TYPE_PREVIEW");
        v.put("display_name", ch.getString("name"));
        v.put("app_link_intent_uri", ch.getString("intent"));
        v.put("internal_provider_id", ch.getString("key"));
        return v;
    }

    static ContentValues program(Context c, JSONObject p) throws Exception {
        ContentValues v = new ContentValues();
        v.put("type", p.getInt("type"));
        v.put("title", p.getString("title"));
        if (p.has("desc")) v.put("short_description", p.getString("desc"));
        if (p.has("poster")) v.put("poster_art_uri", Fx.artUri(c, p.getString("poster")));
        if (p.has("aspect")) v.put("poster_art_aspect_ratio", p.getInt("aspect"));
        v.put("intent_uri", p.getString("intent"));
        v.put("internal_provider_id", p.getString("key"));
        if (p.optBoolean("live")) v.put("live", 1);
        if (p.has("genre")) v.put("canonical_genre", p.getString("genre"));
        if (p.has("year")) v.put("release_date", String.valueOf(p.getInt("year")));
        if (p.has("season")) v.put("season_display_number", String.valueOf(p.getInt("season")));
        if (p.has("episode")) v.put("episode_display_number", String.valueOf(p.getInt("episode")));
        if (p.has("durationMs")) v.put("duration_millis", p.getLong("durationMs"));
        if (p.has("positionMs")) v.put("last_playback_position_millis", p.getLong("positionMs"));
        return v;
    }

    static ContentValues preview(Context c, JSONObject p, long channelId) throws Exception {
        ContentValues v = program(c, p);
        v.put("channel_id", channelId);
        v.put("weight", p.getInt("weight"));
        return v;
    }

    static ContentValues watchNext(Context c, JSONObject w, long base) throws Exception {
        ContentValues v = program(c, w);
        v.put("watch_next_type", w.getInt("wnType"));
        v.put("last_engagement_time_utc_millis", base - w.getInt("rank") * RANK_STEP_MS);
        return v;
    }

    // ---- bridge rows (API < 26 or no TvProvider) ---------------------------

    static List<ContentValues> forTable(Context c, String table) throws Exception {
        SharedPreferences sp = Fx.prefs(c);
        Set<String> hidden = sp.getStringSet("hidden", new java.util.HashSet<String>());
        String pkg = c.getPackageName();
        List<ContentValues> out = new ArrayList<ContentValues>();
        JSONArray chs = channelsCfg(c);
        Map<String, Long> ids = new HashMap<String, Long>();
        for (int i = 0; i < chs.length(); i++) ids.put(chs.getJSONObject(i).getString("key"), (long) (i + 1));
        if ("channels".equals(table)) {
            for (int i = 0; i < chs.length(); i++) {
                JSONObject ch = chs.getJSONObject(i);
                ContentValues v = channel(c, ch);
                v.put("_id", (long) (i + 1));
                v.put("package_name", pkg);
                v.put("browsable", sp.getBoolean("approved:" + ch.getString("key"), false) ? 1 : 0);
                out.add(v);
            }
        } else if ("preview_programs".equals(table)) {
            long id = 1;
            for (int i = 0; i < chs.length(); i++) {
                JSONObject ch = chs.getJSONObject(i);
                JSONArray ps = ch.getJSONArray("programs");
                for (int k = 0; k < ps.length(); k++) {
                    JSONObject p = ps.getJSONObject(k);
                    if (hidden.contains(p.getString("key"))) continue;
                    ContentValues v = preview(c, p, ids.get(ch.getString("key")));
                    v.put("_id", id++);
                    v.put("package_name", pkg);
                    v.put("browsable", 1);
                    out.add(v);
                }
            }
        } else if ("watch_next_programs".equals(table)) {
            JSONArray wn = watchNextCfg(c);
            long base = System.currentTimeMillis();
            for (int i = 0; i < wn.length(); i++) {
                JSONObject w = wn.getJSONObject(i);
                if (hidden.contains(w.getString("key"))) continue;
                ContentValues v = watchNext(c, w, base);
                v.put("_id", (long) (i + 1));
                v.put("package_name", pkg);
                v.put("browsable", 1);
                out.add(v);
            }
        } else {
            throw new IllegalArgumentException("unknown table " + table);
        }
        return out;
    }

    static String[] colsFor(String table) {
        if ("channels".equals(table)) return CHANNEL_COLS;
        if ("preview_programs".equals(table)) return PREVIEW_COLS;
        return WATCH_NEXT_COLS;
    }
}
