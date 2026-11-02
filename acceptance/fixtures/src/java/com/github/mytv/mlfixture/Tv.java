package com.github.mytv.mlfixture;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

/** Entry points for TvProvider commands; API 26+ work lives in {@link Tv26}. */
final class Tv {
    private Tv() {}

    static String unavailable(Context c) {
        if (Build.VERSION.SDK_INT < 26) return "api<26";
        if (!Fx.hasTvProvider(c)) return "no_tv_provider";
        return null;
    }

    static JSONObject publish(Context c, String arg, String source) throws Exception {
        String why = unavailable(c);
        if (why != null) {
            JSONObject o = Fx.j("source", source, "skipped", why, "bridge", Fx.cfg(c).optBoolean("bridge"));
            Fx.log(c, "published", o);
            BridgeProvider.notifyRows(c);
            return o;
        }
        long base = System.currentTimeMillis();
        if (arg != null && arg.length() > 0) base = Long.parseLong(arg);
        JSONObject o = Tv26.publish(c, base);
        Fx.put(o, "source", source);
        Fx.log(c, "published", o);
        return o;
    }

    static JSONObject unpublish(Context c) throws Exception {
        String why = unavailable(c);
        JSONObject o = why != null ? Fx.j("skipped", why) : Tv26.unpublish(c);
        Fx.log(c, "unpublished", o);
        return o;
    }

    static JSONObject requestBrowsable(Context c, String key) throws Exception {
        String why = unavailable(c);
        JSONObject o = why != null ? Fx.j("key", key, "skipped", why) : Tv26.requestBrowsable(c, key);
        Fx.log(c, "request_browsable", o);
        return o;
    }
}
