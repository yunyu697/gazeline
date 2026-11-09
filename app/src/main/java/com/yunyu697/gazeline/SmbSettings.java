package com.yunyu697.gazeline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SMB endpoints. Every server lives in its own named record, so hopping
 * between the NAS at home and the one at the office is one tap. The flat
 * fields (host … password) always mirror the active record, which keeps every
 * existing call site unchanged. The password stays in app-private
 * preferences and is never written into the HUD, subtitles or exported logs.
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
    private static final String KEY_ANONYMOUS = "anonymous";
    private static final String KEY_AUTO_RECONNECT = "autoReconnect";
    private static final String KEY_PROJECTION = "projection";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE = "active";

    /** Name given to the record migrated from the 1.0.x single endpoint. */
    private static final String MIGRATED_NAME = "默认服务器";

    /** One saved server: the name shown in lists plus its endpoint and account. */
    static final class Profile {
        String name = "";
        String host = "";
        int port = 445;
        String share = "";
        String path = "";
        String user = "";
        String password = "";
        String projection = "360";
        boolean anonymous;
        boolean autoReconnect = true;

        boolean isConfigured() {
            return host.length() > 0 && share.length() > 0
                    && (user.length() > 0 || anonymous);
        }

        String summary() {
            String dir = path.length() == 0 ? "" : "/" + path;
            return user + "@" + host + "/" + share + dir;
        }
    }

    String host = "";
    int port = 445;
    String share = "";
    String path = "";
    String user = "";
    String password = "";
    /** Guest login: the account and password fields are ignored when set. */
    boolean anonymous;
    /** Reconnect to the active record when the library opens. */
    boolean autoReconnect = true;
    /** Last projection picked in the library: "360" or "sbs". */
    String projection = "360";
    /** Name of the record the flat fields were loaded from; "" when unsaved. */
    String profileName = "";

    static SmbSettings load(Context context) {
        SharedPreferences p = prefs(context);
        SmbSettings s = new SmbSettings();
        s.host = p.getString(KEY_HOST, "");
        s.port = p.getInt(KEY_PORT, 445);
        s.share = p.getString(KEY_SHARE, "");
        s.path = p.getString(KEY_PATH, "");
        s.user = p.getString(KEY_USER, "");
        s.password = p.getString(KEY_PASS, "");
        s.anonymous = p.getBoolean(KEY_ANONYMOUS, false);
        s.autoReconnect = p.getBoolean(KEY_AUTO_RECONNECT, true);
        s.projection = p.getString(KEY_PROJECTION, "360");
        s.profileName = p.getString(KEY_ACTIVE, "");
        if (!p.contains(KEY_PROFILES) && s.isConfigured()) {
            // 1.0.x stored a single endpoint: promote it to the first record.
            List<Profile> list = new ArrayList<>();
            list.add(s.toProfile(MIGRATED_NAME));
            writeProfiles(context, list);
            s.profileName = MIGRATED_NAME;
            p.edit().putString(KEY_ACTIVE, s.profileName).apply();
        }
        return s;
    }

    /** Persists the flat fields and mirrors them into the active record. */
    void save(Context context) {
        writeCurrent(context);
        if (profileName.length() == 0) {
            return;
        }
        List<Profile> list = profiles(context);
        int index = indexOf(list, profileName);
        Profile p = toProfile(profileName);
        if (index < 0) {
            list.add(p);
        } else {
            list.set(index, p);
        }
        writeProfiles(context, list);
    }

    /**
     * Saves the form as a named record and makes it active. A blank name falls
     * back to the share (or host), numbered when it collides with an existing
     * record. Renaming the record that was being edited replaces it instead of
     * leaving a duplicate behind.
     */
    void saveProfile(Context context, String name) {
        String wanted = name == null ? "" : name.trim();
        List<Profile> list = profiles(context);
        if (wanted.length() == 0) {
            wanted = suggestName(list);
        }
        if (profileName.length() > 0 && !wanted.equals(profileName)) {
            int old = indexOf(list, profileName);
            if (old >= 0) {
                list.remove(old);
                writeProfiles(context, list);
            }
        }
        profileName = wanted;
        save(context);
    }

    /** Copies a saved record into the flat fields and makes it active. */
    boolean selectProfile(Context context, String name) {
        List<Profile> list = profiles(context);
        int index = indexOf(list, name);
        if (index < 0) {
            return false;
        }
        Profile p = list.get(index);
        host = p.host;
        port = p.port;
        share = p.share;
        path = p.path;
        user = p.user;
        password = p.password;
        anonymous = p.anonymous;
        autoReconnect = p.autoReconnect;
        projection = p.projection;
        profileName = p.name;
        writeCurrent(context);
        return true;
    }

    boolean deleteProfile(Context context, String name) {
        List<Profile> list = profiles(context);
        int index = indexOf(list, name);
        if (index < 0) {
            return false;
        }
        list.remove(index);
        writeProfiles(context, list);
        if (name.equals(profileName)) {
            profileName = "";
            prefs(context).edit().remove(KEY_ACTIVE).apply();
        }
        return true;
    }

    static List<Profile> profiles(Context context) {
        String raw = prefs(context).getString(KEY_PROFILES, "");
        List<Profile> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Profile p = new Profile();
                p.name = o.optString("name", "").trim();
                p.host = o.optString("host", "");
                p.port = o.optInt("port", 445);
                p.share = o.optString("share", "");
                p.path = o.optString("path", "");
                p.user = o.optString("user", "");
                p.password = o.optString("password", "");
                p.anonymous = o.optBoolean("anonymous", false);
                p.autoReconnect = o.optBoolean("autoReconnect", true);
                p.projection = o.optString("projection", "360");
                if (p.name.length() > 0) {
                    out.add(p);
                }
            }
        } catch (Throwable ignored) {
            // A corrupt list is treated as "no records" rather than crashing.
        }
        return out;
    }

    /** Never render an empty name in the UI. */
    static String profileLabel(String name) {
        return name == null || name.length() == 0 ? "未命名服务器" : name;
    }

    boolean isConfigured() {
        return host.length() > 0 && share.length() > 0
                && (user.length() > 0 || anonymous);
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

    // ------------------------------------------------------------ internals

    private Profile toProfile(String name) {
        Profile p = new Profile();
        p.name = name;
        p.host = host;
        p.port = port > 0 ? port : 445;
        p.share = share;
        p.path = path;
        p.user = user;
        p.password = password;
        p.anonymous = anonymous;
        p.autoReconnect = autoReconnect;
        p.projection = projection;
        return p;
    }

    private static int indexOf(List<Profile> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private String suggestName(List<Profile> list) {
        String base = share.length() > 0 ? share : host;
        if (base.length() == 0) {
            base = "服务器";
        }
        String name = base;
        int n = 2;
        while (indexOf(list, name) >= 0) {
            name = base + " " + n;
            n++;
        }
        return name;
    }

    private void writeCurrent(Context context) {
        prefs(context).edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port > 0 ? port : 445)
                .putString(KEY_SHARE, share)
                .putString(KEY_PATH, path)
                .putString(KEY_USER, user)
                .putString(KEY_PASS, password)
                .putBoolean(KEY_ANONYMOUS, anonymous)
                .putBoolean(KEY_AUTO_RECONNECT, autoReconnect)
                .putString(KEY_PROJECTION, projection)
                .putString(KEY_ACTIVE, profileName)
                .apply();
    }

    private static void writeProfiles(Context context, List<Profile> list) {
        JSONArray arr = new JSONArray();
        for (Profile p : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", p.name);
                o.put("host", p.host);
                o.put("port", p.port > 0 ? p.port : 445);
                o.put("share", p.share);
                o.put("path", p.path);
                o.put("user", p.user);
                o.put("password", p.password);
                o.put("anonymous", p.anonymous);
                o.put("autoReconnect", p.autoReconnect);
                o.put("projection", p.projection);
                arr.put(o);
            } catch (Throwable ignored) {
                // Skip a record that cannot be serialised.
            }
        }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
