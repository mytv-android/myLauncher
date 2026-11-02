package com.github.mytv.mlfixture;

import android.content.Context;
import android.widget.RemoteViews;

/** crash: layout contains an EditText, which RemoteViews refuses to inflate in the host. */
public class CrashWidget extends WidgetBase {
    @Override
    RemoteViews build(Context c, int id, int count) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_crash);
        v.setTextViewText(R.id.w_title, "#" + count);
        return v;
    }
}
