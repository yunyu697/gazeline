package com.yunyu697.gazeline;

import android.media.MediaDataSource;
import android.util.Log;

import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;

/**
 * Random-access MediaDataSource over one open SMB file.
 *
 * SMB2 charges about one network round trip per read, and MediaExtractor issues
 * a lot of small reads (container headers, cue entries, then per-sample
 * payloads) from two threads at once. Reading straight through turned 4K/360
 * streams into a request storm that ran far below the network's real
 * throughput. Requests are therefore served from a block cache that fetches
 * 256 KB at a time and keeps a couple of megabytes read ahead on worker
 * threads, so playback mostly hits memory instead of waiting on Wi-Fi.
 */
final class SmbMediaDataSource extends MediaDataSource {

    private static final String TAG = "GazelineSmb";

    /** Size of one cached block, which is also one SMB2 read request. */
    private static final int BLOCK = 256 * 1024;
    /** Blocks kept in memory: 12 MB, roughly two seconds of a 4K/360 stream. */
    private static final int WINDOW = 48;
    /** Blocks kept queued ahead of the reader: 4 MB of cushion against Wi-Fi jitter. */
    private static final int PREFETCH = 16;
    /** How often a throughput line is logged for real-device diagnosis. */
    private static final long STATS_EVERY_MS = 5000;

    private final SmbConnection connection;
    private final File file;
    private final long size;
    private final String label;

    private final Object lock = new Object();
    private final Map<Long, FutureTask<byte[]>> pending = new HashMap<>();
    private final LinkedHashMap<Long, byte[]> cache =
            new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                    return size() > WINDOW;
                }
            };
    private final ExecutorService loader;

    private volatile boolean closed;
    private long fetchedBytes;
    private long fetchedBlocks;
    private long cacheHits;
    private long lastStatsMs;

    private SmbMediaDataSource(SmbConnection connection, File file, long size, String label) {
        this.connection = connection;
        this.file = file;
        this.size = size;
        this.label = label;
        this.loader = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "smb-read");
                t.setDaemon(true);
                return t;
            }
        });
        this.lastStatsMs = android.os.SystemClock.elapsedRealtime();
    }

    static SmbMediaDataSource open(SmbSettings settings, String url) throws IOException {
        String[] parts = SmbSettings.splitUrl(url);
        if (parts == null) {
            throw new IOException("SMB 地址无效");
        }
        SmbConnection connection = SmbConnection.connect(parts[0], settings.port, parts[1],
                settings.user, settings.password);
        try {
            File file = connection.openFile(parts[2]);
            long size = file.getLength();
            Log.i(TAG, "open " + parts[1] + "/" + parts[2] + " size=" + size);
            return new SmbMediaDataSource(connection, file, size, parts[1] + "/" + parts[2]);
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    String label() {
        return label;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int length)
            throws IOException {
        if (closed) {
            throw new IOException("SMB 源已关闭");
        }
        if (position >= size) {
            return -1;
        }
        if (length <= 0) {
            return 0;
        }
        int want = (int) Math.min(length, size - position);
        int done = 0;
        while (done < want) {
            long p = position + done;
            long start = p - (p % BLOCK);
            byte[] block = block(start);
            long within = p - start;
            if (within >= block.length) {
                // Short tail block: the file ended before the advertised size.
                break;
            }
            int n = (int) Math.min(want - done, block.length - within);
            System.arraycopy(block, (int) within, buffer, offset + done, n);
            done += n;
            prefetchAfter(start);
        }
        logStats();
        return done == 0 ? -1 : done;
    }

    // ------------------------------------------------------------ block cache

    /**
     * Returns the block that starts at {@code start}. Missing blocks are read
     * on the calling thread so the very first request has minimal latency;
     * blocks already queued by the prefetcher are awaited instead of being
     * requested twice.
     */
    private byte[] block(long start) throws IOException {
        FutureTask<byte[]> task;
        boolean owner = false;
        synchronized (lock) {
            byte[] hit = cache.get(start);
            if (hit != null) {
                cacheHits++;
                return hit;
            }
            task = pending.get(start);
            if (task == null) {
                task = new BlockTask(start);
                pending.put(start, task);
                owner = true;
            }
        }
        if (owner) {
            task.run();
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SMB 读取被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(cause == null ? "SMB 读取失败" : cause.getMessage(), cause);
        }
    }

    private void prefetchAfter(long servedStart) {
        for (int i = 1; i <= PREFETCH; i++) {
            long start = servedStart + (long) i * BLOCK;
            if (start >= size) {
                return;
            }
            submit(start);
        }
    }

    private void submit(final long start) {
        FutureTask<byte[]> task;
        synchronized (lock) {
            if (closed || cache.containsKey(start) || pending.containsKey(start)) {
                return;
            }
            task = new BlockTask(start);
            pending.put(start, task);
        }
        loader.execute(task);
    }

    /** Reads one block, tolerating short responses from the server. */
    private byte[] loadBlock(long start) throws IOException {
        if (closed) {
            throw new IOException("SMB 源已关闭");
        }
        int want = (int) Math.min(BLOCK, size - start);
        byte[] data = new byte[want];
        int done = 0;
        while (done < want) {
            if (closed) {
                throw new IOException("SMB 源已关闭");
            }
            int read = file.read(data, start + done, done, want - done);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("SMB 读取返回 0 字节");
            }
            done += read;
        }
        if (done < data.length) {
            byte[] tail = new byte[done];
            System.arraycopy(data, 0, tail, 0, done);
            data = tail;
        }
        synchronized (lock) {
            fetchedBytes += data.length;
            fetchedBlocks++;
        }
        return data;
    }

    /** Caches finished loads, whether they came from a reader or the prefetcher. */
    private final class BlockTask extends FutureTask<byte[]> {
        private final long start;

        BlockTask(final long start) {
            super(new Callable<byte[]>() {
                @Override
                public byte[] call() throws Exception {
                    return loadBlock(start);
                }
            });
            this.start = start;
        }

        @Override
        protected void done() {
            byte[] data = null;
            boolean ok = false;
            try {
                data = get();
                ok = true;
            } catch (Throwable ignored) {
                // A failed prefetch simply leaves the block uncached.
            }
            synchronized (lock) {
                pending.remove(start);
                if (ok && !closed) {
                    cache.put(start, data);
                }
            }
        }
    }

    private void logStats() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastStatsMs < STATS_EVERY_MS) {
            return;
        }
        long bytes;
        long blocks;
        long hits;
        synchronized (lock) {
            bytes = fetchedBytes;
            blocks = fetchedBlocks;
            hits = cacheHits;
        }
        Log.i(TAG, "stream " + label + " · " + (bytes / 1048576) + " MB / "
                + blocks + " 次读取 · 命中 " + hits);
        lastStatsMs = now;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        loader.shutdownNow();
        synchronized (lock) {
            pending.clear();
            cache.clear();
        }
        try {
            file.close();
        } catch (Throwable ignored) {
        }
        connection.close();
        Log.i(TAG, "closed " + label);
    }
}
