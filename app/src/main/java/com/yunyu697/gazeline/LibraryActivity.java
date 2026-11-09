package com.yunyu697.gazeline;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phone-side library (P1) and server page (P2) from the v2 design.
 *
 * <p>Deliberately no media library: there is no poster, actor, codec or bitrate
 * scraping. Everything shown is the real file size plus what the file name
 * itself says (360/3D/4K), and the artwork is a generated gradient block. The
 * hero card resumes from {@link PlayHistory}, which is the only "rich" data the
 * app owns.
 */
public class LibraryActivity extends Activity {

    private static final int REQ_LOCAL = 2001;
    private static final int REQ_PLAY = 2002;

    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_FILES = 1;
    private static final int SCREEN_SERVER = 2;
    private static final int SCREEN_SETTINGS = 3;

    private static final int C_BG = 0xFF05070A;
    private static final int C_CARD = 0xFF0E141C;
    private static final int C_CARD2 = 0xFF121A24;
    private static final int C_LINE = 0x1FFFFFFF;
    private static final int C_TEXT = 0xFFE9EEF9;
    private static final int C_DIM = 0xFF93A2BA;
    private static final int C_FAINT = 0xFF6F7A91;
    private static final int C_AMBER = 0xFFFFB454;
    private static final int C_GREEN = 0xFF43D9A0;
    private static final int C_BLUE = 0xFF6C8CFF;

    private static final String[] CHIPS = {"全部", "360°", "SBS 3D", "4K"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private SmbSettings settings;
    private String dir = "";
    private boolean loading;
    private String error;
    private long latencyMs = -1;
    private String dialect = "";
    private int videoCount;
    private int fourKCount;
    private List<SmbConnection.Entry> entries = new ArrayList<>();

    private String query = "";
    private int chip;

    private int screen = SCREEN_HOME;

    private TextView barTitle;
    private TextView barLeft;
    private TextView barRight;
    private TextView[] navItems;

    private ScrollView homeScroll;
    private ScrollView filesScroll;
    private ScrollView serverScroll;
    private ScrollView settingsScroll;

    private LinearLayout homeBody;
    private LinearLayout filesBody;
    private LinearLayout serverInner;

    private TextView statusStrip;
    private EditText searchField;

    private EditText nameField;
    private EditText hostField;
    private EditText shareField;
    private EditText userField;
    private EditText passField;
    private TextView passToggle;
    private TextView modeSmb;
    private TextView modeDav;
    private TextView modeHttp;
    private TextView anonToggle;
    private TextView autoToggle;
    private TextView resultView;
    private LinearLayout recentList;

    private boolean passwordShown;

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
            showScreen(SCREEN_SERVER);
        } else {
            showScreen(SCREEN_HOME);
            if (settings.autoReconnect) {
                loadDir(dir);
            } else {
                refreshHome();
            }
        }
    }

    // ------------------------------------------------------------- shell

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        root.addView(buildBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        homeScroll = scrollHost(homePage());
        filesScroll = scrollHost(filesPage());
        serverScroll = scrollHost(serverPage());
        settingsScroll = scrollHost(settingsPage());
        body.addView(homeScroll, matchParams());
        body.addView(filesScroll, matchParams());
        body.addView(serverScroll, matchParams());
        body.addView(settingsScroll, matchParams());

        root.addView(buildNav(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private LinearLayout buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(4));

        barLeft = label("≡", 20, C_TEXT);
        barLeft.setPadding(dp(10), dp(4), dp(14), dp(4));
        barLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (screen == SCREEN_HOME) {
                    showScreen(SCREEN_SERVER);
                } else {
                    showScreen(SCREEN_HOME);
                }
            }
        });
        bar.addView(barLeft);

        barTitle = label("片库", 18, C_TEXT);
        barTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(TextUtils.TruncateAt.END);
        barTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(barTitle);

        barRight = label("", 13, C_AMBER);
        barRight.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.addView(barRight);
        return bar;
    }

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(0xFF0A1017);
        nav.setPadding(0, dp(6), 0, dp(6));
        String[] names = {"片库", "文件", "服务器", "设置"};
        navItems = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView item = label(names[i], 11.5f, C_FAINT);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(6), 0, dp(6));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showScreen(index);
                }
            });
            navItems[i] = item;
            nav.addView(item, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return nav;
    }

    private void showScreen(int which) {
        screen = which;
        homeScroll.setVisibility(which == SCREEN_HOME ? View.VISIBLE : View.GONE);
        filesScroll.setVisibility(which == SCREEN_FILES ? View.VISIBLE : View.GONE);
        serverScroll.setVisibility(which == SCREEN_SERVER ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(which == SCREEN_SETTINGS ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navItems.length; i++) {
            navItems[i].setTextColor(i == which ? C_AMBER : C_FAINT);
        }
        switch (which) {
            case SCREEN_HOME:
                barLeft.setText("≡");
                barTitle.setText("片库");
                barRight.setText("＋ 服务器");
                barRight.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showScreen(SCREEN_SERVER);
                    }
                });
                refreshHome();
                break;
            case SCREEN_FILES:
                barLeft.setText("‹");
                barTitle.setText("文件");
                barRight.setText(settings.isConfigured() ? "刷新" : "");
                barRight.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadDir(dir);
                    }
                });
                refreshFiles();
                break;
            case SCREEN_SERVER:
                barLeft.setText("‹");
                barTitle.setText("添加服务器");
                barRight.setText("帮助");
                barRight.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toast("输入 NAS 的局域网地址与共享名；仅 SMB 可用，"
                                + "账号可留空时打开「匿名访问」。");
                    }
                });
                fillServerForm();
                break;
            case SCREEN_SETTINGS:
                barLeft.setText("‹");
                barTitle.setText("设置");
                barRight.setText("");
                barRight.setOnClickListener(null);
                break;
            default:
                break;
        }
        if (which == SCREEN_HOME) {
            homeScroll.scrollTo(0, 0);
        }
    }

    // ------------------------------------------------------------- P1 home

    private LinearLayout homePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(4), dp(14), dp(14));

        LinearLayout search = card();
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(12), dp(2), dp(12), dp(2));
        search.addView(label("⌕", 15, C_FAINT));

        searchField = new EditText(this);
        searchField.setHint("搜索片名、文件名、路径");
        searchField.setHintTextColor(C_FAINT);
        searchField.setTextColor(C_TEXT);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        searchField.setSingleLine(true);
        searchField.setBackground(null);
        searchField.setPadding(dp(8), dp(8), 0, dp(8));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim();
                refreshHomeBody();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        search.addView(searchField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(search, blockLp(dp(6)));

        statusStrip = label("", 11.5f, C_DIM);
        statusStrip.setPadding(dp(6), dp(8), dp(6), dp(6));
        page.addView(statusStrip);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < CHIPS.length; i++) {
            final int index = i;
            TextView chipView = label(CHIPS[i], 12, C_DIM);
            chipView.setGravity(Gravity.CENTER);
            chipView.setPadding(dp(12), dp(6), dp(12), dp(6));
            chipView.setBackground(chipBackground(false));
            chipView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chip = index;
                    refreshHomeBody();
                }
            });
            chips.addView(chipView, chipLp());
        }
        page.addView(chips, blockLp(dp(8)));

        homeBody = new LinearLayout(this);
        homeBody.setOrientation(LinearLayout.VERTICAL);
        page.addView(homeBody, matchWrap());
        return page;
    }

    private void refreshHome() {
        refreshStrip();
        refreshHomeBody();
    }

    private void refreshStrip() {
        if (statusStrip == null) {
            return;
        }
        if (!settings.isConfigured()) {
            statusStrip.setText("● 未配置服务器 · 先在「服务器」页添加");
            statusStrip.setTextColor(C_AMBER);
            return;
        }
        StringBuilder sb = new StringBuilder("● ");
        sb.append(SmbSettings.profileLabel(settings.profileName));
        sb.append(" · ").append(dialect.length() == 0 ? "SMB" : dialect);
        if (latencyMs >= 0) {
            sb.append(" · ").append(videoCount).append(" 部 · ").append(latencyMs).append(" ms");
        } else if (loading) {
            sb.append(" · 正在连接…");
        } else if (error != null) {
            sb.append(" · 连接失败");
        }
        statusStrip.setText(sb.toString());
        statusStrip.setTextColor(error != null ? C_AMBER
                : (latencyMs >= 0 ? C_GREEN : C_DIM));
    }

    private void refreshHomeBody() {
        if (homeBody == null) {
            return;
        }
        homeBody.removeAllViews();

        if (!settings.isConfigured()) {
            homeBody.addView(emptyCard("还没有服务器",
                    "填地址、共享名和账号后即可浏览；也可以直接打开本地文件。",
                    "添加服务器", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showScreen(SCREEN_SERVER);
                        }
                    }), blockLp(dp(10)));
            homeBody.addView(localCard(), blockLp(dp(10)));
            return;
        }

        homeBody.addView(readyCard(), blockLp(dp(10)));
        PlayHistory.Entry hero = heroEntry();
        if (hero != null) {
            homeBody.addView(heroCard(hero), blockLp(dp(10)));
        }
        if (error != null) {
            homeBody.addView(emptyCard("连接失败", error
                            + " · 检查网段 / 端口 445 / 账号密码",
                    "重试", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            loadDir(dir);
                        }
                    }), blockLp(dp(10)));
        }
        homeBody.addView(sectionHead("最近添加", "查看全部",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showScreen(SCREEN_FILES);
                    }
                }), blockLp(dp(6)));

        List<SmbConnection.Entry> videos = filteredVideos();
        if (loading && videos.isEmpty()) {
            homeBody.addView(infoCard("正在读取目录…"), blockLp(dp(8)));
        } else if (videos.isEmpty()) {
            homeBody.addView(infoCard(error != null ? "目录暂时不可读"
                    : "这个目录没有匹配的视频 · 到「文件」页换目录"), blockLp(dp(8)));
        } else {
            int limit = Math.min(videos.size(), 6);
            for (int i = 0; i < limit; i++) {
                homeBody.addView(videoRow(videos.get(i)), blockLp(dp(8)));
            }
            if (videos.size() > limit) {
                homeBody.addView(sectionHead("", "共 " + videos.size() + " 部 · 查看全部",
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showScreen(SCREEN_FILES);
                            }
                        }), blockLp(dp(4)));
            }
        }
        homeBody.addView(localCard(), blockLp(dp(10)));
    }

    private View readyCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView ok = label("✓", 16, C_GREEN);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(circle(0x1F43D9A0));
        ok.setWidth(dp(30));
        ok.setHeight(dp(30));
        card.addView(ok);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        TextView head = label("已准备好佩戴", 13.5f, C_TEXT);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.addView(head);
        CalibParams calib = CalibParams.load(this);
        StringBuilder sb = new StringBuilder(latencyMs >= 0 ? "服务器已连接" : "尚未连接");
        sb.append(" · ").append(calib.modelName())
                .append(" · IPD ").append(Math.round(calib.ipdMm)).append(" mm");
        TextView sub = label(sb.toString(), 11, C_DIM);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(sub);
        card.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = action("校准");
        go.setPadding(dp(12), dp(7), dp(12), dp(7));
        go.setBackground(chipBackground(true));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LibraryActivity.this, CalibActivity.class)
                        .putExtra(CalibActivity.EXTRA_SENSOR, true));
            }
        });
        card.addView(go);
        return card;
    }

    private PlayHistory.Entry heroEntry() {
        for (PlayHistory.Entry e : PlayHistory.load(this)) {
            if (e.resumable()) {
                return e;
            }
        }
        return null;
    }

    private View heroCard(final PlayHistory.Entry entry) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(heroBackground());
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView kicker = label("继续观看 · "
                + ("360".equals(entry.mode) ? "360°" : "SBS 3D"), 11, C_AMBER);
        card.addView(kicker);
        TextView title = label(stripExtension(entry.name), 19, C_TEXT);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(4), 0, dp(2));
        card.addView(title);

        String meta = inferTags(entry.name);
        TextView sub = label(meta.length() == 0 ? "上次播放的影片" : meta, 11, C_DIM);
        card.addView(sub);

        long duration = entry.durationMs;
        int percent = duration > 0
                ? (int) Math.min(100, entry.positionMs * 100L / duration) : 0;
        card.addView(progressBar(percent), blockLp(dp(10)));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.addView(label(timeOf(entry.positionMs), 11, C_DIM), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView remain = label(duration > 0
                ? "−" + timeOf(Math.max(0, duration - entry.positionMs)) : percent + "%",
                11, C_DIM);
        remain.setGravity(Gravity.RIGHT);
        times.addView(remain);
        card.addView(times);

        TextView play = action("▶ 继续播放");
        play.setGravity(Gravity.CENTER);
        play.setPadding(0, dp(10), 0, dp(10));
        play.setBackground(chipBackground(true));
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play(entry.source, entry.mode);
            }
        });
        card.addView(play, blockLp(dp(10)));
        return card;
    }

    private View videoRow(final SmbConnection.Entry entry) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play(settings.url(entry.path), projectionMode());
            }
        });

        row.addView(thumb(entry.name), new LinearLayout.LayoutParams(dp(56), dp(56)));

        PlayHistory.Entry h = PlayHistory.find(this, settings.url(entry.path));
        int percent = h != null && h.durationMs > 0
                ? (int) Math.min(100, h.positionMs * 100L / h.durationMs) : 0;

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(12), 0, dp(8), 0);
        TextView title = label(stripExtension(entry.name), 13.5f, C_TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        main.addView(title);
        TextView meta = label(metaOf(entry), 10.5f, C_DIM);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(3), 0, dp(5));
        main.addView(meta);
        main.addView(progressBar(percent), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        row.addView(main, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String end;
        if (percent > 0) {
            end = percent + "%";
        } else if (h != null) {
            end = "已看完";
        } else {
            end = "未看";
        }
        TextView badge = label(end, 11.5f, percent > 0 ? C_AMBER : C_FAINT);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(6), 0, 0, 0);
        row.addView(badge);
        return row;
    }

    private View thumb(String name) {
        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                gradientFor(name));
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        TextView tag = label(tagOf(name), 9.5f, 0xFFF2F6FF);
        tag.setGravity(Gravity.CENTER);
        tag.setBackground(chipBackground(false));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setMargins(dp(4), 0, 0, dp(4));
        box.addView(tag, lp);
        return box;
    }

    private View localCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLocal();
            }
        });
        card.addView(label("▤", 16, C_BLUE));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(10), 0, 0, 0);
        text.addView(label("本地视频文件", 13, C_TEXT));
        text.addView(label("系统选择器 · " + projectionModeText(), 10.5f, C_DIM));
        card.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(label("›", 18, C_FAINT));
        return card;
    }

    private View sectionHead(String title, String action, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(2), dp(4), 0);
        TextView head = label(title, 13, C_TEXT);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(head, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null && action.length() > 0) {
            TextView go = action(action);
            go.setOnClickListener(listener);
            row.addView(go);
        }
        return row;
    }

    private View emptyCard(String title, String body, String button,
                           View.OnClickListener listener) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(label(title, 14, C_TEXT));
        TextView sub = label(body, 11, C_DIM);
        sub.setPadding(0, dp(4), 0, dp(10));
        card.addView(sub);
        TextView go = action(button);
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, dp(9), 0, dp(9));
        go.setBackground(chipBackground(true));
        go.setOnClickListener(listener);
        card.addView(go);
        return card;
    }

    private View infoCard(String text) {
        LinearLayout card = card();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(label(text, 11.5f, C_FAINT));
        return card;
    }

    private View progressBar(int percent) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x21FFFFFF);
        bg.setCornerRadius(dp(2));
        track.setBackground(bg);
        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(C_AMBER);
        fg.setCornerRadius(dp(2));
        fill.setBackground(fg);
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(2),
                Math.max(0.001f, Math.min(1f, percent / 100f))));
        if (percent < 100) {
            View rest = new View(this);
            track.addView(rest, new LinearLayout.LayoutParams(0, dp(2),
                    1f - Math.max(0f, Math.min(1f, percent / 100f))));
        }
        return track;
    }

    private List<SmbConnection.Entry> filteredVideos() {
        List<SmbConnection.Entry> out = new ArrayList<>();
        for (SmbConnection.Entry e : entries) {
            if (e.directory || !playable(e.name)) {
                continue;
            }
            if (query.length() > 0
                    && !e.name.toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US))
                    && !e.path.toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US))) {
                continue;
            }
            if (chip == 1 && !is360(e.name)) {
                continue;
            }
            if (chip == 2 && !is3d(e.name)) {
                continue;
            }
            if (chip == 3 && !is4k(e.name)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------- files

    private LinearLayout filesPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(4), dp(14), dp(14));
        filesBody = new LinearLayout(this);
        filesBody.setOrientation(LinearLayout.VERTICAL);
        page.addView(filesBody, matchWrap());
        return page;
    }

    private void refreshFiles() {
        if (filesBody == null) {
            return;
        }
        filesBody.removeAllViews();
        filesBody.addView(localCard(), blockLp(dp(10)));

        List<String> favorites = SmbFavorites.list(this, settings);
        if (!favorites.isEmpty()) {
            filesBody.addView(sectionHead("收藏夹", "一次点击进入", null),
                    blockLp(dp(8)));
            for (final String fav : favorites) {
                LinearLayout row = card();
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(label("★", 14, C_AMBER));
                LinearLayout text = new LinearLayout(this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(10), 0, 0, 0);
                text.addView(label(SmbFavorites.displayName(fav), 13, C_TEXT));
                text.addView(label(settings.share + (fav.length() == 0 ? "" : "/" + fav),
                        10.5f, C_DIM));
                row.addView(text, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(label("›", 18, C_FAINT));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showScreen(SCREEN_FILES);
                        loadDir(fav);
                    }
                });
                filesBody.addView(row, blockLp(dp(8)));
            }
        }

        if (!settings.isConfigured()) {
            filesBody.addView(infoCard("尚未配置服务器 · 现在只能打开本地文件"),
                    blockLp(dp(8)));
            return;
        }

        filesBody.addView(sectionHead(dir.length() == 0
                ? settings.share : settings.share + "/" + dir, "☆ 收藏",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean starred = SmbFavorites.toggle(LibraryActivity.this,
                                settings, dir);
                        toast(starred ? "已收藏 " + SmbFavorites.displayName(dir)
                                : "已取消收藏");
                        refreshFiles();
                    }
                }), blockLp(dp(10)));

        if (loading) {
            filesBody.addView(infoCard("正在读取目录…"), blockLp(dp(6)));
        }
        if (error != null) {
            filesBody.addView(infoCard("连接失败：" + error), blockLp(dp(6)));
        }

        if (dir.length() > 0) {
            String parent = parentOf(dir);
            LinearLayout up = fileRow(".. 上一级",
                    parent.length() == 0 ? settings.share : settings.share + "/" + parent,
                    C_DIM);
            up.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadDir(parentOf(dir));
                }
            });
            filesBody.addView(up, blockLp(dp(6)));
        }

        for (final SmbConnection.Entry e : entries) {
            if (!e.directory) {
                continue;
            }
            LinearLayout row = fileRow("▸ " + e.name, "目录", C_TEXT);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadDir(e.path);
                }
            });
            filesBody.addView(row, blockLp(dp(6)));
        }
        for (final SmbConnection.Entry e : entries) {
            if (e.directory || !playable(e.name)) {
                continue;
            }
            LinearLayout row = fileRow(stripExtension(e.name), metaOf(e), C_TEXT);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    play(settings.url(e.path), projectionMode());
                }
            });
            filesBody.addView(row, blockLp(dp(6)));
        }
    }

    private LinearLayout fileRow(String title, String detail, int color) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView head = label(title, 13, color);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        text.addView(head);
        TextView sub = label(detail, 10.5f, C_DIM);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label("›", 18, C_FAINT));
        return row;
    }

    // ------------------------------------------------------------- P2 server

    private LinearLayout serverPage() {
        serverInner = new LinearLayout(this);
        serverInner.setOrientation(LinearLayout.VERTICAL);
        serverInner.setPadding(dp(14), dp(4), dp(14), dp(16));

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(cardBackground());
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        modeSmb = segItem("SMB", true);
        modeDav = segItem("WebDAV", false);
        modeHttp = segItem("HTTP", false);
        seg.addView(modeSmb, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(modeDav, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(modeHttp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        serverInner.addView(seg, blockLp(dp(10)));
        View.OnClickListener unsupported = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("暂未支持 WebDAV / HTTP，先用 SMB");
            }
        };
        modeDav.setOnClickListener(unsupported);
        modeHttp.setOnClickListener(unsupported);

        nameField = field("记录名称（留空自动命名）", InputType.TYPE_CLASS_TEXT);
        hostField = field("服务器地址 如 192.168.1.20", InputType.TYPE_CLASS_TEXT);
        shareField = field("共享名与路径 如 media / Movies / VR",
                InputType.TYPE_CLASS_TEXT);
        userField = field("账号（访客可留空）", InputType.TYPE_CLASS_TEXT);
        passField = field("密码", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        serverInner.addView(nameField);
        serverInner.addView(hostField);
        serverInner.addView(shareField);
        serverInner.addView(userField);

        LinearLayout passRow = new LinearLayout(this);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        passRow.setGravity(Gravity.CENTER_VERTICAL);
        passRow.addView(passField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        passToggle = action("显示");
        passToggle.setPadding(dp(10), dp(8), dp(4), dp(8));
        passToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                passwordShown = !passwordShown;
                passField.setInputType(InputType.TYPE_CLASS_TEXT
                        | (passwordShown ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                passField.setSelection(passField.getText().length());
                passToggle.setText(passwordShown ? "隐藏" : "显示");
            }
        });
        passRow.addView(passToggle);
        serverInner.addView(passRow);

        anonToggle = switchRow("匿名访问", "不需要账号密码，仅限可信局域网",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        settings.anonymous = !settings.anonymous;
                        refreshSwitches();
                    }
                });
        serverInner.addView(anonToggle, blockLp(dp(2)));

        autoToggle = switchRow("开机自动重连", "打开片库时自动连接本服务器",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        settings.autoReconnect = !settings.autoReconnect;
                        refreshSwitches();
                    }
                });
        serverInner.addView(autoToggle, blockLp(dp(2)));

        resultView = label("尚未测试", 11.5f, C_FAINT);
        resultView.setPadding(dp(14), dp(12), dp(14), dp(12));
        resultView.setBackground(cardBackground());
        resultView.setLineSpacing(dp(2), 1f);
        serverInner.addView(resultView, blockLp(dp(10)));

        TextView test = action("测试并保存");
        test.setGravity(Gravity.CENTER);
        test.setPadding(0, dp(13), 0, dp(13));
        test.setBackground(chipBackground(true));
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAndSave();
            }
        });
        serverInner.addView(test, blockLp(dp(10)));

        serverInner.addView(calibCard(), blockLp(dp(10)));

        serverInner.addView(sectionHead("最近连接", "管理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("在下方记录里点「使用」切换，点「删除」移除");
            }
        }), blockLp(dp(8)));

        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        serverInner.addView(recentList, matchWrap());
        return serverInner;
    }

    private View calibCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(label("◉", 16, C_AMBER));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(10), 0, dp(6), 0);
        TextView head = label("观看器参数（戴前）", 12.5f, C_TEXT);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.addView(head);
        CalibParams p = CalibParams.load(this);
        TextView sub = label(p.modelName() + " · IPD " + Math.round(p.ipdMm)
                + " mm · " + String.format(Locale.US, "%.1f″ 屏", p.screenInch),
                10.5f, C_DIM);
        text.addView(sub);
        card.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = action("开始校准");
        go.setPadding(dp(10), dp(6), dp(0), dp(6));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LibraryActivity.this, CalibActivity.class)
                        .putExtra(CalibActivity.EXTRA_SENSOR, true));
            }
        });
        card.addView(go);
        return card;
    }

    private void fillServerForm() {
        if (nameField == null) {
            return;
        }
        nameField.setText(settings.profileName);
        hostField.setText(settings.host);
        shareField.setText(settings.share);
        userField.setText(settings.user);
        passField.setText(settings.password);
        refreshSwitches();
        refreshRecent();
        refreshStrip();
    }

    private void refreshSwitches() {
        if (anonToggle == null) {
            return;
        }
        anonToggle.setText("匿名访问　" + (settings.anonymous ? "已开启" : "已关闭"));
        anonToggle.setTextColor(settings.anonymous ? C_AMBER : C_DIM);
        autoToggle.setText("开机自动重连　" + (settings.autoReconnect ? "已开启" : "已关闭"));
        autoToggle.setTextColor(settings.autoReconnect ? C_AMBER : C_DIM);
        passField.setEnabled(!settings.anonymous);
        userField.setEnabled(!settings.anonymous);
    }

    private void refreshRecent() {
        if (recentList == null) {
            return;
        }
        recentList.removeAllViews();
        List<SmbSettings.Profile> saved = SmbSettings.profiles(this);
        if (saved.isEmpty()) {
            recentList.addView(infoCard("还没有记录 · 填好后点「测试并保存」"), blockLp(dp(6)));
            return;
        }
        for (final SmbSettings.Profile profile : saved) {
            boolean active = profile.name.equals(settings.profileName);
            LinearLayout row = card();
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = label((active ? "● " : "○ ") + profile.name, 12.5f,
                    active ? C_AMBER : C_TEXT);
            text.addView(name);
            TextView meta = label(profile.host + "/" + profile.share
                    + (profile.anonymous ? " · 匿名" : ""), 10.5f, C_DIM);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.addView(meta);
            row.addView(text, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView use = action(active ? "当前" : "使用");
            use.setPadding(dp(10), dp(6), dp(10), dp(6));
            use.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (profile.name.equals(settings.profileName)) {
                        return;
                    }
                    settings.selectProfile(LibraryActivity.this, profile.name);
                    dir = SmbFavorites.normalize(settings.path);
                    fillServerForm();
                    toast("已切换到「" + SmbSettings.profileLabel(settings.profileName) + "」");
                    loadDir(dir);
                }
            });
            row.addView(use);

            TextView del = action("删除");
            del.setTextColor(C_FAINT);
            del.setPadding(dp(10), dp(6), dp(2), dp(6));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!settings.deleteProfile(LibraryActivity.this, profile.name)) {
                        return;
                    }
                    toast("已删除「" + profile.name + "」");
                    fillServerForm();
                }
            });
            row.addView(del);

            recentList.addView(row, blockLp(dp(6)));
        }
    }

    private TextView segItem(String text, boolean on) {
        TextView v = label(text, 12, on ? C_TEXT : C_DIM);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(8), 0, dp(8));
        if (on) {
            v.setBackground(chipBackground(true));
        }
        return v;
    }

    private TextView switchRow(String title, String detail, View.OnClickListener listener) {
        TextView v = label(title + "　已关闭", 13, C_DIM);
        v.setPadding(dp(14), dp(11), dp(14), dp(11));
        v.setBackground(cardBackground());
        v.setOnClickListener(listener);
        return v;
    }

    /** Connect, list the root, and persist only after a real round trip. */
    private void testAndSave() {
        settings.host = valueOf(hostField);
        settings.share = valueOf(shareField);
        settings.user = valueOf(userField);
        settings.password = passField.getText().toString().trim();
        settings.port = 445;
        applyShareAndPath();
        if (!settings.isConfigured()) {
            toast("地址与共享名不能为空；匿名访问时账号可留空");
            return;
        }
        resultView.setText("正在测试 " + settings.host + " …");
        resultView.setTextColor(C_DIM);
        final String name = valueOf(nameField);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long start = SystemClock.elapsedRealtime();
                List<SmbConnection.Entry> list = null;
                String fail = null;
                String proto = "";
                try {
                    SmbConnection conn = SmbConnection.connect(settings.host, settings.port,
                            settings.share, settings.user, settings.password,
                            settings.anonymous);
                    try {
                        proto = conn.dialectName();
                        list = conn.list("");
                    } finally {
                        conn.close();
                    }
                } catch (Throwable t) {
                    fail = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final long cost = SystemClock.elapsedRealtime() - start;
                final List<SmbConnection.Entry> result = list;
                final String errorText = fail;
                final String protocol = proto;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (result == null) {
                            resultView.setText("连接失败 · " + errorText);
                            resultView.setTextColor(C_AMBER);
                            return;
                        }
                        int videos = 0;
                        int fourK = 0;
                        for (SmbConnection.Entry e : result) {
                            if (!e.directory && playable(e.name)) {
                                videos++;
                                if (is4k(e.name)) {
                                    fourK++;
                                }
                            }
                        }
                        resultView.setText("✓ 测试通过 · "
                                + (protocol.length() == 0 ? "SMB" : protocol)
                                + " · " + cost + " ms\n目录可读："
                                + videos + " 个视频，" + fourK + " 个 4K 文件");
                        resultView.setTextColor(C_GREEN);
                        settings.saveProfile(LibraryActivity.this, name);
                        nameField.setText(settings.profileName);
                        refreshRecent();
                        refreshSwitches();
                        dir = SmbFavorites.normalize(settings.path);
                        showScreen(SCREEN_HOME);
                        loadDir(dir);
                    }
                });
            }
        }, "gazeline-test").start();
    }

    /**
     * "share/path" is typed as one field in the design, so split on the first
     * slash: everything before it is the share, the rest is the start folder.
     */
    private void applyShareAndPath() {
        String raw = settings.share;
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            String path = raw.substring(slash + 1);
            settings.share = raw.substring(0, slash).trim();
            settings.path = SmbFavorites.normalize(path);
            shareField.setText(settings.share
                    + (settings.path.length() == 0 ? "" : " / " + settings.path));
        } else {
            settings.path = "";
        }
    }

    // ------------------------------------------------------------- settings

    private LinearLayout settingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(4), dp(14), dp(16));

        page.addView(sectionHead("默认投影", "播放时仍可切换", null), blockLp(dp(8)));
        final TextView mode = label(projectionModeText(), 13, C_AMBER);
        mode.setPadding(dp(14), dp(12), dp(14), dp(12));
        mode.setBackground(cardBackground());
        mode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.projection = "360".equals(settings.projection) ? "sbs" : "360";
                settings.save(LibraryActivity.this);
                mode.setText(projectionModeText());
            }
        });
        page.addView(mode, blockLp(dp(6)));

        page.addView(sectionHead("播放记录", "本地保存，不上传", null), blockLp(dp(14)));
        LinearLayout clear = card();
        clear.setPadding(dp(14), dp(12), dp(14), dp(12));
        clear.addView(label("清除全部续播记录", 13, C_TEXT));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlayHistory.clear(LibraryActivity.this);
                toast("已清除播放记录");
                refreshHome();
            }
        });
        page.addView(clear, blockLp(dp(6)));

        page.addView(sectionHead("关于", null, null), blockLp(dp(14)));
        LinearLayout about = card();
        about.setOrientation(LinearLayout.VERTICAL);
        about.setPadding(dp(14), dp(12), dp(14), dp(12));
        about.addView(label("Gazeline " + versionName(), 13, C_TEXT));
        TextView note = label("片库只读取文件名与大小，不抓取海报、演员、编码或码率；"
                + "封面为渐变色块。搜索支持片名、文件名与路径。", 11, C_DIM);
        note.setPadding(0, dp(4), 0, 0);
        note.setLineSpacing(dp(2), 1f);
        about.addView(note);
        page.addView(about, blockLp(dp(6)));
        return page;
    }

    // ------------------------------------------------------------- data

    private void loadDir(final String target) {
        if (!settings.isConfigured()) {
            showScreen(SCREEN_SERVER);
            return;
        }
        loading = true;
        error = null;
        refreshStrip();
        refreshHomeBody();
        refreshFiles();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SmbConnection.Entry> result = null;
                String failure = null;
                String proto = "";
                final long start = SystemClock.elapsedRealtime();
                try {
                    SmbConnection conn = SmbConnection.connect(settings.host, settings.port,
                            settings.share, settings.user, settings.password,
                            settings.anonymous);
                    try {
                        proto = conn.dialectName();
                        result = conn.list(target);
                    } finally {
                        conn.close();
                    }
                } catch (Throwable t) {
                    failure = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final long cost = SystemClock.elapsedRealtime() - start;
                final List<SmbConnection.Entry> list = result;
                final String err = failure;
                final String protocol = proto;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        loading = false;
                        if (list == null) {
                            error = err == null ? "未知错误" : err;
                            entries = new ArrayList<>();
                            refreshStrip();
                            refreshHomeBody();
                            refreshFiles();
                            return;
                        }
                        error = null;
                        latencyMs = cost;
                        dialect = protocol;
                        entries = list;
                        dir = target;
                        settings.path = dir;
                        settings.save(LibraryActivity.this);
                        int videos = 0;
                        int fourK = 0;
                        for (SmbConnection.Entry e : list) {
                            if (!e.directory && playable(e.name)) {
                                videos++;
                                if (is4k(e.name)) {
                                    fourK++;
                                }
                            }
                        }
                        videoCount = videos;
                        fourKCount = fourK;
                        refreshStrip();
                        refreshHomeBody();
                        refreshFiles();
                    }
                });
            }
        }, "gazeline-library").start();
    }

    private void play(String source, String mode) {
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
            play(uri.toString(), projectionMode());
        }
    }

    @Override
    public void onBackPressed() {
        if (screen != SCREEN_HOME) {
            showScreen(SCREEN_HOME);
            return;
        }
        if (dir.length() > 0) {
            loadDir(parentOf(dir));
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------- helpers

    private String projectionMode() {
        return "360".equals(settings.projection) ? "360" : "sbs";
    }

    private String projectionModeText() {
        return "360".equals(settings.projection) ? "360° 全景" : "SBS 3D 立体";
    }

    private String metaOf(SmbConnection.Entry e) {
        StringBuilder sb = new StringBuilder();
        String tags = inferTags(e.name);
        if (tags.length() > 0) {
            sb.append(tags).append(" · ");
        }
        String size = sizeText(e.size);
        sb.append(size.length() == 0 ? "大小未知" : size);
        sb.append(" · SMB");
        return sb.toString();
    }

    /** Name-only inference: no media probing, per the "no media library" call. */
    private String inferTags(String name) {
        List<String> tags = new ArrayList<>();
        if (is4k(name)) {
            tags.add("4K");
        }
        if (is3d(name)) {
            tags.add("SBS 3D");
        } else if (is360(name)) {
            tags.add("360°");
        }
        if (name.toLowerCase(Locale.US).contains("hevc")
                || name.toLowerCase(Locale.US).contains("h265")) {
            tags.add("HEVC");
        }
        StringBuilder sb = new StringBuilder();
        for (String tag : tags) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(tag);
        }
        return sb.toString();
    }

    private String tagOf(String name) {
        if (is4k(name)) {
            return "4K";
        }
        if (is3d(name)) {
            return "3D";
        }
        if (is360(name)) {
            return "360";
        }
        return "2D";
    }

    private static boolean is4k(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.contains("4k") || n.contains("2160");
    }

    private static boolean is3d(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.contains("sbs") || n.contains("3d") || n.contains("hsbs");
    }

    private static boolean is360(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.contains("360") || n.contains("vr") || n.contains("equirect");
    }

    private int[] gradientFor(String name) {
        int hash = Math.abs(name.hashCode());
        int[][] sets = {
                {0xFF1D3C58, 0xFF3F6D7A, 0xFF7A5A3A},
                {0xFF2A2438, 0xFF6A4A55, 0xFFB07A55},
                {0xFF1A2A44, 0xFF3A5A6A, 0xFF8A7A4A},
                {0xFF233040, 0xFF5A6A4A, 0xFFA08A5A},
        };
        return sets[hash % sets.length];
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(1, C_LINE);
        return bg;
    }

    private GradientDrawable heroBackground() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1E30, 0xFF1D4A63, 0xFF22301F});
        bg.setCornerRadius(dp(18));
        bg.setStroke(1, C_LINE);
        return bg;
    }

    private GradientDrawable chipBackground(boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? 0x33FFB454 : 0x1FFFFFFF);
        bg.setCornerRadius(dp(20));
        bg.setStroke(1, on ? 0x66FFB454 : C_LINE);
        return bg;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        return bg;
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(cardBackground());
        return v;
    }

    private ScrollView scrollHost(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(child, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private static FrameLayout.LayoutParams matchParams() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams blockLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginDp;
        return lp;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        return lp;
    }

    private static String parentOf(String path) {
        if (path == null || path.length() == 0) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
            return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private TextView label(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        v.setTextColor(color);
        return v;
    }

    private TextView action(String text) {
        TextView v = label(text, 12.5f, C_AMBER);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        // Order matters: setSingleLine installs the single-line transformation,
        // so it must run before setInputType installs the password one.
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_FAINT);
        e.setBackground(cardBackground());
        e.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private static String valueOf(EditText field) {
        return field.getText().toString().trim();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
