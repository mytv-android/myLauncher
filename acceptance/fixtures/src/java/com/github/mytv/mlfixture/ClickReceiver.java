package com.github.mytv.mlfixture;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Not exported, so only this app's own PendingIntents (fired by the host) can reach it. */
public class ClickReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Fx.log(c, "widget_click", Fx.j(
                "provider", i.getStringExtra("provider"),
                "appWidgetId", i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
                "button", i.getStringExtra("button")));
    }
}
