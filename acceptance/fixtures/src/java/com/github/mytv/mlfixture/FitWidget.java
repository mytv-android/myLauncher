package com.github.mytv.mlfixture;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/** fit: 2x2 collection widget (ListView + RemoteViewsService, 8 items). */
public class FitWidget extends WidgetBase {
    @Override
    @SuppressWarnings("deprecation")
    RemoteViews build(Context c, int id, int count) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_fit);
        v.setTextViewText(R.id.w_title, label(c, FitWidget.class) + " · #" + count);
        Intent svc = new Intent(c, FitService.class)
                .setData(Uri.parse("mlfixture://fit/" + id + "/" + count))
                .putExtra("count", count);
        v.setRemoteAdapter(R.id.w_list, svc);
        v.setEmptyView(R.id.w_list, R.id.w_empty);
        // The host fills in the item extras, so the template must be mutable on API 31+.
        PendingIntent tpl = PendingIntent.getBroadcast(c, id,
                clickIntent(c, FitWidget.class, id, null), Fx.piFlags(true));
        v.setPendingIntentTemplate(R.id.w_list, tpl);
        return v;
    }
}
