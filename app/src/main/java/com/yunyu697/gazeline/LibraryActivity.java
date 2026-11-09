package com.yunyu697.gazeline;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-page SMB library, replacing the 1.0.0 dialog chain. Everything the
 * viewer needs is on one page: the folder list, parent navigation, star
 * favourites, the projection toggle and a settings form that always opens
 * prefilled and persists what is typed.
 */
public class LibraryActivity extends Activity {

    private static final int REQ_LOCAL = 2001;
    private static final int REQ_PLAY = 2002;

    private static final int ROW_LOCAL = 0;
    private static final int ROW_HEADER = 1;
    private static final int ROW_UP = 2;
    private static final int ROW_DIR = 3;
    private static final int ROW_VIDEO = 4;
    private static final int ROW_FAV = 5;
    private static final int ROW_INFO = 6;

    private static final int C_BG = 0xFF05070A;
    private static final int C_TEXT = 0xFFE9EEF9;
    private static final int C_DIM = 0xFF93A2BA;
    private static final int C_FAINT = 0xFF6F7A91;
    private static final int C_AMBER = 0xFFFFB454;

    private SmbSettings settings;
    private String dir = "";
    private boolean loading;
    private String error;

    private final List<Row> rows = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout listPage;
    private LinearLayout settingsPage;
    private ListView list;
    private TextView pathView;
    private TextView starView;
    private TextView modeView;
    private TextView statusView;
    private EditText hostField;
    private EditText shareField;
    private EditText dirField;
    private EditText userField;
    private EditText passField;

    static final class Row {
        int type;
        String title = "";
        String detail = "";
        String path = "";
        long size;
        boolean clickable;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        settings = SmbSettings.load(this);
        dir = SmbFavorites.normalize(settings.path);
        setContentView(buildRoot());
        if (!settings.isConfigured()) {
            showSettings();
        } else {
            loadDir(dir);
        }
    }

    // ------------------------------------------------------------- layout

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(10), dp(10), dp(6));
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView back = label("←", 22, C_TEXT);
        back.setPadding(dp(10), dp(4), dp(14), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        bar.addView(back);

        TextView title = label("片库", 18, C_TEXT);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        modeView = action("360°");
        modeView.setPadding(dp(10), dp(6), dp(10), dp(6));
        modeView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.projection = "360".equals(settings.projection) ? "sbs" : "360";
                settings.save(LibraryActivity.this);
                refreshMode();
                refreshRows();
            }
        });
        bar.addView(modeView);

        starView = action("☆");
        starView.setPadding(dp(10), dp(6), dp(10), dp(6));
        starView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!settings.isConfigured()) {
                    showSettings();
                    return;
                }
                boolean starred = SmbFavorites.toggle(LibraryActivity.this, dir);
                refreshStar();
                refreshRows();
                toast(starred ? "已收藏 " + SmbFavorites.displayName(dir)
                        : "已取消收藏");
            }
        });
        bar.addView(starView);

        TextView config = action("设置");
        config.setPadding(dp(10), dp(6), dp(10), dp(6));
        config.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        bar.addView(config);

        pathView = label("", 11.5f, C_DIM);
        pathView.setPadding(dp(18), 0, dp(18), dp(6));
        root.addView(pathView);

        statusView = label("", 11.5f, C_AMBER);
        statusView.setPadding(dp(18), 0, dp(18), dp(4));
        root.addView(statusView);

        listPage = new LinearLayout(this);
        listPage.setOrientation(LinearLayout.VERTICAL);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(C_BG);
        list.setAdapter(new RowAdapter());
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                if (position < 0 || position >= rows.size()) {
                    return;
                }
                Row row = rows.get(position);
                if (row.clickable) {
                    handleRow(row);
                }
            }
        });
        listPage.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(listPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        settingsPage = buildSettingsPage();
        settingsPage.setVisibility(View.GONE);
        root.addView(settingsPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private LinearLayout buildSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), dp(20));

        TextView intro = label("SMB 配置 · 保存后会一直保留，下次打开自动填入。\n"
                + "账号密码只存在本机，不会写进日志或字幕。", 12, C_DIM);
        intro.setPadding(0, 0, 0, dp(8));
        form.addView(intro);

        hostField = field("服务器地址（如 192.168.1.10）", settings.host,
                InputType.TYPE_CLASS_TEXT);
        shareField = field("共享名（如 Data1）", settings.share, InputType.TYPE_CLASS_TEXT);
        dirField = field("默认目录（可留空）", settings.path, InputType.TYPE_CLASS_TEXT);
        userField = field("账号", settings.user, InputType.TYPE_CLASS_TEXT);
        passField = field("密码", settings.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Landscape stacks the five fields into two columns so the form fits
        // without scrolling; portrait keeps the single column.
        LinearLayout colA = form;
        LinearLayout colB = form;
        if (isLandscape()) {
            LinearLayout cols = new LinearLayout(this);
            cols.setOrientation(LinearLayout.HORIZONTAL);
            colA = new LinearLayout(this);
            colA.setOrientation(LinearLayout.VERTICAL);
            colB = new LinearLayout(this);
            colB.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lpB.leftMargin = dp(22);
            cols.addView(colA, lpA);
            cols.addView(colB, lpB);
            form.addView(cols);
        }
        colA.addView(hostField);
        colA.addView(shareField);
        colA.addView(dirField);
        colB.addView(userField);
        colB.addView(passField);

        TextView save = action("保存并连接");
        save.setGravity(Gravity.CENTER);
        save.setTextSize(16);
        save.setPadding(dp(12), dp(14), dp(12), dp(14));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.host = valueOf(hostField);
                settings.share = valueOf(shareField);
                settings.user = valueOf(userField);
                settings.password = passField.getText().toString().trim();
                settings.path = SmbFavorites.normalize(valueOf(dirField));
                settings.port = 445;
                if (!settings.isConfigured()) {
                    toast("服务器地址、共享名、账号不能为空");
                    return;
                }
                settings.save(LibraryActivity.this);
                dir = settings.path;
                showList();
                loadDir(dir);
            }
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(14);
        colB.addView(save, saveLp);

        TextView cancel = action("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(12), dp(12), dp(12), dp(12));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showList();
            }
        });
        form.addView(cancel);

        scroll.addView(form);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void showSettings() {
        settingsPage.setVisibility(View.VISIBLE);
        listPage.setVisibility(View.GONE);
        hostField.setText(settings.host);
        shareField.setText(settings.share);
        dirField.setText(settings.path);
        userField.setText(settings.user);
        passField.setText(settings.password);
    }

    private void showList() {
        settingsPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        refreshMode();
        refreshStar();
        refreshRows();
    }

    // ------------------------------------------------------------- data

    private void loadDir(final String target) {
        if (!settings.isConfigured()) {
            showSettings();
            return;
        }
        setLoading(true, "正在连接 " + settings.host + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SmbConnection.Entry> result = null;
                String failure = null;
                try {
                    SmbConnection conn = SmbConnection.connect(settings.host, settings.port,
                            settings.share, settings.user, settings.password);
                    try {
                        result = conn.list(target);
                    } finally {
                        conn.close();
                    }
                } catch (Throwable t) {
                    failure = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final List<SmbConnection.Entry> entries = result;
                final String err = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        setLoading(false, null);
                        if (entries == null) {
                            error = err == null ? "未知错误" : err;
                            dir = target;
                            settings.path = dir;
                            settings.save(LibraryActivity.this);
                            buildRows(new ArrayList<SmbConnection.Entry>());
                            refreshRows();
                        } else {
                            error = null;
                            dir = target;
                            settings.path = dir;
                            settings.save(LibraryActivity.this);
                            buildRows(entries);
                            refreshRows();
                        }
                    }
                });
            }
        }, "gazeline-library").start();
    }

    private void buildRows(List<SmbConnection.Entry> entries) {
        rows.clear();

        Row local = new Row();
        local.type = ROW_LOCAL;
        local.title = "本地视频文件";
        local.detail = "系统选择器 · " + modeText();
        local.clickable = true;
        add(local);

        List<String> favorites = SmbFavorites.list(this);
        if (!favorites.isEmpty()) {
            Row header = new Row();
            header.type = ROW_HEADER;
            header.title = "收藏夹 · 一次点击进入";
            add(header);
            for (String fav : favorites) {
                Row r = new Row();
                r.type = ROW_FAV;
                r.title = "★ " + SmbFavorites.displayName(fav);
                r.detail = settings.share + (fav.length() == 0 ? "" : "/" + fav);
                r.path = fav;
                r.clickable = true;
                add(r);
            }
        }

        if (dir.length() > 0) {
            Row up = new Row();
            up.type = ROW_UP;
            up.title = ".. 上一级";
            String parent = parentOf(dir);
            up.detail = parent.length() == 0 ? settings.share : settings.share + "/" + parent;
            up.path = parent;
            up.clickable = true;
            add(up);
        }

        for (SmbConnection.Entry e : entries) {
            if (e.directory) {
                Row r = new Row();
                r.type = ROW_DIR;
                r.title = e.name;
                r.detail = "目录";
                r.path = e.path;
                r.clickable = true;
                add(r);
            }
        }
        for (SmbConnection.Entry e : entries) {
            if (e.directory || !playable(e.name)) {
                continue;
            }
            Row r = new Row();
            r.type = ROW_VIDEO;
            r.title = e.name;
            PlayHistory.Entry h = PlayHistory.find(this, settings.url(e.path));
            String size = sizeText(e.size);
            if (h != null && h.resumable()) {
                r.detail = (size.length() == 0 ? "" : size + " · ")
                        + "继续 " + timeOf(h.positionMs);
            } else if (h != null && h.durationMs > 0) {
                r.detail = (size.length() == 0 ? "" : size + " · ") + "已看完";
            } else {
                r.detail = size;
            }
            r.path = e.path;
            r.size = e.size;
            r.clickable = true;
            add(r);
        }

        if (error != null) {
            Row r = new Row();
            r.type = ROW_INFO;
            r.title = "连接失败";
            r.detail = error + " · 检查网段 / 端口 445 / 账号密码";
            add(r);
            Row retry = new Row();
            retry.type = ROW_UP;
            retry.title = "重试";
            retry.detail = settings.host;
            retry.path = dir;
            retry.clickable = true;
            add(retry);
        } else if (entries.isEmpty()) {
            Row r = new Row();
            r.type = ROW_INFO;
            r.title = "这个目录是空的";
            r.detail = "换一个目录，或把常用目录收藏起来";
            add(r);
        }
    }

    private void refreshRows() {
        if (loading) {
            rows.clear();
            // Keep the local entry reachable even while the SMB listing is in flight.
            Row local = new Row();
            local.type = ROW_LOCAL;
            local.title = "本地视频文件";
            local.detail = "系统选择器 · " + modeText();
            local.clickable = true;
            rows.add(local);
            Row r = new Row();
            r.type = ROW_INFO;
            r.title = "正在连接…";
            r.detail = settings.host;
            rows.add(r);
        }
        pathView.setText(where());
        refreshMode();
        refreshStar();
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private void handleRow(Row r) {
        switch (r.type) {
            case ROW_LOCAL:
                pickLocal();
                return;
            case ROW_UP:
                loadDir(r.path);
                return;
            case ROW_FAV:
            case ROW_DIR:
                loadDir(r.path);
                return;
            case ROW_VIDEO:
                play(settings.url(r.path));
                return;
            default:
                break;
        }
    }

    private void play(String source) {
        String mode = "360".equals(settings.projection) ? "360" : "sbs";
        long position = PlayHistory.positionFor(this, source);
        Intent play = new Intent(this, PlayerActivity.class);
        play.putExtra(PlayerActivity.EXTRA_PATH, source);
        play.putExtra(PlayerActivity.EXTRA_MODE, mode);
        play.putExtra(PlayerActivity.EXTRA_SENSOR, true);
        if (position > 0) {
            play.putExtra(PlayerActivity.EXTRA_POSITION, position);
        }
        startActivityForResult(play, REQ_PLAY);
    }

    private void pickLocal() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_LOCAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PLAY) {
            // Hand the player's result back to the phone HUD so its 5 s
            // "continue" affordance keeps working.
            if (resultCode == RESULT_OK && data != null) {
                setResult(RESULT_OK, data);
            }
            finish();
            return;
        }
        if (requestCode == REQ_LOCAL) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            play(uri.toString());
        }
    }

    // ------------------------------------------------------------- helpers

    private void setLoading(boolean value, String text) {
        loading = value;
        statusView.setText(text == null ? "" : text);
        refreshRows();
    }

    private void add(Row row) {
        rows.add(row);
    }

    private String where() {
        if (!settings.isConfigured()) {
            return "尚未配置 SMB";
        }
        String path = dir.length() == 0 ? "" : "/" + dir;
        return settings.share + path + " · " + settings.host;
    }

    private void refreshMode() {
        if (modeView != null) {
            modeView.setText(modeText());
        }
    }

    private void refreshStar() {
        if (starView == null) {
            return;
        }
        boolean starred = settings.isConfigured() && SmbFavorites.contains(this, dir);
        starView.setText(starred ? "★" : "☆");
        starView.setTextColor(starred ? C_AMBER : C_DIM);
    }

    private String modeText() {
        return "360".equals(settings.projection) ? "360°" : "SBS";
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (settingsPage.getVisibility() == View.VISIBLE) {
            if (settings.isConfigured()) {
                showList();
                return;
            }
            finish();
            return;
        }
        if (dir.length() > 0) {
            loadDir(parentOf(dir));
            return;
        }
        super.onBackPressed();
    }

    private static String parentOf(String path) {
        if (path == null || path.length() == 0) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
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

    private static String timeOf(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long h = total / 3600L;
        long m = (total / 60L) % 60L;
        long s = total % 60L;
        if (h > 0) {
            return String.format("%02d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private TextView label(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        v.setTextColor(color);
        return v;
    }

    private TextView action(String text) {
        TextView v = label(text, 13, C_AMBER);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        // Order matters: setSingleLine installs the single-line transformation,
        // so it must run before setInputType installs the password one.
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_FAINT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private static String valueOf(EditText field) {
        return field.getText().toString().trim();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    // ------------------------------------------------------------- adapter

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position).clickable;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout box;
            TextView title;
            TextView detail;
            if (convertView == null) {
                box = new LinearLayout(LibraryActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(20), dp(10), dp(20), dp(10));
                title = label("", 15.5f, C_TEXT);
                detail = label("", 11.5f, C_DIM);
                detail.setPadding(0, dp(2), 0, 0);
                box.addView(title);
                box.addView(detail);
                box.setTag(new TextView[]{title, detail});
            } else {
                box = (LinearLayout) convertView;
                TextView[] tags = (TextView[]) box.getTag();
                title = tags[0];
                detail = tags[1];
            }
            Row r = rows.get(position);
            title.setText(r.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            detail.setText(r.detail);
            detail.setVisibility(r.detail.length() == 0 ? View.GONE : View.VISIBLE);

            boolean land = isLandscape();
            if (land) {
                // Wide screen: one compact line per entry, title left, meta right.
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                title.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detail.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                detail.setPadding(0, 0, dp(4), 0);
                detail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            } else {
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.START);
                title.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                title.setSingleLine(false);
                title.setEllipsize(null);
                detail.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                detail.setPadding(0, dp(2), 0, 0);
                detail.setGravity(Gravity.START);
            }

            switch (r.type) {
                case ROW_HEADER:
                    title.setTextColor(C_FAINT);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    box.setPadding(dp(20), dp(14), dp(20), dp(4));
                    break;
                case ROW_UP:
                    title.setTextColor(C_AMBER);
                    box.setPadding(dp(20), dp(10), dp(20), dp(10));
                    break;
                case ROW_DIR:
                    title.setTextColor(C_AMBER);
                    box.setPadding(dp(20), dp(10), dp(20), dp(10));
                    break;
                case ROW_FAV:
                    title.setTextColor(C_AMBER);
                    box.setPadding(dp(20), dp(8), dp(20), dp(8));
                    break;
                case ROW_LOCAL:
                    title.setTextColor(0xFF67E8F9);
                    box.setPadding(dp(20), dp(12), dp(20), dp(12));
                    break;
                case ROW_INFO:
                    title.setTextColor(C_DIM);
                    box.setPadding(dp(20), dp(12), dp(20), dp(12));
                    break;
                default:
                    title.setTextColor(C_TEXT);
                    box.setPadding(dp(20), dp(10), dp(20), dp(10));
                    break;
            }
            if (land) {
                int v = r.type == ROW_HEADER ? dp(8) : dp(6);
                box.setPadding(dp(20), v, dp(20), v);
            }
            return box;
        }
    }
}
