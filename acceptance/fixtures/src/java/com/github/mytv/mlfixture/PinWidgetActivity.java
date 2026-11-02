package com.github.mytv.mlfixture;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * am start -n PKG/com.github.mytv.mlfixture.PinWidgetActivity --es provider CLASS
 * Calls requestPinAppWidget() and logs pin_requested; the success callback logs pin_result.
 */
public class PinWidgetActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String cls = getIntent().getStringExtra("provider");
        Boolean supported = null;
        Boolean requested = null;
        String error = null;
        ComponentName cn = null;
        try {
            cn = new ComponentName(this, Fx.widgetClass(cls));
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            if (awm == null) {
                error = "no_appwidget_service";
            } else if (Build.VERSION.SDK_INT < 26) {
                error = "api<26";
            } else {
                supported = awm.isRequestPinAppWidgetSupported();
                Intent cb = new Intent(this, PinResultReceiver.class).putExtra("provider", cls);
                PendingIntent pi = PendingIntent.getBroadcast(this, 0, cb, Fx.piFlags(true));
                requested = awm.requestPinAppWidget(cn, null, pi);
            }
        } catch (Exception e) {
            error = e.toString();
        }
        Fx.log(this, "pin_requested", Fx.j(
                "provider", cn == null ? cls : cn.flattenToShortString(),
                "isRequestPinAppWidgetSupported", supported,
                "requestPinAppWidget", requested,
                "error", error));
        finish();
    }
}
