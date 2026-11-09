package com.yunyu697.gazeline;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/**
 * Phone-side shell. The file list lives in {@link LibraryActivity}; the big
 * button opens it, the "进入 VR" button jumps straight into the headset
 * browser, and the recent list resumes a film where it stopped.
 */
public class MainActivity extends Activity {

    private static final int REQ_LIBRARY = 1001;
    private static final int REQ_PLAY = 1002;
    private static final int REQ_CALIB = 1003;

    private StageView stage;
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
                startActivityForResult(new Intent(MainActivity.this, LibraryActivity.class),
                        REQ_LIBRARY);
            }
        });
        stage.setOnEnterVrListener(new StageView.OnEnterVrListener() {
            @Override
            public void onEnterVr() {
                launchVrLibrary();
            }
        });
        stage.setOnHistoryPlayListener(new StageView.OnHistoryPlayListener() {
            @Override
            public void onHistoryPlay(String path, String mode, long positionMs) {
                launchPlayer(path, mode, positionMs);
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

    @Override
    protected void onResume() {
        super.onResume();
        if (stage != null) {
            stage.reloadHistory();
        }
    }

    private void launchVrLibrary() {
        Intent vr = new Intent(this, PlayerActivity.class);
        vr.putExtra(PlayerActivity.EXTRA_BROWSE, true);
        vr.putExtra(PlayerActivity.EXTRA_SENSOR, true);
        startActivityForResult(vr, REQ_PLAY);
    }

    private void launchPlayer(String path, String mode, long positionMs) {
        if (path == null || path.length() == 0) {
            return;
        }
        Intent play = new Intent(this, PlayerActivity.class);
        play.putExtra(PlayerActivity.EXTRA_PATH, path);
        play.putExtra(PlayerActivity.EXTRA_MODE, mode);
        play.putExtra(PlayerActivity.EXTRA_SENSOR, true);
        long resume = positionMs > 0 ? positionMs : PlayHistory.positionFor(this, path);
        if (resume > 0) {
            play.putExtra(PlayerActivity.EXTRA_POSITION, resume);
        }
        startActivityForResult(play, REQ_PLAY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CALIB) {
            stage.reloadCalib();
            return;
        }
        if ((requestCode == REQ_PLAY || requestCode == REQ_LIBRARY)
                && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(PlayerActivity.EXTRA_PATH);
            String mode = data.getStringExtra(PlayerActivity.EXTRA_MODE);
            long position = data.getLongExtra(PlayerActivity.EXTRA_POSITION, 0L);
            if (path != null && path.length() > 0) {
                stage.showUndo(path, mode == null ? "360" : mode, position);
                stage.reloadHistory();
                ui.removeCallbacks(clearUndo);
                ui.postDelayed(clearUndo, 5000);
            }
        }
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
