package com.yunyu697.gazeline;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Phone-side SMB endpoint: one server, one share, one account (§4.2 keeps the
 * POC to a single server). The password stays in app-private preferences and
 * is never written into the HUD, subtitles or exported logs.
 */
final class SmbSettings {

    static final String SCHEME = "smb://";
    private static final String PREFS = "gazeline_smb";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_SHARE = "share";
    private static final String KEY_PATH = "path";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "password";
    private static final String KEY_PROJECTION = "projection";

    String host = "";
    int port = 445;
    String share = "";
    String path = "";
    String user = "";
    String password = "";
    /** Last projection picked in the library: "360" or "sbs". */
    String projection = "360";

    static SmbSettings load(Context context) {
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SmbSettings s = new SmbSettings();
        s.host = p.getString(KEY_HOST, "");
        s.port = p.getInt(KEY_PORT, 445);
        s.share = p.getString(KEY_SHARE, "");
        s.path = p.getString(KEY_PATH, "");
        s.user = p.getString(KEY_USER, "");
        s.password = p.getString(KEY_PASS, "");
        s.projection = p.getString(KEY_PROJECTION, "360");
        return s;
    }

    void save(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port > 0 ? port : 445)
                .putString(KEY_SHARE, share)
                .putString(KEY_PATH, path)
                .putString(KEY_USER, user)
                .putString(KEY_PASS, password)
                .putString(KEY_PROJECTION, projection)
                .apply();
    }

    boolean isConfigured() {
        return host.length() > 0 && share.length() > 0 && user.length() > 0;
    }

    String summary() {
        if (!isConfigured()) {
            return "未配置";
        }
        String dir = path.length() == 0 ? "" : "/" + path;
        return user + "@" + host + "/" + share + dir;
    }

    /** Builds smb://host/share/path; the credentials are read from prefs. */
    String url(String relativePath) {
        String p = relativePath == null ? "" : relativePath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return SCHEME + host + "/" + share + (p.length() == 0 ? "" : "/" + p);
    }

    static boolean isSmb(String source) {
        return source != null && source.startsWith(SCHEME);
    }

    /** Splits an smb:// URL into {host, share, path}; null when malformed. */
    static String[] splitUrl(String url) {
        if (!isSmb(url)) {
            return null;
        }
        String rest = url.substring(SCHEME.length());
        int first = rest.indexOf('/');
        if (first <= 0) {
            return null;
        }
        String host = rest.substring(0, first);
        int second = rest.indexOf('/', first + 1);
        String share = second < 0 ? rest.substring(first + 1) : rest.substring(first + 1, second);
        String path = second < 0 ? "" : rest.substring(second + 1);
        if (share.length() == 0) {
            return null;
        }
        return new String[]{host, share, path};
    }
}
