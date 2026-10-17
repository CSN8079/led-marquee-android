package com.ledmatrix.marquee;

import android.content.SharedPreferences;

/**
 * 全部可调参数。改动会即时作用于预览与全屏显示，并持久化到 SharedPreferences。
 * 纯 Android Framework 实现：整个应用零第三方依赖、零 Kotlin 运行时。
 */
public class MarqueeConfig {

    public static final String DEFAULT_TEXT = "欢迎光临  ·  LED 点阵滚动字幕\nHELLO  LED  MATRIX";

    private static final String K_TEXT = "text";
    private static final String K_COLOR = "color";
    private static final String K_ROWS = "rows";
    private static final String K_SPEED = "speed";
    private static final String K_BRIGHT = "bright";
    private static final String K_DIR = "dir";
    private static final String K_SMOOTH = "smooth";
    private static final String K_WEIGHT = "weight";
    private static final String K_GLOW = "glow";
    private static final String K_UNLIT = "unlit";
    private static final String K_NOISE = "noise";
    private static final String K_GRID = "grid";
    private static final String K_GLOSS = "gloss";
    private static final String K_MIRROR = "mirror";
    private static final String K_KEEPON = "keepon";
    private static final String K_TAPPAUSE = "tappause";
    private static final String K_ORIENT = "orient";

    /** 字幕文本，可用 \n 换行（最多 6 行） */
    public String text = DEFAULT_TEXT;

    /** 灯珠颜色，索引见 {@link LedPalette} */
    public int colorIndex = 0;

    /** 每个字占用的灯珠行数（点阵字高），越大越细腻 */
    public int rows = 24;

    /** 滚动速度：灯珠列 / 秒 */
    public float speed = 16f;

    /** 亮度百分比，15 ~ 160 */
    public int brightness = 100;

    /** 0 = 从右向左，1 = 从左向右 */
    public int direction = 0;

    /** true = 平滑移动；false = 按整颗灯珠步进（更像真实 LED 板） */
    public boolean smooth = false;

    /** 笔画粗细 1（细）~ 5（粗） */
    public int strokeWeight = 3;

    /** 灯光辉光 */
    public boolean glow = true;

    /** 显示未点亮的灯珠 */
    public boolean showUnlit = true;

    /** 灯珠亮度色差（模拟真实灯板的个体差异） */
    public boolean ledNoise = true;

    /** 面板模块网格线 */
    public boolean moduleGrid = false;

    /** 玻璃反光 */
    public boolean gloss = true;

    /** 左右镜像（用于玻璃反射 / 后视镜场景） */
    public boolean mirror = false;

    /** 全屏时保持屏幕常亮 */
    public boolean keepScreenOn = true;

    /** 全屏时点击暂停 / 继续 */
    public boolean tapPause = true;

    /** 全屏方向：0 跟随系统，1 竖屏，2 横屏 */
    public int orientation = 0;

    /** 用另一份配置覆盖本对象；keepText = true 时保留当前文本 */
    public void copyFrom(MarqueeConfig other, boolean keepText) {
        String currentText = text;
        colorIndex = other.colorIndex;
        rows = other.rows;
        speed = other.speed;
        brightness = other.brightness;
        direction = other.direction;
        smooth = other.smooth;
        strokeWeight = other.strokeWeight;
        glow = other.glow;
        showUnlit = other.showUnlit;
        ledNoise = other.ledNoise;
        moduleGrid = other.moduleGrid;
        gloss = other.gloss;
        mirror = other.mirror;
        keepScreenOn = other.keepScreenOn;
        tapPause = other.tapPause;
        orientation = other.orientation;
        text = keepText ? currentText : other.text;
    }

    public void load(SharedPreferences p) {
        text = p.getString(K_TEXT, DEFAULT_TEXT);
        if (text == null) {
            text = DEFAULT_TEXT;
        }
        colorIndex = p.getInt(K_COLOR, 0);
        rows = p.getInt(K_ROWS, 24);
        speed = p.getFloat(K_SPEED, 16f);
        brightness = p.getInt(K_BRIGHT, 100);
        direction = p.getInt(K_DIR, 0);
        smooth = p.getBoolean(K_SMOOTH, false);
        strokeWeight = p.getInt(K_WEIGHT, 3);
        glow = p.getBoolean(K_GLOW, true);
        showUnlit = p.getBoolean(K_UNLIT, true);
        ledNoise = p.getBoolean(K_NOISE, true);
        moduleGrid = p.getBoolean(K_GRID, false);
        gloss = p.getBoolean(K_GLOSS, true);
        mirror = p.getBoolean(K_MIRROR, false);
        keepScreenOn = p.getBoolean(K_KEEPON, true);
        tapPause = p.getBoolean(K_TAPPAUSE, true);
        orientation = p.getInt(K_ORIENT, 0);
    }

    public void save(SharedPreferences p) {
        p.edit()
                .putString(K_TEXT, text)
                .putInt(K_COLOR, colorIndex)
                .putInt(K_ROWS, rows)
                .putFloat(K_SPEED, speed)
                .putInt(K_BRIGHT, brightness)
                .putInt(K_DIR, direction)
                .putBoolean(K_SMOOTH, smooth)
                .putInt(K_WEIGHT, strokeWeight)
                .putBoolean(K_GLOW, glow)
                .putBoolean(K_UNLIT, showUnlit)
                .putBoolean(K_NOISE, ledNoise)
                .putBoolean(K_GRID, moduleGrid)
                .putBoolean(K_GLOSS, gloss)
                .putBoolean(K_MIRROR, mirror)
                .putBoolean(K_KEEPON, keepScreenOn)
                .putBoolean(K_TAPPAUSE, tapPause)
                .putInt(K_ORIENT, orientation)
                .apply();
    }

    /** 文本是否包含中文/日文/韩文（用于给出更合适的字高建议） */
    public static boolean hasCjk(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            // 0x2E80 之后是中日韩文字、全角符号、私用区以及 UTF-16 代理对（emoji 等）
            if (value.charAt(i) >= 0x2E80) {
                return true;
            }
        }
        return false;
    }
}