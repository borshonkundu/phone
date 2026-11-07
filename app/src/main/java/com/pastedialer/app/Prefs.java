package com.pastedialer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("pastedialer", Context.MODE_PRIVATE);
    }

    static Uri getTree(Context c) {
        String s = sp(c).getString("tree", null);
        return s == null ? null : Uri.parse(s);
    }

    static void setTree(Context c, Uri u) {
        sp(c).edit().putString("tree", u == null ? null : u.toString()).apply();
    }

    static boolean isAuto(Context c) {
        return sp(c).getBoolean("auto", false);
    }

    static void setAuto(Context c, boolean v) {
        sp(c).edit().putBoolean("auto", v).apply();
    }
}
