package com.github.mytv.mlfixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONObject;

/** Solid colour page with the app name. Logs every launch with its program / channel extra. */
public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JSONObject cfg = Fx.cfg(this);
        TextView tv = new TextView(this);
        tv.setText(cfg.optString("label"));
        tv.setTextSize(48);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.parseColor(cfg.optString("color", "#455a64")));
        setContentView(tv);
        logLaunch(getIntent(), "create");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        logLaunch(intent, "new_intent");
    }

    private void logLaunch(Intent i, String via) {
        Uri ref = null;
        try {
            ref = getReferrer();
        } catch (Exception ignored) {
        }
        Fx.log(this, "launched", Fx.j(
                "program", i.getStringExtra("program"),
                "channel", i.getStringExtra("channel"),
                "action", i.getAction(),
                "flags", i.getFlags(),
                "referrer", ref == null ? null : ref.toString(),
                "via", via));
    }
}
