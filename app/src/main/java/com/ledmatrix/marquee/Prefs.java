package com.ledmatrix.marquee;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** SharedPreferences 统一入口（含常用语预设的极简存储） */
public final class Prefs {

    private static final String FILE = "led_marquee";
    private static final String K_PRESETS = "presets";
    private static final String SEP = "\u0001";
    private static final int MAX_PRESETS = 20;

    private Prefs() {
    }

    public static SharedPreferences of(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static ArrayList<String> loadPresets(SharedPreferences p) {
        ArrayList<String> list = new ArrayList<>();
        String raw = p.getString(K_PRESETS, "");
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        String[] parts = raw.split(SEP);
        for (String part : parts) {
            if (!part.isEmpty()) {
                list.add(part);
            }
        }
        return list;
    }

    public static void savePresets(SharedPreferences p, List<String> presets) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(presets.size(), MAX_PRESETS);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(presets.get(i).replace(SEP, " "));
        }
        p.edit().putString(K_PRESETS, sb.toString()).apply();
    }
}