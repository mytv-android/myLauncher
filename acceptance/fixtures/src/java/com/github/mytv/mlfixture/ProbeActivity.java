package com.github.mytv.mlfixture;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;

/** The probe's AppWidgetHost: shows every widget bound with bind_widget. */
public class ProbeActivity extends Activity {
    private static ProbeActivity sShown;
    private Host host;
    private LinearLayout list;

    /** AppWidgetHost whose views log when the error view replaces a widget that failed to inflate. */
    static class Host extends AppWidgetHost {
        Host(Context c, int id) {
            super(c, id);
        }

        @Override
        protected AppWidgetHostView onCreateView(Context c, int appWidgetId, AppWidgetProviderInfo info) {
            return new HostView(c);
        }
    }

    static class HostView extends AppWidgetHostView {
        HostView(Context c) {
            super(c);
        }

        @Override
        protected View getErrorView() {
            AppWidgetProviderInfo info = getAppWidgetInfo();
            Fx.log(getContext(), "host_error_view", Fx.j("appWidgetId", getAppWidgetId(),
                    "provider", info == null ? null : info.provider.flattenToShortString()));
            return super.getErrorView();
        }
    }

    static void reloadShown() {
        if (sShown != null) sShown.reload();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppWidgetManager.getInstance(this) != null) host = new Host(this, Probe.HOST_ID);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#455a64"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        list.setPadding(pad, pad, pad, pad);
        sv.addView(list);
        setContentView(sv);
        Fx.log(this, "probe_activity", Fx.j("hostAvailable", host != null));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (host != null) {
            try {
                host.startListening();
            } catch (Exception e) {
                Fx.log(this, "host_error", Fx.j("error", e.toString()));
            }
        }
        sShown = this;
        reload();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sShown == this) sShown = null;
        if (host != null) {
            try {
                host.stopListening();
            } catch (Exception ignored) {
            }
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    void reload() {
        list.removeAllViews();
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        if (host == null || awm == null) return;
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = null;
        int n = 0;
        for (int id : Probe.boundIds(this)) {
            if (n++ % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row);
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(0, 0, dp(24), dp(16));
            row.addView(cell);
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            TextView head = new TextView(this);
            head.setTextColor(Color.LTGRAY);
            head.setText("#" + id + " " + (info == null ? "-" : info.provider.getClassName().replace(Fx.NS, "")));
            cell.addView(head);
            if (info == null) continue;
            AppWidgetHostView hv = host.createView(this, id, info);
            int w = info.minWidth + dp(30);
            int h = info.minHeight + dp(30);
            cell.addView(hv, new LinearLayout.LayoutParams(w, h));
            int wDp = (int) (w / density);
            int hDp = (int) (h / density);
            if (Build.VERSION.SDK_INT >= 31) {
                hv.updateAppWidgetSize(new Bundle(), Collections.singletonList(new SizeF(wDp, hDp)));
            } else {
                hv.updateAppWidgetSize(null, wDp, hDp, wDp, hDp);
            }
        }
    }
}
