package com.yunyu697.gazeline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK_VIDEO = 1001;
    private static final int REQ_PLAY = 1002;
    private static final int REQ_CALIB = 1003;
    private static final int REQ_PICK_SUBTITLE = 1004;

    private StageView stage;
    private boolean pendingSbs;
    private String pendingVideo;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable clearUndo = new Runnable() {
        @Override
        public void run() {
            stage.clearUndo();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        stage = new StageView(this);
        stage.setOnOpenVideoListener(new StageView.OnOpenVideoListener() {
            @Override
            public void onOpenVideo() {
                askMode();
            }
        });
        stage.setOnUndoPlayListener(new StageView.OnUndoPlayListener() {
            @Override
            public void onUndoPlay(String path, String mode, long positionMs) {
                ui.removeCallbacks(clearUndo);
                launchPlayer(path, mode, positionMs);
            }
        });
        stage.setOnStartCalibListener(new StageView.OnStartCalibListener() {
            @Override
            public void onStartCalib() {
                startActivityForResult(new Intent(MainActivity.this, CalibActivity.class),
                        REQ_CALIB);
            }
        });
        setContentView(stage);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                stage.showHud();
            }
        }, 1700);
    }

    private void askMode() {
        String[] sources = {"360° 全景 · 本地文件", "SBS 3D · 本地文件",
                "SMB 局域网片库", "SMB 配置"};
        new AlertDialog.Builder(this)
                .setTitle("选择片源")
                .setItems(sources, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0 || which == 1) {
                            pendingSbs = which == 1;
                            pickVideo();
                        } else if (which == 2) {
                            openSmbLibrary();
                        } else {
                            editSmbSettings(false);
                        }
                    }
                })
                .show();
    }

    private void openSmbLibrary() {
        SmbSettings settings = SmbSettings.load(this);
        if (!settings.isConfigured()) {
            editSmbSettings(true);
            return;
        }
        listSmb(settings, settings.path);
    }

    /** Pre-wear input: credentials stay on the phone (§4.2). */
    private void editSmbSettings(final boolean thenBrowse) {
        final SmbSettings s = SmbSettings.load(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final EditText host = input("服务器地址（如 192.168.1.10）", s.host, InputType.TYPE_CLASS_TEXT);
        final EditText share = input("共享名（如 Data1）", s.share, InputType.TYPE_CLASS_TEXT);
        final EditText dir = input("起始目录（可留空）", s.path, InputType.TYPE_CLASS_TEXT);
        final EditText user = input("账号", s.user, InputType.TYPE_CLASS_TEXT);
        final EditText pass = input("密码", s.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(host);
        box.addView(share);
        box.addView(dir);
        box.addView(user);
        box.addView(pass);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("SMB 配置")
                .setMessage("SMB2/3 · 强制签名 · 单服务器单共享。账号密码只保存在本机。")
                .setView(scroll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        s.host = valueOf(host);
                        s.share = valueOf(share);
                        s.path = valueOf(dir);
                        s.user = valueOf(user);
                        s.password = pass.getText().toString().trim();
                        s.port = 445;
                        s.save(MainActivity.this);
                        if (thenBrowse) {
                            listSmb(s, s.path);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void listSmb(final SmbSettings settings, final String dir) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在连接 SMB…");
        progress.setCancelable(false);
        progress.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SmbConnection.Entry> entries = null;
                String failure = null;
                try {
                    SmbConnection conn = SmbConnection.connect(settings.host, settings.port,
                            settings.share, settings.user, settings.password);
                    try {
                        entries = conn.list(dir);
                    } finally {
                        conn.close();
                    }
                } catch (Throwable t) {
                    failure = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final List<SmbConnection.Entry> result = entries;
                final String error = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.dismiss();
                        if (isFinishing()) {
                            return;
                        }
                        if (result == null) {
                            showSmbError(settings, dir, error);
                        } else {
                            showSmbEntries(settings, dir, result);
                        }
                    }
                });
            }
        }, "gazeline-smb-list").start();
    }

    private void showSmbError(final SmbSettings settings, final String dir, String message) {
        new AlertDialog.Builder(this)
                .setTitle("SMB 连接失败")
                .setMessage((message == null ? "未知错误" : message)
                        + "\n\n检查手机与电脑是否在同一网段、共享是否开启、账号密码是否正确、445 端口是否可达。")
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        listSmb(settings, dir);
                    }
                })
                .setNeutralButton("配置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        editSmbSettings(true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSmbEntries(final SmbSettings settings, final String dir,
                                List<SmbConnection.Entry> entries) {
        final List<String> labels = new ArrayList<>();
        final List<SmbConnection.Entry> targets = new ArrayList<>();
        final String parent = parentOf(dir);
        if (parent != null) {
            labels.add(".. 上一级");
            targets.add(null);
        }
        for (SmbConnection.Entry e : entries) {
            if (e.directory) {
                labels.add("[目录] " + e.name);
                targets.add(e);
            }
        }
        for (SmbConnection.Entry e : entries) {
            if (!e.directory && playable(e.name)) {
                labels.add("[视频] " + e.name + " · " + sizeText(e.size));
                targets.add(e);
            }
        }
        if (labels.isEmpty()) {
            labels.add("（此目录没有可播放的视频）");
            targets.add(null);
        }
        String where = dir.length() == 0 ? settings.share : settings.share + "/" + dir;
        new AlertDialog.Builder(this)
                .setTitle("SMB 片库 · " + where)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SmbConnection.Entry entry = targets.get(which);
                        if (entry == null) {
                            if (parent != null) {
                                listSmb(settings, parent);
                            }
                            return;
                        }
                        if (entry.directory) {
                            listSmb(settings, entry.path);
                        } else {
                            askSmbMode(settings.url(entry.path));
                        }
                    }
                })
                .setNeutralButton("配置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        editSmbSettings(false);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void askSmbMode(final String url) {
        String[] modes = {"360° 全景（等距柱状）", "SBS 3D（左右半宽）"};
        new AlertDialog.Builder(this)
                .setTitle("选择投影方式")
                .setItems(modes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pendingSbs = which == 1;
                        pendingVideo = url;
                        askSubtitle();
                    }
                })
                .show();
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PLAY) {
            if (resultCode == RESULT_OK && data != null) {
                String path = data.getStringExtra(PlayerActivity.EXTRA_PATH);
                String mode = data.getStringExtra(PlayerActivity.EXTRA_MODE);
                long position = data.getLongExtra(PlayerActivity.EXTRA_POSITION, 0L);
                if (path != null && path.length() > 0) {
                    stage.showUndo(path, mode == null ? "360" : mode, position);
                    ui.removeCallbacks(clearUndo);
                    ui.postDelayed(clearUndo, 5000);
                }
            }
            return;
        }
        if (requestCode == REQ_CALIB) {
            stage.reloadCalib();
            return;
        }
        if (requestCode == REQ_PICK_SUBTITLE) {
            String subtitle = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri subUri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(subUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                subtitle = subUri.toString();
            }
            if (pendingVideo != null) {
                launchPlayer(pendingVideo, pendingSbs ? "sbs" : "360", 0L, subtitle);
                pendingVideo = null;
            }
            return;
        }
        if (requestCode != REQ_PICK_VIDEO || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        pendingVideo = uri.toString();
        askSubtitle();
    }

    /** Optional, pre-wear step: pick a sidecar subtitle or start without one. */
    private void askSubtitle() {
        new AlertDialog.Builder(this)
                .setTitle("字幕（可选）")
                .setMessage("选择 .srt / .vtt 字幕文件，或直接开始播放。")
                .setPositiveButton("选择字幕", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pickSubtitle();
                    }
                })
                .setNegativeButton("跳过", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        launchPending(null);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        launchPending(null);
                    }
                })
                .show();
    }

    private void pickSubtitle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_SUBTITLE);
    }

    private void launchPending(String subtitle) {
        if (pendingVideo == null) {
            return;
        }
        launchPlayer(pendingVideo, pendingSbs ? "sbs" : "360", 0L, subtitle);
        pendingVideo = null;
    }

    private void launchPlayer(String path, String mode, long positionMs) {
        launchPlayer(path, mode, positionMs, null);
    }

    private void launchPlayer(String path, String mode, long positionMs, String subtitle) {
        Intent play = new Intent(this, PlayerActivity.class);
        play.putExtra(PlayerActivity.EXTRA_PATH, path);
        play.putExtra(PlayerActivity.EXTRA_MODE, mode);
        play.putExtra(PlayerActivity.EXTRA_SENSOR, true);
        if (positionMs > 0) {
            play.putExtra(PlayerActivity.EXTRA_POSITION, positionMs);
        }
        if (subtitle != null && subtitle.length() > 0) {
            play.putExtra(PlayerActivity.EXTRA_SUBTITLE, subtitle);
        }
        startActivityForResult(play, REQ_PLAY);
    }

    private EditText input(String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setInputType(inputType);
        field.setSingleLine(true);
        return field;
    }

    private static String valueOf(EditText field) {
        return field.getText().toString().trim();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String parentOf(String dir) {
        if (dir == null || dir.length() == 0) {
            return null;
        }
        int slash = dir.lastIndexOf('/');
        return slash < 0 ? "" : dir.substring(0, slash);
    }

    private static boolean playable(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov")
                || n.endsWith(".m4v") || n.endsWith(".webm") || n.endsWith(".ts")
                || n.endsWith(".avi");
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes >= 1073741824L) {
            return String.format(Locale.US, "%.2f GB", bytes / 1073741824f);
        }
        if (bytes >= 1048576L) {
            return String.format(Locale.US, "%.0f MB", bytes / 1048576f);
        }
        return String.format(Locale.US, "%.0f KB", bytes / 1024f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(clearUndo);
    }

    @Override
    public void onBackPressed() {
        if (!stage.back()) {
            super.onBackPressed();
        }
    }
}
