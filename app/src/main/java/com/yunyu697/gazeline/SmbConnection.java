package com.yunyu697.gazeline;

import android.util.Log;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One SMB2/3 session against a single share. Signing is mandatory because
 * Windows 11 requires it by default; the dialect list starts at SMB 2.0.2 so
 * SMB1 is never attempted (§4.1.2).
 */
final class SmbConnection implements Closeable {

    private static final String TAG = "GazelineSmb";

    static final class Entry {
        final String name;
        final String path;
        final boolean directory;
        final long size;

        Entry(String name, String path, boolean directory, long size) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
        }
    }

    private final SMBClient client;
    private final Connection connection;
    private final Session session;
    private final DiskShare share;

    private SmbConnection(SMBClient client, Connection connection, Session session, DiskShare share) {
        this.client = client;
        this.connection = connection;
        this.session = session;
        this.share = share;
    }

    static SmbConnection connect(String host, int port, String shareName,
                                 String user, String password) throws IOException {
        return connect(host, port, shareName, user, password, false);
    }

    static SmbConnection connect(String host, int port, String shareName,
                                 String user, String password,
                                 boolean anonymous) throws IOException {
        SmbConfig config = SmbConfig.builder()
                .withSigningRequired(true)
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(20, TimeUnit.SECONDS)
                .build();
        SMBClient client = new SMBClient(config);
        Connection connection = null;
        Session session = null;
        DiskShare share = null;
        try {
            connection = client.connect(host, port);
            AuthenticationContext auth = anonymous
                    ? AuthenticationContext.guest()
                    : new AuthenticationContext(user, password.toCharArray(), null);
            session = connection.authenticate(auth);
            share = (DiskShare) session.connectShare(shareName);
            Log.i(TAG, "connected " + host + ":" + port + " share=" + shareName
                    + " signing=" + session.isSigningRequired());
            return new SmbConnection(client, connection, session, share);
        } catch (IOException | RuntimeException e) {
            closeQuietly(share, session, connection, client);
            throw e;
        }
    }

    /** Short label for the negotiated dialect, shown in the connection card. */
    String dialectName() {
        try {
            switch (connection.getNegotiatedProtocol().getDialect()) {
                case SMB_2_0_2:
                    return "SMB2";
                case SMB_2_1:
                    return "SMB2.1";
                case SMB_3_0:
                    return "SMB3";
                case SMB_3_0_2:
                    return "SMB3.0.2";
                case SMB_3_1_1:
                    return "SMB3.1.1";
                default:
                    return "SMB";
            }
        } catch (Throwable t) {
            return "SMB";
        }
    }

    /** Lists one directory; entries with no name are skipped. */
    List<Entry> list(String directory) {
        String dir = normalize(directory);
        List<FileIdBothDirectoryInformation> infos = share.list(dir);
        List<Entry> out = new ArrayList<>(infos.size());
        for (FileIdBothDirectoryInformation info : infos) {
            String name = info.getFileName();
            if (name == null || name.length() == 0 || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            boolean isDir = (info.getFileAttributes()
                    & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
            String child = dir.length() == 0 ? name : dir + "/" + name;
            out.add(new Entry(name, child, isDir, info.getEndOfFile()));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    File openFile(String path) {
        return share.openFile(normalize(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null);
    }

    private static String normalize(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    @Override
    public void close() {
        closeQuietly(share, session, connection, client);
    }

    private static void closeQuietly(DiskShare share, Session session,
                                     Connection connection, SMBClient client) {
        if (share != null) {
            try {
                share.close();
            } catch (Throwable ignored) {
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
        }
        if (client != null) {
            try {
                client.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
