package com.ledmatrix.marquee;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 设置界面：上方是实时 LED 点阵预览，下面是可滚动的参数面板。
 *
 * <p>所有参数改动都会即时反映到预览，并持久化（文本输入做了 400ms 防抖）。
 * 界面用代码构建而不是 XML layout，便于集中维护参数面板，也便于在手机/平板上统一缩放
 * （内容最大宽度限制为 760dp，平板不会拉得过于松散）。
 */
public class MainActivity extends Activity {

    private static final int MAX_PRESETS = 20;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;

    private static final int BG = 0xFF0B0D10;
    private static final int SURFACE = 0xFF14171C;
    private static final int SURFACE_ALT = 0xFF1B1F26;
    private static final int DIVIDER = 0xFF262B34;
    private static final int TEXT_PRIMARY = 0xFFEDEFF3;
    private static final int TEXT_SECONDARY = 0xFF9AA3B2;
    private static final int TEXT_HINT = 0xFF6B7484;
    private static final int ACCENT = 0xFFFF3B20;

    private static final int[] ROW_OPTIONS = {8, 12, 16, 20, 24, 32, 40};

    private MarqueeConfig config;
    private SharedPreferences prefs;
    private LinearLayout content;
    private LedMarqueeView preview;
    private EditText textInput;
    private TextView hintView;
    private Spinner presetSpinner;
    private LinearLayout ledGroup;
    private LinearLayout vectorGroup;
    private LinearLayout target;
    private final ArrayList<View> colorSwatches = new ArrayList<>();
    private TextView colorNameView;
    private int contentWidthPx;
    private ArrayAdapter<String> presetAdapter;

    private final ArrayList<String> presets = new ArrayList<>();
    private Runnable savePending;
    private boolean openedDisplay = false;
    private boolean suppressTextWatcher = false;

    private final Runnable debouncedSave = new Runnable() {
        @Override
        public void run() {
            config.save(prefs);
            savePending = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        presets.addAll(Prefs.loadPresets(prefs));
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (openedDisplay) {
            // 全屏页的快捷控制条可能改了参数：先把最新值读回来（避免旧值回写），再重建界面
            openedDisplay = false;
            config.load(prefs);
            recreate();
            return;
        }
        preview.startScroll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        preview.pauseScroll();
        persistNow();
    }

    @Override
    protected void onDestroy() {
        persistNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 界面构建

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(20), dp(16), dp(28));
        target = content;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int contentWidth = Math.min(screenWidth - dp(24), dp(760));
        contentWidthPx = contentWidth;
        frame.addView(content, new FrameLayout.LayoutParams(
                contentWidth,
                WRAP,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        addHeader();
        addPreview();
        addTextInput();
        addStartButton();
        addPresetSection();
        addDisplaySection();
        addBehaviorSection();
        addFooter();
        return scroll;
    }

    private void addHeader() {
        TextView title = new TextView(this);
        title.setText("LED 滚动字幕");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("仿红色 LED 点阵 · 手机 / 平板");
        subtitle.setTextColor(TEXT_SECONDARY);
        subtitle.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(4);
        content.addView(subtitle, lp);
    }

    private void addPreview() {
        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(rounded(SURFACE, 14f, DIVIDER, 1f));
        holder.setClipToOutline(true);

        preview = new LedMarqueeView(this);
        // 与 View 共用同一份配置对象，界面改动才能即时驱动渲染
        config = preview.config;
        config.load(prefs);
        preview.submit(true);
        preview.onTapListener = new Runnable() {
            @Override
            public void run() {
                startDisplay();
            }
        };
        holder.addView(preview, new FrameLayout.LayoutParams(MATCH_PARENT, dp(150)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(150));
        lp.topMargin = dp(16);
        content.addView(holder, lp);

        TextView tip = new TextView(this);
        tip.setText("点击预览即可全屏播放（全屏后单击暂停、长按呼出控制条）");
        tip.setTextColor(TEXT_HINT);
        tip.setTextSize(12f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tlp.topMargin = dp(8);
        content.addView(tip, tlp);
    }

    private void addTextInput() {
        textInput = new EditText(this);
        textInput.setText(config.text);
        textInput.setHint("输入要显示的字幕内容，可用回车换行（最多 6 行）");
        textInput.setTextColor(TEXT_PRIMARY);
        textInput.setHintTextColor(TEXT_HINT);
        textInput.setTextSize(16f);
        textInput.setMinLines(2);
        textInput.setMaxLines(6);
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textInput.setHorizontallyScrolling(false);
        textInput.setBackground(rounded(SURFACE, 12f, DIVIDER, 1f));
        textInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        textInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressTextWatcher) {
                    return;
                }
                config.text = s == null ? "" : s.toString();
                preview.submit();
                scheduleSave();
                updateCjkHint();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP);
        lp.topMargin = dp(18);
        content.addView(textInput, lp);

        hintView = new TextView(this);
        hintView.setTextColor(ACCENT);
        hintView.setTextSize(12f);
        hintView.setVisibility(View.GONE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        hlp.topMargin = dp(6);
        content.addView(hintView, hlp);
    }

    private void addStartButton() {
        TextView button = new TextView(this);
        button.setText("全屏播放");
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(17f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(ACCENT, 14f, 0, 0f));
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDisplay();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, dp(52));
        lp.topMargin = dp(16);
        content.addView(button, lp);
    }
    // ------------------------------------------------------------ 常用语预设

    private void addPresetSection() {
        addSection("常用语预设");

        presetSpinner = new Spinner(this);
        presetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new ArrayList<String>());
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(presetAdapter);
        refreshPresets(0);
        addRow(presetSpinner);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(weightedButton("载入", new Runnable() {
            @Override
            public void run() {
                loadSelectedPreset();
            }
        }, false));
        buttons.addView(weightedButton("存为预设", new Runnable() {
            @Override
            public void run() {
                savePreset();
            }
        }, false));
        buttons.addView(weightedButton("删除", new Runnable() {
            @Override
            public void run() {
                deletePreset();
            }
        }, true));
        addRow(buttons);
    }

    private void refreshPresets(int select) {
        presetAdapter.clear();
        if (presets.isEmpty()) {
            presetAdapter.add("（暂无预设，可在下方保存）");
        } else {
            presetAdapter.addAll(presets);
        }
        presetAdapter.notifyDataSetChanged();
        if (!presets.isEmpty()) {
            presetSpinner.setSelection(Math.max(0, Math.min(select, presets.size() - 1)));
        }
    }

    private void loadSelectedPreset() {
        int index = presetSpinner.getSelectedItemPosition();
        if (presets.isEmpty() || index < 0 || index >= presets.size()) {
            toast("暂无预设");
            return;
        }
        applyPreset(presets.get(index));
    }

    private void applyPreset(String value) {
        textInput.setText(value);
        textInput.setSelection(textInput.getText().length());
        toast("已载入预设");
    }

    private void savePreset() {
        String value = config.text.trim();
        if (value.isEmpty()) {
            toast("请先输入字幕内容");
            return;
        }
        if (presets.contains(value)) {
            toast("该内容已存在");
            return;
        }
        presets.add(0, value);
        while (presets.size() > MAX_PRESETS) {
            presets.remove(presets.size() - 1);
        }
        Prefs.savePresets(prefs, presets);
        refreshPresets(0);
        toast("已保存为预设");
    }

    private void deletePreset() {
        int index = presetSpinner.getSelectedItemPosition();
        if (presets.isEmpty() || index < 0 || index >= presets.size()) {
            toast("暂无预设可删除");
            return;
        }
        String removed = presets.remove(index);
        Prefs.savePresets(prefs, presets);
        refreshPresets(0);
        toast("已删除：" + (removed.length() > 10 ? removed.substring(0, 10) : removed));
    }

    // ------------------------------------------------------------ 各组参数

    private void addDisplaySection() {
        addSection("显示参数");

        addSpinnerRow("渲染方式", new String[]{"点阵 LED", "矢量文字"}, config.renderMode,
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.renderMode = value;
                        updateModeVisibility();
                        preview.submit();
                        scheduleSave();
                    }
                });

        addColorRow();

        addSpinnerRow("滚动方向", new String[]{"从右向左", "从左向右"}, config.direction,
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.direction = value;
                        preview.submit();
                        scheduleSave();
                    }
                });

        addSeekRow("滚动速度", 1, 80, clamp(Math.round(config.speed), 1, 80), " 列/秒",
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.speed = value;
                        preview.submit();
                        scheduleSave();
                    }
                });

        addSeekRow("亮度", 15, 160, clamp(config.brightness, 15, 160), "%", new IntCallback() {
            @Override
            public void onValue(int value) {
                config.brightness = value;
                preview.submit();
                scheduleSave();
            }
        });

        addSwitchRow("玻璃反光", config.gloss, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.gloss = value;
                preview.submit();
                scheduleSave();
            }
        });

        addModeGroups();
    }

    /** 点阵 / 矢量两种模式各自的效果参数，按当前模式显示或隐藏 */
    private void addModeGroups() {
        ledGroup = new LinearLayout(this);
        ledGroup.setOrientation(LinearLayout.VERTICAL);
        target = ledGroup;
        addSection("点阵效果");

        int rowIndex = indexOfRowOption(config.rows);
        addSpinnerRow("点阵字高", rowOptions(), rowIndex >= 0 ? rowIndex : 4, new IntCallback() {
            @Override
            public void onValue(int value) {
                config.rows = ROW_OPTIONS[value];
                preview.submit();
                scheduleSave();
                updateCjkHint();
            }
        });

        addSpinnerRow("滚动方式", new String[]{"灯珠步进（真实 LED 感）", "平滑移动"},
                config.smooth ? 1 : 0, new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.smooth = value == 1;
                        preview.submit();
                        scheduleSave();
                    }
                });

        addSeekRow("笔画粗细", 1, 5, clamp(config.strokeWeight, 1, 5), " 级", new IntCallback() {
            @Override
            public void onValue(int value) {
                config.strokeWeight = value;
                preview.submit();
                scheduleSave();
            }
        });

        addSwitchRow("灯光辉光", config.glow, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.glow = value;
                preview.submit();
                scheduleSave();
            }
        });
        addSwitchRow("显示未点亮的灯珠", config.showUnlit, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.showUnlit = value;
                preview.submit();
                scheduleSave();
            }
        });
        addSwitchRow("灯珠亮度色差", config.ledNoise, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.ledNoise = value;
                preview.submit();
                scheduleSave();
            }
        });
        addSwitchRow("面板模块网格", config.moduleGrid, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.moduleGrid = value;
                preview.submit();
                scheduleSave();
            }
        });

        target = content;
        content.addView(ledGroup);

        vectorGroup = new LinearLayout(this);
        vectorGroup.setOrientation(LinearLayout.VERTICAL);
        target = vectorGroup;
        addSection("文字效果（矢量）");

        addFontRow();

        addSeekRow("字号（占屏高）", 8, 100, clamp(config.textSizePercent, 8, 100), "%",
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.textSizePercent = value;
                        preview.submit();
                        scheduleSave();
                    }
                });

        addSpinnerRow("字形", new String[]{"常规", "粗体", "斜体", "粗斜体"}, config.fontWeight,
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.fontWeight = value;
                        preview.submit();
                        scheduleSave();
                    }
                });

        addSeekRow("字间距", 0, 20, clamp(config.letterSpacing, 0, 20), " 级",
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.letterSpacing = value;
                        preview.submit();
                        scheduleSave();
                    }
                });

        addSeekRow("行距", 10, 22, clamp(config.lineSpacing, 10, 22), " ×0.1",
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.lineSpacing = value;
                        preview.submit();
                        scheduleSave();
                    }
                });

        addSwitchRow("文字发光", config.textGlow, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.textGlow = value;
                preview.submit();
                scheduleSave();
            }
        });
        addSwitchRow("文字描边", config.textOutline, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.textOutline = value;
                preview.submit();
                scheduleSave();
            }
        });

        target = content;
        content.addView(vectorGroup);

        updateModeVisibility();
    }

    /** 字体下拉：每个选项用对应字体渲染，选之前就能看到效果 */
    private void addFontRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labelView("字体"), new LinearLayout.LayoutParams(0, WRAP, 1f));

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, TextFonts.NAMES) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView text = (TextView) super.getView(position, convertView, parent);
                text.setTypeface(TextFonts.typeface(position, Typeface.NORMAL));
                text.setTextSize(16f);
                return text;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView text = (TextView) super.getDropDownView(position, convertView, parent);
                text.setTypeface(TextFonts.typeface(position, Typeface.NORMAL));
                text.setTextSize(17f);
                return text;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(clamp(config.fontIndex, 0, TextFonts.NAMES.length - 1));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                config.fontIndex = position;
                preview.submit();
                scheduleSave();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        row.addView(spinner, lp);
        addRow(row);
    }

    private void updateModeVisibility() {
        boolean vector = config.renderMode == 1;
        if (ledGroup != null) {
            ledGroup.setVisibility(vector ? View.GONE : View.VISIBLE);
        }
        if (vectorGroup != null) {
            vectorGroup.setVisibility(vector ? View.VISIBLE : View.GONE);
        }
        updateCjkHint();
    }

    private void addBehaviorSection() {
        addSection("播放行为");

        addSpinnerRow("全屏方向", new String[]{"跟随系统", "竖屏", "横屏"}, config.orientation,
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        config.orientation = value;
                        scheduleSave();
                    }
                });
        addSwitchRow("左右镜像（玻璃反射场景）", config.mirror, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.mirror = value;
                preview.submit();
                scheduleSave();
            }
        });
        addSwitchRow("保持屏幕常亮", config.keepScreenOn, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.keepScreenOn = value;
                scheduleSave();
            }
        });
        addSwitchRow("全屏时点击暂停 / 继续", config.tapPause, new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                config.tapPause = value;
                scheduleSave();
            }
        });
        addButtonRow("恢复默认参数（保留文字与预设）", new Runnable() {
            @Override
            public void run() {
                resetToDefault();
            }
        });
    }

    private void addFooter() {
        TextView view = new TextView(this);
        view.setText("灯珠尺寸会随屏幕自动换算：手机竖屏能显示较少但更大的灯珠，"
                + "平板横屏则显示更多更细腻的灯珠；点阵始终严格对齐，不会出现半颗灯珠。");
        view.setTextColor(TEXT_HINT);
        view.setTextSize(12f);
        view.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(24);
        target.addView(view, lp);
    }

    // ------------------------------------------------------------ 行部件

    private void addSection(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextColor(TEXT_SECONDARY);
        view.setTextSize(13f);
        view.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(2);
        target.addView(view, lp);
    }

    private void addRow(View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP);
        lp.topMargin = dp(10);
        target.addView(view, lp);
    }

    private TextView labelView(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT_PRIMARY);
        view.setTextSize(15f);
        return view;
    }

    private void addSpinnerRow(String label, String[] items, int initial, IntCallback onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labelView(label), new LinearLayout.LayoutParams(0, WRAP, 1f));

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (items.length > 0) {
            spinner.setSelection(Math.max(0, Math.min(initial, items.length - 1)));
        }
        final IntCallback callback = onChange;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                callback.onValue(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        row.addView(spinner, lp);
        addRow(row);
    }

    private void addSeekRow(String label, int minValue, int maxValue, int initial,
                            String suffix, IntCallback onChange) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(labelView(label), new LinearLayout.LayoutParams(0, WRAP, 1f));

        final TextView valueView = new TextView(this);
        valueView.setTextColor(ACCENT);
        valueView.setTextSize(14f);
        head.addView(valueView);
        wrapper.addView(head);

        SeekBar seek = new SeekBar(this);
        seek.setMax(maxValue - minValue);
        seek.setProgress(Math.max(0, Math.min(initial - minValue, maxValue - minValue)));
        valueView.setText("" + (seek.getProgress() + minValue) + suffix);
        ColorStateList tint = ColorStateList.valueOf(ACCENT);
        seek.setProgressTintList(tint);
        seek.setThumbTintList(tint);
        final int base = minValue;
        final String textSuffix = suffix;
        final IntCallback callback = onChange;
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = progress + base;
                valueView.setText("" + value + textSuffix);
                callback.onValue(value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        wrapper.addView(seek, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP));
        addRow(wrapper);
    }

    /** 可视化色板：点圆点直接选色，所见即所得 */
    private void addColorRow() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(labelView("灯珠颜色"), new LinearLayout.LayoutParams(0, WRAP, 1f));
        colorNameView = new TextView(this);
        colorNameView.setTextColor(ACCENT);
        colorNameView.setTextSize(14f);
        colorNameView.setText(LedPalette.of(config.colorIndex).name);
        head.addView(colorNameView);
        wrapper.addView(head);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int inner = Math.max(dp(200), contentWidthPx - dp(32));
        int columns = Math.max(5, Math.min(8, inner / dp(46)));
        // 避免最后一行只剩一颗（19 颗灯珠配 6 列会变成 6/6/6/1，改成 5 列后是 5/5/5/4）
        if (LedPalette.COLORS.length % columns == 1 && columns > 5) {
            columns--;
        }
        LinearLayout line = null;
        for (int i = 0; i < LedPalette.COLORS.length; i++) {
            if (i % columns == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lineParams =
                        new LinearLayout.LayoutParams(MATCH_PARENT, WRAP);
                if (i > 0) {
                    lineParams.topMargin = dp(8);
                }
                grid.addView(line, lineParams);
            }
            final int index = i;
            View swatch = new View(this);
            swatch.setBackground(swatchBg(index, index == config.colorIndex));
            swatch.setContentDescription(LedPalette.of(index).name);
            swatch.setClickable(true);
            swatch.setOnClickListener(v -> selectColor(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
            params.rightMargin = dp(8);
            line.addView(swatch, params);
            colorSwatches.add(swatch);
        }
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP);
        gridParams.topMargin = dp(12);
        wrapper.addView(grid, gridParams);
        addRow(wrapper);
    }

    private void selectColor(int index) {
        if (index == config.colorIndex) {
            return;
        }
        config.colorIndex = index;
        for (int i = 0; i < colorSwatches.size(); i++) {
            colorSwatches.get(i).setBackground(swatchBg(i, i == index));
        }
        if (colorNameView != null) {
            colorNameView.setText(LedPalette.of(index).name);
        }
        preview.submit();
        scheduleSave();
    }

    /** 色板圆点：外圈为该灯珠颜色的光晕，选中时加白色圆环 */
    private Drawable swatchBg(int index, boolean selected) {
        LedPalette.LedColor color = LedPalette.of(index);
        int rgb = color.led & 0x00FFFFFF;
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        if (selected) {
            halo.setColor(rgb | 0x66000000);
            halo.setStroke(dp(2), 0xFFFFFFFF);
        } else {
            halo.setColor(rgb | 0x22000000);
            halo.setStroke(dp(1), 0x2AFFFFFF);
        }
        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(color.led);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{halo, core});
        int inset = selected ? dp(7) : dp(8);
        layer.setLayerInset(1, inset, inset, inset, inset);
        return layer;
    }

    private void addSwitchRow(String label, boolean initial, BoolCallback onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labelView(label), new LinearLayout.LayoutParams(0, WRAP, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(initial);
        final BoolCallback callback = onChange;
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> callback.onValue(isChecked));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        row.addView(toggle, lp);
        addRow(row);
    }

    private void addButtonRow(String text, Runnable onClick) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(TEXT_PRIMARY);
        button.setTextSize(15f);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(SURFACE_ALT, 12f, DIVIDER, 1f));
        button.setPadding(dp(14), dp(13), dp(14), dp(13));
        button.setClickable(true);
        button.setOnClickListener(v -> onClick.run());
        addRow(button);
    }

    private TextView weightedButton(String text, Runnable onClick, boolean last) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT_PRIMARY);
        view.setTextSize(13f);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(SURFACE_ALT, 10f, DIVIDER, 1f));
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setClickable(true);
        view.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (!last) {
            lp.rightMargin = dp(8);
        }
        view.setLayoutParams(lp);
        return view;
    }

    // ------------------------------------------------------------ 行为

    private void startDisplay() {
        if (config.text.trim().isEmpty()) {
            toast("请先输入字幕内容");
            return;
        }
        persistNow();
        openedDisplay = true;
        startActivity(new Intent(this, DisplayActivity.class));
    }

    private void resetToDefault() {
        config.copyFrom(new MarqueeConfig(), true);
        config.save(prefs);
        recreate();
    }

    private void updateCjkHint() {
        if (config.renderMode == 0 && MarqueeConfig.hasCjk(config.text) && config.rows < 20) {
            hintView.setText("检测到中文：建议把「点阵字高」调到 20 行以上，笔画更清晰易读");
            hintView.setVisibility(View.VISIBLE);
        } else {
            hintView.setVisibility(View.GONE);
        }
    }

    private String[] rowOptions() {
        String[] result = new String[ROW_OPTIONS.length];
        for (int i = 0; i < ROW_OPTIONS.length; i++) {
            result[i] = ROW_OPTIONS[i] + " 行";
        }
        return result;
    }

    private static int indexOfRowOption(int rows) {
        for (int i = 0; i < ROW_OPTIONS.length; i++) {
            if (ROW_OPTIONS[i] == rows) {
                return i;
            }
        }
        return -1;
    }

    /** 文本输入逐字保存太频繁，做 400ms 防抖 */
    private void scheduleSave() {
        if (savePending == null) {
            savePending = debouncedSave;
        }
        content.removeCallbacks(savePending);
        content.postDelayed(savePending, 400L);
    }

    private void persistNow() {
        if (savePending != null) {
            content.removeCallbacks(savePending);
            savePending = null;
        }
        config.save(prefs);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static int clamp(int value, int lo, int hi) {
        if (value < lo) {
            return lo;
        }
        return value > hi ? hi : value;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, float radiusDp, int strokeColor, float strokeDp) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * density);
        if (strokeColor != 0 && strokeDp > 0f) {
            drawable.setStroke((int) Math.max(1f, strokeDp * density), strokeColor);
        }
        return drawable;
    }

    private interface IntCallback {
        void onValue(int value);
    }

    private interface BoolCallback {
        void onValue(boolean value);
    }
}