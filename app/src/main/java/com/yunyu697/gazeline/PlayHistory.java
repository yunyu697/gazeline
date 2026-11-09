package com.yunyu697.gazeline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Watch history and resume positions (§ new in 1.1). The same store drives the
 * phone list and the in-VR browser, so a film started on the phone resumes at
 * the same second inside the headset. Entries are keyed by their source
 * (content://, file path or smb:// URL) and capped to a short recent list.
 */
final class PlayHistory {

    private static final String PREFS = "gazeline_history";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ENTRIES = 12;
    private static final long MIN_RESUME_MS = 3000L;

    static final class Entry {
        String source = "";
        String name = "";
        String mode = "360";
        long positionMs;
        long durationMs;
        long updatedAt;

        boolean resumable() {
            return positionMs >= MIN_RESUME_MS
                    && (durationMs <= 0 || positionMs < durationMs - 5000L);
        }

        long startAt() {
            return resumable() ? positionMs : 0L;
        }
    }

    private PlayHistory() {
    }

    static List<Entry> load(Context context) {
        SharedPreferences p = prefs(context);
        String raw = p.getString(KEY_ITEMS, "");
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.source = o.optString("s", "");
                e.name = o.optString("n", "");
                e.mode = o.optString("m", "360");
                e.positionMs = o.optLong("p", 0L);
                e.durationMs = o.optLong("d", 0L);
                e.updatedAt = o.optLong("t", 0L);
                if (e.source.length() > 0) {
                    out.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    static Entry find(Context context, String source) {
        if (source == null) {
            return null;
        }
        for (Entry e : load(context)) {
            if (e.source.equals(source)) {
                return e;
            }
        }
        return null;
    }

    static long positionFor(Context context, String source) {
        Entry e = find(context, source);
        return e == null ? 0L : e.startAt();
    }

    /**
     * Upserts the entry. Called every few seconds while playing, so it rewrites
     * the small JSON list rather than doing partial edits.
     */
    static void record(Context context, String source, String name, String mode,
                       long positionMs, long durationMs) {
        if (source == null || source.length() == 0) {
            return;
        }
        List<Entry> entries = load(context);
        Entry target = null;
        for (Entry e : entries) {
            if (e.source.equals(source)) {
                target = e;
                break;
            }
        }
        if (target == null) {
            target = new Entry();
            target.source = source;
            entries.add(target);
        }
        target.name = name == null ? "" : name;
        target.mode = mode == null ? "360" : mode;
        target.durationMs = Math.max(0L, durationMs);
        if (durationMs > 0 && positionMs >= durationMs - 5000L) {
            target.positionMs = 0L;
        } else {
            target.positionMs = Math.max(0L, positionMs);
        }
        target.updatedAt = System.currentTimeMillis();
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save(context, entries);
    }

    static void clear(Context context) {
        prefs(context).edit().remove(KEY_ITEMS).apply();
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry e : entries) {
            try {
                JSONObject o = new JSONObject();
                o.put("s", e.source);
                o.put("n", e.name);
                o.put("m", e.mode);
                o.put("p", e.positionMs);
                o.put("d", e.durationMs);
                o.put("t", e.updatedAt);
                array.put(o);
            } catch (Throwable ignored) {
            }
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
