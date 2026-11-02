package com.github.mytv.mlfixture;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;

/**
 * am start -n PKG/com.github.mytv.mlfixture.RequestBrowsableActivity --es channel KEY [--es target PKG]
 * Starts ACTION_REQUEST_CHANNEL_BROWSABLE for result and logs browsable_result.
 */
public class RequestBrowsableActivity extends Activity {
    static final String ACTION = "android.media.tv.action.REQUEST_CHANNEL_BROWSABLE";
    static final String EXTRA_CHANNEL_ID = "android.media.tv.extra.CHANNEL_ID";
    private String key;
    private String target;
    private long channelId = -1;
    private String handler;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            key = savedInstanceState.getString("key");
            target = savedInstanceState.getString("target");
            channelId = savedInstanceState.getLong("channelId", -1);
            handler = savedInstanceState.getString("handler");
            return;
        }
        key = getIntent().getStringExtra("channel");
        target = getIntent().getStringExtra("target");
        if (Build.VERSION.SDK_INT < 26 || !Fx.hasTvProvider(this)) {
            done(null, Build.VERSION.SDK_INT < 26 ? "api<26" : "no_tv_provider");
            return;
        }
        long[] ch = Tv26.ownChannels(getContentResolver()).get(key);
        if (ch == null) {
            done(null, "channel_not_found");
            return;
        }
        channelId = ch[0];
        Intent i = new Intent(ACTION).putExtra(EXTRA_CHANNEL_ID, channelId);
        if (target != null && target.length() > 0) i.setPackage(target);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null) {
            ActivityInfo ai = ri.activityInfo;
            handler = new ComponentName(ai.packageName, ai.name).flattenToShortString();
        }
        try {
            startActivityForResult(i, 1);
        } catch (ActivityNotFoundException e) {
            done(null, "ActivityNotFoundException");
        } catch (SecurityException e) {
            done(null, e.toString());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("key", key);
        out.putString("target", target);
        out.putLong("channelId", channelId);
        out.putString("handler", handler);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        done(resultCode, null);
    }

    private void done(Integer resultCode, String error) {
        if (finished) return;
        finished = true;
        Fx.log(this, "browsable_result", Fx.j(
                "channel", key,
                "channelId", channelId,
                "target", target,
                "handler", handler,
                "resultCode", resultCode,
                "error", error));
        finish();
    }
}
