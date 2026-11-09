package com.yunyu697.gazeline;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Starred SMB folders. Keys carry the endpoint (host/share) so the same
 * folder name on two servers never mixes up, while the UI keeps showing the
 * plain relative path. Stored as a string set so one tap in the phone browser
 * or the VR browser jumps straight into a folder.
 */
final class SmbFavorites {

    private static final String PREFS = "gazeline_smb_favorites";
    /** 1.0.x keys: a bare relative path with no server attached. */
    private static final String KEY_LEGACY = "paths";
    private static final String KEY_PATHS = "paths_v2";

    private SmbFavorites() {
    }

    /** Directories visible for the active server, as plain relative paths. */
    static List<String> list(Context context, SmbSettings settings) {
        List<String> out = new ArrayList<>();
        String scope = scope(settings);
        for (String key : load(context, settings)) {
            if (key.startsWith(scope)) {
                out.add(key.substring(scope.length()));
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static boolean contains(Context context, SmbSettings settings, String dir) {
        return load(context, settings).contains(fullKey(settings, dir));
    }

    static boolean toggle(Context context, SmbSettings settings, String dir) {
        String key = fullKey(settings, dir);
        Set<String> paths = load(context, settings);
        if (!paths.remove(key)) {
            paths.add(key);
        }
        save(context, paths);
        return paths.contains(key);
    }

    static void remove(Context context, SmbSettings settings, String dir) {
        Set<String> paths = load(context, settings);
        if (paths.remove(fullKey(settings, dir))) {
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

    private static String scope(SmbSettings settings) {
        return settings.host + "/" + settings.share + "/";
    }

    private static String fullKey(SmbSettings settings, String dir) {
        return scope(settings) + normalize(dir);
    }

    private static Set<String> load(Context context, SmbSettings settings) {
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> paths = new HashSet<>(p.getStringSet(KEY_PATHS, Collections.<String>emptySet()));
        Set<String> legacy = p.getStringSet(KEY_LEGACY, null);
        if (legacy != null && !legacy.isEmpty()) {
            // Attach the 1.0.x bare paths to the server configured right now;
            // that is the only server they could have come from.
            String scope = scope(settings);
            for (String old : legacy) {
                paths.add(scope + normalize(old));
            }
            save(context, paths);
            p.edit().remove(KEY_LEGACY).apply();
        }
        return paths;
    }

    private static void save(Context context, Set<String> paths) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PATHS, new HashSet<>(paths))
                .apply();
    }
}
