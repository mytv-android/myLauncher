package com.github.mytv.mlfixture;

import android.content.Context;
import android.widget.RemoteViews;

/** sky: 4x2 weather. TextViews are the click targets; colours come from system_accent on API 31+. */
public class WeatherWidget extends WidgetBase {
    @Override
    RemoteViews build(Context c, int id, int count) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_weather);
        v.setTextViewText(R.id.w_temp, "23°");
        v.setTextViewText(R.id.w_desc, "晴 · #" + count);
        v.setOnClickPendingIntent(R.id.w_today, click(c, WeatherWidget.class, id, "today"));
        v.setOnClickPendingIntent(R.id.w_tomorrow, click(c, WeatherWidget.class, id, "tomorrow"));
        v.setOnClickPendingIntent(R.id.w_refresh, click(c, WeatherWidget.class, id, "refresh"));
        return v;
    }
}
