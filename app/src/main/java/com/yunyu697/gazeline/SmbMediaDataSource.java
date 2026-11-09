package com.yunyu697.gazeline;

import android.media.MediaDataSource;
import android.util.Log;

import com.hierynomus.smbj.share.File;

import java.io.IOException;

/**
 * Random-access MediaDataSource over one open SMB file. MediaExtractor and the
 * audio extractor both read this source at arbitrary offsets, so every call is
 * serialized on a single SMB2 file handle.
 */
final class SmbMediaDataSource extends MediaDataSource {

    private static final String TAG = "GazelineSmb";

    private final SmbConnection connection;
    private final File file;
    private final long size;
    private final String label;
    private volatile boolean closed;

    private SmbMediaDataSource(SmbConnection connection, File file, long size, String label) {
        this.connection = connection;
        this.file = file;
        this.size = size;
        this.label = label;
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
    public synchronized int readAt(long position, byte[] buffer, int offset, int length)
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
        int read = file.read(buffer, position, offset, want);
        if (read < 0) {
            return -1;
        }
        if (read == 0) {
            // The parser treats 0 as EOF; an empty SMB read means the range is
            // broken, so report it instead of silently truncating the file.
            throw new IOException("SMB 读取返回 0 字节");
        }
        return read;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            file.close();
        } catch (Throwable ignored) {
        }
        connection.close();
        Log.i(TAG, "closed " + label);
    }
}
