package com.yunyu697.gazeline;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Poster frames for the in-VR file grid. A browser page is eight files, so the
 * work is done on one background thread — never on the render thread — and the
 * results are kept in a small memory cache, which means a page revisited while
 * browsing appears instantly.
 *
 * <p>Local files and content URIs are read directly. SMB files are opened
 * through the same streaming source the player uses, so a poster costs the
 * moov atom plus the first key frame rather than the whole file.
 */
final class Thumbnails {

    private static final int MAX_WIDTH = 360;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(32);

    private static final ExecutorService POOL =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "gazeline-thumbs");
                    t.setDaemon(true);
                    return t;
                }
            });

    private Thumbnails() {
    }

    /** Cached poster, or null while it is still being decoded. */
    static Bitmap peek(String key) {
        return key == null ? null : CACHE.get(key);
    }

    static void load(final Context context, final String source, final Runnable done) {
        if (source == null || source.length() == 0) {
            return;
        }
        if (CACHE.get(source) != null) {
            done.run();
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = decode(context.getApplicationContext(), source);
                if (bmp != null) {
                    CACHE.put(source, bmp);
                }
                if (done != null) {
                    done.run();
                }
            }
        });
    }

    private static Bitmap decode(Context context, String source) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaDataSource smb = null;
        try {
            if (source.startsWith("smb://")) {
                smb = SmbMediaDataSource.open(SmbSettings.load(context), source);
                retriever.setDataSource(smb);
            } else if (source.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(source));
            } else {
                retriever.setDataSource(source);
            }
            Bitmap frame = retriever.getFrameAtTime(-1,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return frame == null ? null : shrink(frame);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
            if (smb != null) {
                try {
                    smb.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Bitmap shrink(Bitmap frame) {
        if (frame.getWidth() <= MAX_WIDTH) {
            return frame;
        }
        int w = MAX_WIDTH;
        int h = Math.max(1, frame.getHeight() * MAX_WIDTH / frame.getWidth());
        Bitmap scaled = Bitmap.createScaledBitmap(frame, w, h, true);
        if (scaled != frame) {
            frame.recycle();
        }
        return scaled;
    }
}
