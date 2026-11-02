package com.github.mytv.mlfixture;

import android.content.Context;
import android.widget.RemoteViews;

/** book: 2x2 "continue listening", has a configuration activity. */
public class BookWidget extends WidgetBase {
    @Override
    RemoteViews build(Context c, int id, int count) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_book);
        String state = Fx.prefs(c).getString("configured:" + id, "");
        v.setTextViewText(R.id.w_title, label(c, BookWidget.class));
        v.setTextViewText(R.id.w_desc, "第 " + count + " 章" + (state.length() > 0 ? " · " + state : ""));
        v.setOnClickPendingIntent(R.id.w_resume, click(c, BookWidget.class, id, "resume"));
        return v;
    }
}
