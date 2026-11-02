package com.github.mytv.mlfixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;

/** Probe only: android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED (sent by the system). */
public class BrowsableRequestedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        JSONArray keys = new JSONArray();
        Bundle b = i.getExtras();
        if (b != null) for (String k : b.keySet()) keys.put(k);
        Fx.log(c, "browsable_requested", Fx.j(
                "package", i.getStringExtra("android.media.tv.extra.PACKAGE_NAME"),
                "channelId", i.getLongExtra("android.media.tv.extra.CHANNEL_ID", -1),
                "extras", keys));
    }
}
