package com.github.mytv.mlfixture;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

/** Supplies the 8 rows of FitWidget. */
public class FitService extends RemoteViewsService {
    static final String[] ITEMS = {
            "步数 6,240", "热量 420 千卡", "运动 38 分钟",
            "站立 9 小时", "心率 72", "睡眠 7.2 小时",
            "饮水 1.4 升", "距离 4.6 公里"};

    @Override
    public RemoteViewsFactory onGetViewFactory(final Intent intent) {
        final Context c = getApplicationContext();
        return new RemoteViewsFactory() {
            @Override public void onCreate() {}
            @Override public void onDataSetChanged() {}
            @Override public void onDestroy() {}
            @Override public int getCount() { return ITEMS.length; }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return true; }

            @Override
            public RemoteViews getViewAt(int position) {
                RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_fit_item);
                v.setTextViewText(R.id.w_item, ITEMS[position]);
                v.setOnClickFillInIntent(R.id.w_item, new Intent().putExtra("button", "item" + (position + 1)));
                return v;
            }
        };
    }
}
