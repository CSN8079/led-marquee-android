package com.ledmatrix.marquee;

import android.graphics.Typeface;

/**
 * 矢量模式可选的系统字体族。
 * 这些是 Android 内置的通用族名，厂商缺字时会自动回退到默认字体，不会崩溃。
 */
public final class TextFonts {

    public static final String[] FAMILIES = {
            "sans-serif",
            "sans-serif-light",
            "sans-serif-condensed",
            "sans-serif-black",
            "serif",
            "monospace",
            "cursive"
    };

    public static final String[] NAMES = {
            "默认黑体",
            "细体",
            "窄体",
            "粗黑",
            "衬线体",
            "等宽体",
            "手写体"
    };

    private TextFonts() {
    }

    public static String name(int index) {
        if (index < 0 || index >= NAMES.length) {
            return NAMES[0];
        }
        return NAMES[index];
    }

    public static String family(int index) {
        if (index < 0 || index >= FAMILIES.length) {
            return FAMILIES[0];
        }
        return FAMILIES[index];
    }

    /** @param style 0 常规 / 1 粗体 / 2 斜体 / 3 粗斜体 */
    public static Typeface typeface(int index, int style) {
        int androidStyle;
        switch (style) {
            case 1:
                androidStyle = Typeface.BOLD;
                break;
            case 2:
                androidStyle = Typeface.ITALIC;
                break;
            case 3:
                androidStyle = Typeface.BOLD_ITALIC;
                break;
            default:
                androidStyle = Typeface.NORMAL;
                break;
        }
        return Typeface.create(family(index), androidStyle);
    }
}