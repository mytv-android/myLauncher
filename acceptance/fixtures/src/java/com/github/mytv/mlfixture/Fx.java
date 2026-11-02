package com.github.mytv.mlfixture;

import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Shared helpers: fixture.json, event log, certificates, PendingIntent flags. */
final class Fx {
    static final String TAG = "MLFixture";
    static final String NS = "com.github.mytv.mlfixture.";
    static final String TV_AUTHORITY = "android.media.tv";
    private static final Object LOCK = new Object();
    private static JSONObject sCfg;

    private Fx() {}

    static JSONObject cfg(Context c) {
        synchronized (LOCK) {
            if (sCfg == null) {
                try {
                    InputStream in = c.getAssets().open("fixture.json");
                    try {
                        sCfg = new JSONObject(new String(readAll(in), "UTF-8"));
                    } finally {
                        in.close();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("fixture.json", e);
                }
            }
            return sCfg;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mlfixture", Context.MODE_PRIVATE);
    }

    /** Builds a JSONObject from key/value pairs; null values become JSON null. */
    static JSONObject j(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** JSON with every non-ASCII char escaped, so adb output is encoding-proof. */
    static String ascii(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch > 0x7e) {
                b.append(String.format("\\u%04x", (int) ch));
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    // ---- event log -------------------------------------------------------

    static File logFile(Context c) {
        return new File(c.getFilesDir(), "events.jsonl");
    }

    static void log(Context c, String event, JSONObject data) {
        synchronized (LOCK) {
            try {
                SharedPreferences p = prefs(c);
                long seq = p.getLong("seq", 0) + 1;
                p.edit().putLong("seq", seq).commit();
                JSONObject o = new JSONObject();
                o.put("seq", seq);
                o.put("ts", System.currentTimeMillis());
                o.put("event", event);
                if (data != null) {
                    Iterator<String> it = data.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        o.put(k, data.get(k));
                    }
                }
                String line = ascii(o.toString());
                FileOutputStream f = new FileOutputStream(logFile(c), true);
                try {
                    f.write((line + "\n").getBytes("UTF-8"));
                } finally {
                    f.close();
                }
                Log.i(TAG, line);
            } catch (Exception e) {
                Log.e(TAG, "log failed", e);
            }
        }
    }

    static List<String> readLog(Context c) {
        synchronized (LOCK) {
            List<String> lines = new ArrayList<String>();
            File f = logFile(c);
            if (!f.exists()) return lines;
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                try {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (l.length() > 0) lines.add(l);
                    }
                } finally {
                    r.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "read log failed", e);
            }
            return lines;
        }
    }

    static void clearLog(Context c) {
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            logFile(c).delete();
        }
    }

    // ---- misc ------------------------------------------------------------

    static int bump(Context c, String key) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            int v = p.getInt(key, 0) + 1;
            p.edit().putInt(key, v).commit();
            return v;
        }
    }

    static String artUri(Context c, String file) {
        return "content://" + c.getPackageName() + ".art/" + file;
    }

    static boolean hasTvProvider(Context c) {
        try {
            return c.getPackageManager().resolveContentProvider(TV_AUTHORITY, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** FLAG_IMMUTABLE everywhere except where the system must fill in the intent. */
    static int piFlags(boolean mutable) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (mutable) {
            if (Build.VERSION.SDK_INT >= 31) f |= PendingIntent.FLAG_MUTABLE;
        } else {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return f;
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Current signing certificate SHA-256 of a package (lowercase hex), or null. */
    @SuppressWarnings("deprecation")
    static String certSha256(Context c, String pkg) {
        if (pkg == null) return null;
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pi.signingInfo;
                if (si == null) return null;
                if (si.hasMultipleSigners()) {
                    sigs = si.getApkContentsSigners();
                } else {
                    Signature[] h = si.getSigningCertificateHistory();
                    sigs = new Signature[]{h[h.length - 1]};
                }
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            return sha256(sigs[0].toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject bundleToJson(Bundle b) {
        if (b == null) return null;
        JSONObject o = new JSONObject();
        for (String k : b.keySet()) {
            Object v = b.get(k);
            if (v instanceof Bundle) v = bundleToJson((Bundle) v);
            else if (v instanceof String[]) {
                JSONArray a = new JSONArray();
                for (String s : (String[]) v) a.put(s);
                v = a;
            } else if (v != null && !(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof String)) {
                v = String.valueOf(v);
            }
            put(o, k, v);
        }
        return o;
    }

    static JSONObject err(Throwable t) {
        return j("exception", t.getClass().getName(), "message", t.getMessage());
    }

    static Class<?> widgetClass(String simpleName) throws ClassNotFoundException {
        return Class.forName(simpleName.contains(".") ? simpleName : NS + simpleName);
    }
}
