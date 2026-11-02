package com.github.mytv.mlfixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * adb shell am broadcast -n PKG/com.github.mytv.mlfixture.CmdReceiver --es cmd CMD [--es arg ARG]
 * Guarded by android.permission.DUMP, so only the shell can send it.
 */
public class CmdReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        String arg = intent.getStringExtra("arg");
        JSONObject res;
        try {
            res = run(c, cmd == null ? "" : cmd, arg);
        } catch (Throwable t) {
            res = Fx.j("cmd", cmd, "arg", arg, "error", t.toString());
            Fx.log(c, "cmd_error", res);
        }
        if (isOrderedBroadcast()) {
            String s = Fx.ascii(String.valueOf(res));
            if (s.length() > 3000) s = s.substring(0, 3000) + "...";
            setResultCode(res != null && !res.has("error") ? 1 : 2);
            setResultData(s);
        }
    }

    static JSONObject run(Context c, String cmd, String arg) throws Exception {
        switch (cmd) {
            case "publish":
                return Tv.publish(c, arg, "cmd");
            case "unpublish":
                return Tv.unpublish(c);
            case "request_browsable":
                return Tv.requestBrowsable(c, arg);
            case "widget_update":
                return WidgetBase.updateAll(c, "cmd");
            case "set_launcher_cert": {
                String cert = arg == null ? "" : arg.replace(":", "").trim().toLowerCase(java.util.Locale.ROOT);
                if (cert.length() == 0) {
                    Fx.prefs(c).edit().remove("launcher_cert").commit();
                } else {
                    Fx.prefs(c).edit().putString("launcher_cert", cert).commit();
                }
                JSONObject o = Fx.j("cert", cert.length() == 0 ? null : cert);
                Fx.log(c, "cert_set", o);
                return o;
            }
            case "clear_log":
                Fx.clearLog(c);
                return Fx.j("cleared", true);
            default:
                if (Fx.cfg(c).optBoolean("probe")) {
                    JSONObject o = Probe.handle(c, cmd, arg);
                    if (o != null) return o;
                }
                JSONObject o = Fx.j("cmd", cmd, "arg", arg, "error", "unknown_command");
                Fx.log(c, "cmd_error", o);
                return o;
        }
    }
}
