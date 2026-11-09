package com.yunyu697.gazeline;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal SRT / WebVTT parser. Cues are kept in source order and looked up by
 * the decoder position each frame; multiline cue text is joined with '\n' and
 * wrapped again by HudView for the eye viewport.
 */
final class SubtitleTrack {

    private static final Pattern TIME = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[\\.,](\\d{3})");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    static final class Cue {
        final long startMs;
        final long endMs;
        final String text;

        Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private final List<Cue> cues;
    private final String name;

    private SubtitleTrack(List<Cue> cues, String name) {
        this.cues = cues;
        this.name = name;
    }

    int cueCount() {
        return cues.size();
    }

    String name() {
        return name;
    }

    /** Cue text covering the position, or "" between cues. */
    String textAt(long positionMs) {
        int lo = 0;
        int hi = cues.size() - 1;
        int hit = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs <= positionMs) {
                hit = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (hit < 0) {
            return "";
        }
        Cue cue = cues.get(hit);
        return positionMs <= cue.endMs ? cue.text : "";
    }

    static SubtitleTrack load(Context context, String source) throws Exception {
        InputStream input;
        if (source.startsWith("content://")) {
            input = context.getContentResolver().openInputStream(Uri.parse(source));
        } else {
            String path = source.startsWith("file://") ? Uri.parse(source).getPath() : source;
            input = new FileInputStream(path);
        }
        if (input == null) {
            throw new IOExceptionCompat("无法打开字幕文件");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > 2 * 1024 * 1024) {
                    throw new IOExceptionCompat("字幕文件超过 2 MB");
                }
                sb.append(line).append('\n');
            }
            String displayName = source.startsWith("content://")
                    ? "已选字幕" : new File(source.startsWith("file://")
                    ? Uri.parse(source).getPath() : source).getName();
            return parse(sb.toString(), displayName);
        }
    }

    static SubtitleTrack parse(String raw, String name) {
        String normalized = raw;
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        List<Cue> cues = new ArrayList<>();
        String[] blocks = normalized.split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            int arrow = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    arrow = i;
                    break;
                }
            }
            if (arrow < 0) {
                continue;
            }
            String timing = lines[arrow];
            int split = timing.indexOf("-->");
            Matcher m1 = TIME.matcher(timing.substring(0, split));
            Matcher m2 = TIME.matcher(timing.substring(split + 3));
            if (!m1.find() || !m2.find()) {
                continue;
            }
            long start = millisOf(m1);
            long end = millisOf(m2);
            if (end < start) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int i = arrow + 1; i < lines.length; i++) {
                String t = TAG.matcher(lines[i]).replaceAll("").trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(t);
            }
            if (text.length() > 0) {
                cues.add(new Cue(start, end, text.toString()));
            }
        }
        return new SubtitleTrack(cues, name);
    }

    /** Sidecar lookup for plain file paths (debug/USB playback). */
    static String findSidecar(String videoSource) {
        if (videoSource == null || videoSource.startsWith("content://")) {
            return null;
        }
        String path = videoSource.startsWith("file://")
                ? Uri.parse(videoSource).getPath() : videoSource;
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot <= slash) {
            return null;
        }
        String base = path.substring(0, dot);
        String[] extensions = {".srt", ".SRT", ".vtt", ".VTT"};
        for (String ext : extensions) {
            File candidate = new File(base + ext);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private static long millisOf(Matcher m) {
        String hours = m.group(1);
        int h = hours == null ? 0 : Integer.parseInt(hours);
        int min = Integer.parseInt(m.group(2));
        int sec = Integer.parseInt(m.group(3));
        int ms = Integer.parseInt(m.group(4));
        return ((long) h * 3600 + (long) min * 60 + sec) * 1000L + ms;
    }

    /** Thrown when the subtitle cannot be read; keeps the API free of java.io.IO*. */
    static final class IOExceptionCompat extends Exception {
        IOExceptionCompat(String message) {
            super(message);
        }
    }

    static String describe(String source) {
        if (source == null) {
            return "";
        }
        String path = source.startsWith("file://") ? Uri.parse(source).getPath() : source;
        String lower = (path == null ? source : path).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtt")) {
            return "VTT";
        }
        if (lower.endsWith(".srt")) {
            return "SRT";
        }
        return "字幕";
    }
}
