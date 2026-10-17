package com.ledmatrix.marquee;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全屏滚动字幕（LED 点阵）界面。
 *
 * <p>交互：
 * <ul>
 *   <li>单击屏幕 -&gt; 暂停 / 继续</li>
 *   <li>长按屏幕 -&gt; 呼出快捷控制条（4.5 秒无操作自动隐藏）</li>
 * </ul>
 */
public class DisplayActivity extends Activity {

    private static final long BAR_TIMEOUT_MS = 4500L;
    private static final int TEXT_COLOR = 0xFFEDEFF3;
    private static final int BAR_BG = 0xE6101318;
    private static final int BUTTON_BG = 0xFF232A34;
    private static final int HUD_BG = 0xCC1A1F27;

    private MarqueeConfig config;
    private LedMarqueeView marquee;
    private FrameLayout root;

    private HorizontalScrollView barHolder;
    private LinearLayout bar;
    private TextView hud;
    private TextView pauseButton;
    private TextView colorButton;
    private TextView directionButton;

    private final Runnable hideBarTask = new Runnable() {
        @Override
        public void run() {
            showBar(false);
        }
    };

    private final Runnable hideHudTask = new Runnable() {
        @Override
        public void run() {
            if (hud != null) {
                hud.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 直接使用 View 内部那份 config，保证界面与渲染共用同一个对象
        marquee = new LedMarqueeView(this);
        config = marquee.config;
        config.load(Prefs.of(this));
        applyOrientation();
        if (config.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        applyDisplayCutout();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        marquee.submit(true);
        marquee.onTapListener = new Runnable() {
            @Override
            public void run() {
                if (config.tapPause) {
                    togglePause();
                }
            }
        };
        marquee.onLongPressListener = new Runnable() {
            @Override
            public void run() {
                showBar(barHolder == null || barHolder.getVisibility() != View.VISIBLE);
            }
        };
        root.addView(marquee, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        marquee.startScroll();
        if (pauseButton != null) {
            refreshBarTexts();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        marquee.pauseScroll();
        if (bar != null) {
            bar.removeCallbacks(hideBarTask);
        }
        config.save(Prefs.of(this));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
        }
    }

    // ------------------------------------------------------------ 控制条

    private View buildBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(rounded(BAR_BG, 24f));

        pauseButton = barButton("", new Runnable() {
            @Override
            public void run() {
                togglePause();
            }
        });
        row.addView(pauseButton);

        colorButton = barButton("", new Runnable() {
            @Override
            public void run() {
                cycleColor();
            }
        });
        row.addView(colorButton);

        directionButton = barButton("", new Runnable() {
            @Override
            public void run() {
                toggleDirection();
            }
        });
        row.addView(directionButton);

        row.addView(barButton("速度 -", new Runnable() {
            @Override
            public void run() {
                changeSpeed(-2f);
            }
        }));
        row.addView(barButton("速度 +", new Runnable() {
            @Override
            public void run() {
                changeSpeed(2f);
            }
        }));
        row.addView(barButton("亮度 -", new Runnable() {
            @Override
            public void run() {
                changeBrightness(-10);
            }
        }));
        row.addView(barButton("亮度 +", new Runnable() {
            @Override
            public void run() {
                changeBrightness(10);
            }
        }));
        if (config.renderMode == 0) {
            row.addView(barButton("平滑", new Runnable() {
                @Override
                public void run() {
                    toggleSmooth();
                }
            }));
        }
        row.addView(barButton("回到开头", new Runnable() {
            @Override
            public void run() {
                marquee.restartScroll();
            }
        }));
        row.addView(barButton("设置 / 退出", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));

        HorizontalScrollView holder = new HorizontalScrollView(this);
        holder.setHorizontalScrollBarEnabled(false);
        holder.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.setVisibility(View.GONE);
        return holder;
    }

    private void refreshBarTexts() {
        if (pauseButton != null) {
            pauseButton.setText(marquee.isScrolling() ? "暂停" : "继续");
        }
        if (colorButton != null) {
            colorButton.setText(LedPalette.of(config.colorIndex).name);
        }
        if (directionButton != null) {
            directionButton.setText(config.direction == 0 ? "从右向左" : "从左向右");
        }
    }

    private void showBar(boolean show) {
        if (show) {
            if (barHolder == null) {
                barHolder = (HorizontalScrollView) buildBar();
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                lp.setMargins(dp(10), dp(10), dp(10), dp(18));
                root.addView(barHolder, lp);
                bar = (LinearLayout) barHolder.getChildAt(0);
            }
            refreshBarTexts();
            barHolder.removeCallbacks(hideBarTask);
            if (barHolder.getVisibility() != View.VISIBLE) {
                barHolder.setVisibility(View.VISIBLE);
                barHolder.setAlpha(0f);
                barHolder.animate().alpha(1f).setDuration(160).start();
            } else {
                barHolder.setAlpha(1f);
            }
            barHolder.postDelayed(hideBarTask, BAR_TIMEOUT_MS);
            ensureHud();
        } else {
            if (barHolder == null) {
                return;
            }
            barHolder.removeCallbacks(hideBarTask);
            barHolder.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
                @Override
                public void run() {
                    barHolder.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    private void ensureHud() {
        if (hud != null) {
            return;
        }
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(15f);
        view.setPadding(dp(16), dp(9), dp(16), dp(9));
        view.setBackground(rounded(HUD_BG, 20f));
        view.setVisibility(View.GONE);
        hud = view;
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    private void showHud(String text) {
        ensureHud();
        if (hud == null) {
            return;
        }
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        hud.removeCallbacks(hideHudTask);
        hud.postDelayed(hideHudTask, 900L);
    }

    private TextView barButton(String text, final Runnable onClick) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT_COLOR);
        view.setTextSize(14f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(9), dp(14), dp(9));
        view.setBackground(rounded(BUTTON_BG, 18f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        view.setLayoutParams(lp);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });
        return view;
    }

    // ------------------------------------------------------------ 快捷操作

    private void togglePause() {
        if (marquee.isScrolling()) {
            marquee.pauseScroll();
            showHud("已暂停");
        } else {
            marquee.startScroll();
            showHud("继续滚动");
        }
        refreshBarTexts();
    }

    private void changeSpeed(float delta) {
        config.speed = Math.max(1f, Math.min(80f, config.speed + delta));
        config.save(Prefs.of(this));
        marquee.submit();
        showHud("速度 " + Math.round(config.speed) + " 列/秒");
    }

    private void changeBrightness(int delta) {
        config.brightness = Math.max(15, Math.min(160, config.brightness + delta));
        config.save(Prefs.of(this));
        marquee.submit();
        showHud("亮度 " + config.brightness + "%");
    }

    private void cycleColor() {
        config.colorIndex = (config.colorIndex + 1) % LedPalette.COLORS.length;
        config.save(Prefs.of(this));
        marquee.submit();
        refreshBarTexts();
        showHud(LedPalette.of(config.colorIndex).name);
    }

    private void toggleDirection() {
        config.direction = config.direction == 0 ? 1 : 0;
        config.save(Prefs.of(this));
        marquee.submit();
        refreshBarTexts();
        showHud(config.direction == 0 ? "从右向左" : "从左向右");
    }

    private void toggleSmooth() {
        config.smooth = !config.smooth;
        config.save(Prefs.of(this));
        marquee.submit();
        showHud(config.smooth ? "平滑移动" : "灯珠步进");
    }

    // ------------------------------------------------------------ 系统 UI

    private void applyOrientation() {
        if (config.orientation == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if (config.orientation == 2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void applyDisplayCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ------------------------------------------------------------ 工具

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return drawable;
    }
}