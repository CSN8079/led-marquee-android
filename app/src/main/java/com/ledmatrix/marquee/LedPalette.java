package com.ledmatrix.marquee;

/** 一种灯珠配色：led 为灯珠本体颜色，glow 为辉光颜色 */
public final class LedPalette {

    public static final class LedColor {
        public final String name;
        public final int led;
        public final int glow;

        LedColor(String name, int led, int glow) {
            this.name = name;
            this.led = led;
            this.glow = glow;
        }
    }

    /** 默认第一项就是经典红色 LED */
    public static final LedColor[] COLORS = {
            new LedColor("红色 LED", 0xFFFF3418, 0xFFFF1000),
            new LedColor("琥珀色", 0xFFFFA000, 0xFFFF7200),
            new LedColor("橙色", 0xFFFF6A14, 0xFFFF3C00),
            new LedColor("黄色", 0xFFFFE23C, 0xFFFFBE00),
            new LedColor("翠绿色", 0xFF2BFF78, 0xFF00E24A),
            new LedColor("青色", 0xFF2BFFE8, 0xFF00D8E0),
            new LedColor("蓝色", 0xFF4D90FF, 0xFF1E5CFF),
            new LedColor("紫色", 0xFFC060FF, 0xFF8C1EFF),
            new LedColor("白色", 0xFFFFFFFF, 0xFFCFE4FF),
            new LedColor("粉色", 0xFFFF5FC0, 0xFFFF1E86),
            new LedColor("荧光绿", 0xFFA9FF2E, 0xFF6FE000),
            new LedColor("薄荷绿", 0xFF66FFC4, 0xFF10E49A),
            new LedColor("天蓝", 0xFF55D9FF, 0xFF18A8FF),
            new LedColor("宝石蓝", 0xFF3A6DFF, 0xFF1036E0),
            new LedColor("品红", 0xFFFF2BD0, 0xFFFF00B4),
            new LedColor("玫红", 0xFFFF2158, 0xFFFF0030),
            new LedColor("暖白", 0xFFFFE9C8, 0xFFFFCA8A),
            new LedColor("冰蓝", 0xFFB5EBFF, 0xFF78D4FF),
            new LedColor("淡紫", 0xFFD9A8FF, 0xFFA860FF)
    };

    private LedPalette() {
    }

    public static LedColor of(int index) {
        if (index < 0 || index >= COLORS.length) {
            return COLORS[0];
        }
        return COLORS[index];
    }

    public static String[] names() {
        String[] result = new String[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) {
            result[i] = COLORS[i].name;
        }
        return result;
    }
}