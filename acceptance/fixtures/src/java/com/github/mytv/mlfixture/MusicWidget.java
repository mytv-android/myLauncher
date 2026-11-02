package com.github.mytv.mlfixture;

import android.content.Context;
import android.widget.RemoteViews;

/** pulse: 4x1 playback controls. Only the ImageViews are clickable. */
public class MusicWidget extends WidgetBase {
    @Override
    RemoteViews build(Context c, int id, int count) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_music);
        v.setTextViewText(R.id.w_title, "咖啡与雨 · #" + count);
        v.setOnClickPendingIntent(R.id.w_prev, click(c, MusicWidget.class, id, "prev"));
        v.setOnClickPendingIntent(R.id.w_play, click(c, MusicWidget.class, id, "play"));
        v.setOnClickPendingIntent(R.id.w_next, click(c, MusicWidget.class, id, "next"));
        return v;
    }
}
