package com.github.mytv.mlfixture;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** Configuration activity of BookWidget: two large buttons, logs configure_result. */
public class BookConfigActivity extends Activity {
    private int appWidgetId;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        setResult(RESULT_CANCELED, result());
        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor(Fx.cfg(this).optString("color", "#3ba4a1")));
        Button done = button("完成", "done");
        Button cancel = button("取消", "cancel");
        root.addView(done);
        root.addView(cancel);
        setContentView(root);
        done.requestFocus();
    }

    private Intent result() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
    }

    private Button button(String text, final String action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(32);
        b.setMinWidth((int) (220 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (24 * getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWith(action);
            }
        });
        return b;
    }

    private void finishWith(String action) {
        if (finished) return;
        finished = true;
        boolean ok = "done".equals(action);
        if (ok && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Fx.prefs(this).edit().putString("configured:" + appWidgetId, "ok").commit();
            AppWidgetManager m = AppWidgetManager.getInstance(this);
            if (m != null) WidgetBase.render(this, m, BookWidget.class, new int[]{appWidgetId}, "configure");
        }
        setResult(ok ? RESULT_OK : RESULT_CANCELED, result());
        Fx.log(this, "configure_result", Fx.j("appWidgetId", appWidgetId, "result", action,
                "resultCode", ok ? RESULT_OK : RESULT_CANCELED));
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWith("back");
    }
}
