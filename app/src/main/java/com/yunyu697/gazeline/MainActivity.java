package com.yunyu697.gazeline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity {

    private static final int REQ_PICK_VIDEO = 1001;
    private static final int REQ_PLAY = 1002;
    private static final int REQ_CALIB = 1003;

    private StageView stage;
    private boolean pendingSbs;
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
        String[] modes = {"360° 全景（等距柱状）", "SBS 3D（左右半宽）"};
        new AlertDialog.Builder(this)
                .setTitle("选择投影方式")
                .setItems(modes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pendingSbs = which == 1;
                        pickVideo();
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
        launchPlayer(uri.toString(), pendingSbs ? "sbs" : "360", 0L);
    }

    private void launchPlayer(String path, String mode, long positionMs) {
        Intent play = new Intent(this, PlayerActivity.class);
        play.putExtra(PlayerActivity.EXTRA_PATH, path);
        play.putExtra(PlayerActivity.EXTRA_MODE, mode);
        play.putExtra(PlayerActivity.EXTRA_SENSOR, true);
        if (positionMs > 0) {
            play.putExtra(PlayerActivity.EXTRA_POSITION, positionMs);
        }
        startActivityForResult(play, REQ_PLAY);
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
