package com.yunyu697.gazeline;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Starred SMB folders (paths relative to the single configured share). Stored
 * as a string set so one tap in the phone browser or the VR browser jumps
 * straight into a folder.
 */
final class SmbFavorites {

    private static final String PREFS = "gazeline_smb_favorites";
    private static final String KEY_PATHS = "paths";

    private SmbFavorites() {
    }

    static List<String> list(Context context) {
        List<String> out = new ArrayList<>(load(context));
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static boolean contains(Context context, String dir) {
        return load(context).contains(normalize(dir));
    }

    static boolean toggle(Context context, String dir) {
        String path = normalize(dir);
        Set<String> paths = load(context);
        if (!paths.remove(path)) {
            paths.add(path);
        }
        save(context, paths);
        return paths.contains(path);
    }

    static void remove(Context context, String dir) {
        Set<String> paths = load(context);
        if (paths.remove(normalize(dir))) {
            save(context, paths);
        }
    }

    static String displayName(String dir) {
        String path = normalize(dir);
        if (path.length() == 0) {
            return "共享根目录";
        }
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.length() == 0 ? path : name;
    }

    static String normalize(String dir) {
        String p = dir == null ? "" : dir.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static Set<String> load(Context context) {
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(p.getStringSet(KEY_PATHS, Collections.<String>emptySet()));
    }

    private static void save(Context context, Set<String> paths) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PATHS, new HashSet<>(paths))
                .apply();
    }
}
