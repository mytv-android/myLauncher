package com.github.mytv.mlfixture;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Not exported. Success callback of requestPinAppWidget(). */
public class PinResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Fx.log(c, "pin_result", Fx.j(
                "provider", i.getStringExtra("provider"),
                "appWidgetId", i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)));
    }
}
