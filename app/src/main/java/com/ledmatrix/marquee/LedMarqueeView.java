package com.ledmatrix.marquee;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 仿 LED 点阵（红色 LED 矩阵）滚动字幕控件。
 *
 * <p>渲染流水线：
 * <ol>
 *   <li>把文字按"灯珠行数"光栅化成二值点阵。先做 3 倍超采样再按方块覆盖率取阈值，
 *       因此中文、符号、任意系统字体都能得到笔画清晰的点阵字（不需要自带点阵字库）。</li>
 *   <li>把未点亮的灯珠、面板网格、渐晕、四边压暗预渲染成一张背景位图，
 *       只在尺寸/配色/参数变化时重建，每帧只做一次 drawBitmap。</li>
 *   <li>每帧按滚动偏移把"点亮的灯珠"坐标收集到 3 个亮度桶，用 drawPoints 一次性绘制；
 *       辉光用 1/8 分辨率的小位图 + 双线性放大实现（bloom），开销极低。</li>
 * </ol>
 *
 * <p>灯珠尺寸取整像素，保证点阵严格对齐，得到真实 LED 板的方格感。
 */
public class LedMarqueeView extends View implements Choreographer.FrameCallback {

    /** 全部渲染参数（与设置界面共用同一个实例，改动即时生效） */
    public final MarqueeConfig config = new MarqueeConfig();

    /** 单击回调：全屏模式下用于暂停/继续 */
    public Runnable onTapListener;

    /** 长按回调：全屏模式下用于呼出控制条 */
    public Runnable onLongPressListener;

    // ---------------------------------------------------------------- 几何
    /** 单颗灯珠的边长（整像素） */
    private int led = 8;
    /** 面板列数（横向可见灯珠数） */
    private int panelCols = 1;
    /** 面板行数（= 文本总行高） */
    private int panelRows = 8;
    /** 第 0 列第 0 行灯珠中心 x */
    private float originX = 0f;
    /** 第 0 行灯珠中心 y */
    private float originY = 0f;

    // ---------------------------------------------------------------- 内容
    /** 点阵内容，索引 = 列 * panelRows + 行 */
    private boolean[] lit = new boolean[0];
    /** 内容宽度（灯珠列数） */
    private int stripCols = 0;
    /** 循环周期 = 内容宽度 + 尾部空白 */
    private int cycle = 1;
    /** 当前已构建的参数指纹 */
    private String signature = "";

    // ---------------------------------------------------------------- 图层
    private Bitmap bgBitmap;
    private Shader glossShader;
    private Bitmap glowBitmap;
    private final Canvas glowCanvas = new Canvas();

    // ---------------------------------------------------------------- 动画
    private boolean scrolling = false;
    private float scrollCol = 0f;
    private long lastFrameNanos = 0L;
    private boolean framePosted = false;
    private boolean needRebuild = true;
    private final Runnable rebuildTask = new Runnable() {
        @Override
        public void run() {
            rebuild();
        }
    };

    // ---------------------------------------------------------------- 绘制缓存
    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowBlitPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint glossPaint = new Paint();
    private final GestureDetector gestures;

    /** 笔画粗细 1(细) ~ 5(粗) 对应的二值化阈值 */
    private static final int[] STROKE_THRESHOLDS = {200, 172, 148, 122, 96};

    /** 3 个亮度桶的点坐标（x0,y0,x1,y1,...） */
    private final float[][] buckets = new float[3][];
    private final int[] bucketCount = new int[3];

    // ------------------------------------------------------------ 常量
    private static final int SUPERSAMPLE = 3;          // 超采样倍数
    private static final float GLYPH_FILL = 0.82f;     // 字形高度占灯珠行数的比例
    private static final float DOT_RATIO = 0.80f;      // 灯珠直径占灯距的比例
    private static final float UNLIT_ALPHA = 0.11f;    // 未点亮灯珠亮度
    private static final int GLOW_DIV = 8;             // 辉光层降采样倍数
    private static final float GLOW_SPREAD = 2.7f;     // 辉光扩散（相对灯距）
    private static final float MIN_COLS_PER_ROW = 2.4f; // 每行灯珠对应的最少横向列数（约 2.8 个汉字宽）
    private static final int ROWS_MIN = 6;
    private static final int ROWS_MAX = 64;
    private static final int MAX_LINES = 6;
    private static final int MAX_CHARS_PER_LINE = 400;
    private static final long MAX_RASTER_PIXELS = 12000000L;
    private static final int MAX_RASTER_COLS = 6000;
    private static final int MAX_POINTS = 400000;
    private static final int MODULE_CELLS = 16;
    private static final long REBUILD_DELAY_MS = 120L;
    private static final int MIN_BRIGHTNESS = 15;
    private static final int MAX_BRIGHTNESS = 160;
    private static final int EDGE_DARK = 0x66000000;
    private static final int PANEL_BASE = 0xFF05070A;

    public LedMarqueeView(Context context) {
        this(context, null);
    }

    public LedMarqueeView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LedMarqueeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new float[0];
        }
        ledPaint.setStrokeCap(Paint.Cap.ROUND);
        glowDotPaint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                performClick();
                if (onTapListener != null) {
                    onTapListener.run();
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (onLongPressListener != null) {
                    onLongPressListener.run();
                }
            }
        });
    }

    // ------------------------------------------------------------ 对外接口

    public boolean isScrolling() {
        return scrolling;
    }

    public void submit() {
        submit(false);
    }

    /**
     * 提交参数变更。会对比参数指纹，只有影响点阵/背景的参数变化才触发重建（带防抖），
     * 速度、亮度、方向一类参数直接下一帧生效。
     */
    public void submit(boolean force) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            needRebuild = true;
            return;
        }
        if (force || !signatureOf(config).equals(signature)) {
            removeCallbacks(rebuildTask);
            postDelayed(rebuildTask, REBUILD_DELAY_MS);
        }
        invalidate();
    }

    /** 开始滚动 */
    public void startScroll() {
        if (scrolling) {
            return;
        }
        scrolling = true;
        lastFrameNanos = 0L;
        scheduleFrame();
    }

    /** 暂停滚动（停在当前位置） */
    public void pauseScroll() {
        if (!scrolling) {
            return;
        }
        scrolling = false;
        framePosted = false;
        Choreographer.getInstance().removeFrameCallback(this);
        invalidate();
    }

    /** 回到起点 */
    public void restartScroll() {
        scrollCol = 0f;
        invalidate();
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (scrolling) {
            scheduleFrame();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        framePosted = false;
        Choreographer.getInstance().removeFrameCallback(this);
        removeCallbacks(rebuildTask);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ 重建

    /** 重建点阵与各图层。只在设置变化/旋转时执行，耗时通常在几毫秒量级。 */
    private void rebuild() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            needRebuild = true;
            return;
        }
        MarqueeConfig c = config;

        List<String> lines = normalizeLines(c.text);
        int rowsPerLine = clamp(c.rows, ROWS_MIN, ROWS_MAX);
        int gapRows = Math.max(2, rowsPerLine / 5);
        int totalRows = lines.size() * rowsPerLine + (lines.size() - 1) * gapRows;

        // 灯珠边长：先让文字高度占满约 88% 的可用高度；
        // 再保证横向至少能看到约 2.8 个中文字符（中文一个字宽约 0.82*rows 颗灯珠），
        // 这样手机竖屏、平板横屏都能保持可读的字数与比例。
        int size = (int) Math.floor(vh * 0.88f / totalRows);
        int minCols = Math.max(12, Math.round(rowsPerLine * MIN_COLS_PER_ROW));
        size = Math.min(size, Math.max(3, (int) Math.floor(vw / (float) minCols)));
        size = Math.max(size, 3);
        led = size;
        panelRows = totalRows;
        panelCols = Math.max(1, vw / led);
        // 灯珠中心坐标（把半格偏移折进原点，绘制时直接加列/行号即可）
        originX = (vw - panelCols * led) / 2f + led * 0.5f;
        originY = (vh - panelRows * led) / 2f + led * 0.5f;

        // ---- 文字 -> 点阵 ----
        int threshold = STROKE_THRESHOLDS[clamp(c.strokeWeight, 1, 5) - 1];
        GlyphBand[] bands = new GlyphBand[lines.size()];
        int maxCols = 1;
        for (int i = 0; i < lines.size(); i++) {
            GlyphBand band = rasterizeLine(lines.get(i), rowsPerLine, threshold);
            bands[i] = band;
            if (band.cols > maxCols) {
                maxCols = band.cols;
            }
        }

        stripCols = maxCols;
        lit = new boolean[stripCols * panelRows];
        for (int i = 0; i < bands.length; i++) {
            GlyphBand band = bands[i];
            int rowBase = i * (rowsPerLine + gapRows);
            for (int x = 0; x < band.cols; x++) {
                int src = x * rowsPerLine;
                int dst = x * panelRows + rowBase;
                for (int y = 0; y < rowsPerLine; y++) {
                    if (band.bits[src + y]) {
                        lit[dst + y] = true;
                    }
                }
            }
        }
        cycle = stripCols + Math.max(4, rowsPerLine);
        if (cycle <= 0) {
            cycle = 1;
        }
        scrollCol = scrollCol % cycle;

        // ---- 图层 ----
        ensureGlowBitmap(vw, vh);
        buildBackground(vw, vh);
        buildGloss(vw, vh);
        allocPoints();

        signature = signatureOf(c);
        needRebuild = false;
        invalidate();
    }

    /**
     * 文字 -> 二值灯珠点阵。
     *
     * <p>关键点：以 3 倍超采样绘制字形，再对每个灯珠单元求平均覆盖率并与阈值比较。
     * 这等价于一次盒式低通滤波 + 二值化，比直接在低分辨率上取阈值更能保住中文笔画。
     *
     * @return 一行的点阵数据，索引 = 列 * rows + 行
     */
    private GlyphBand rasterizeLine(String line, int rows, int threshold) {
        int ss = SUPERSAMPLE;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setTextSize(rows * GLYPH_FILL * ss);
        paint.setColor(Color.WHITE);

        float textWidth = paint.measureText(line);
        // 限制单行点阵总宽，避免"超长文本 + 超高字高"时位图过大
        int cols = Math.min(MAX_RASTER_COLS, Math.max(1, (int) Math.ceil(textWidth / ss) + 2));
        int bw = cols * ss;
        int bh = rows * ss;
        if (bw <= 0 || bh <= 0 || (long) bw * bh > MAX_RASTER_PIXELS) {
            return new GlyphBand(1, new boolean[rows]);
        }

        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint.FontMetrics fm = paint.getFontMetrics();
        // 让字形在 rows 行内垂直居中
        float baseline = (bh - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        canvas.drawText(line, ss, baseline, paint);

        int[] pixels = new int[bw * bh];
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh);
        bmp.recycle();

        boolean[] bits = new boolean[cols * rows];
        float inv = 1f / (ss * ss);
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int sum = 0;
                int base = cy * ss * bw + cx * ss;
                for (int sy = 0; sy < ss; sy++) {
                    int rowOffset = base + sy * bw;
                    for (int sx = 0; sx < ss; sx++) {
                        sum += (pixels[rowOffset + sx] >>> 24) & 0xFF;
                    }
                }
                bits[cx * rows + cy] = sum * inv > threshold;
            }
        }
        return new GlyphBand(cols, bits);
    }

    /** 文本 -> 行列表：换行、裁掉首尾空行、限制行数与每行长度 */
    private List<String> normalizeLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] raw = normalized.split("\n", -1);
        List<String> parts = new ArrayList<>();
        for (String item : raw) {
            parts.add(trimEnd(item));
        }
        while (parts.size() > 1 && parts.get(parts.size() - 1).isEmpty()) {
            parts.remove(parts.size() - 1);
        }
        while (parts.size() > 1 && parts.get(0).isEmpty()) {
            parts.remove(0);
        }
        if (parts.isEmpty()) {
            parts.add(" ");
        }

        List<String> limited = new ArrayList<>();
        int count = Math.min(parts.size(), MAX_LINES);
        for (int i = 0; i < count; i++) {
            String line = parts.get(i);
            if (line.isEmpty()) {
                line = " ";
            }
            if (line.length() > MAX_CHARS_PER_LINE) {
                line = line.substring(0, MAX_CHARS_PER_LINE);
            }
            limited.add(line);
        }
        return limited;
    }

    private static String trimEnd(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /** 一行的点阵数据，索引 = 列 * rows + 行 */
    private static final class GlyphBand {
        final int cols;
        final boolean[] bits;

        GlyphBand(int cols, boolean[] bits) {
            this.cols = cols;
            this.bits = bits;
        }
    }
    // ------------------------------------------------------------ 背景图层

    /** 未点亮灯珠：做成 8x8 颗灯珠一块的重复贴图，避免逐颗绘制（上万颗也只画一次）。 */
    private Bitmap makeDotTile(int ledPx, int color, boolean noise) {
        int n = 8;
        while (n > 2 && n * ledPx > 512) {
            n /= 2;
        }
        int size = n * ledPx;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        float radius = ledPx * DOT_RATIO * 0.5f;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                float k = noise ? 0.60f + 0.40f * hash01(i * n + j) : 1f;
                int alpha = clamp((int) (UNLIT_ALPHA * 255f * k), 3, 70);
                paint.setColor(Color.argb(alpha, red, green, blue));
                canvas.drawCircle((i + 0.5f) * ledPx, (j + 0.5f) * ledPx, radius, paint);
            }
        }
        return bmp;
    }

    /** 背景：面板底色 + 未亮灯珠 + 模块网格 + 渐晕 + 四边压暗 */
    private void buildBackground(int vw, int vh) {
        MarqueeConfig c = config;
        LedPalette.LedColor palette = LedPalette.of(c.colorIndex);
        // 不主动 recycle：位图可能仍在渲染线程使用中，交给 GC 回收更安全
        bgBitmap = null;

        Bitmap bmp = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(mix(PANEL_BASE, palette.led, 0.10f));

        if (c.showUnlit) {
            Bitmap tile = makeDotTile(led, palette.led, c.ledNoise);
            Paint paint = new Paint();
            BitmapShader shader = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            // 让未点亮灯珠的点阵与点亮灯珠严格同相位（面板居中时原点不一定落在灯距整数倍上）
            Matrix matrix = new Matrix();
            matrix.setTranslate(originX - led * 0.5f, originY - led * 0.5f);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            canvas.drawRect(0f, 0f, vw, vh, paint);
            tile.recycle();
        }

        if (c.moduleGrid) {
            Paint gridPaint = new Paint();
            gridPaint.setColor(Color.BLACK);
            gridPaint.setAlpha(110);
            gridPaint.setStrokeWidth(Math.max(1f, led * 0.08f));
            int module = Math.max(4, MODULE_CELLS);
            for (int col = 0; col <= panelCols; col++) {
                if (col % module != 0) {
                    continue;
                }
                float x = originX - led * 0.5f + col * led;
                canvas.drawLine(x, 0f, x, vh, gridPaint);
            }
            for (int row = 0; row <= panelRows; row++) {
                if (row % module != 0) {
                    continue;
                }
                float y = originY - led * 0.5f + row * led;
                canvas.drawLine(0f, y, vw, y, gridPaint);
            }
        }

        // 渐晕：模拟面板凹进机身的纵深
        Paint vignette = new Paint();
        vignette.setShader(new RadialGradient(
                vw / 2f, vh / 2f, Math.max(vw, vh) * 0.78f,
                new int[]{0x00000000, 0x00000000, 0x88000000},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, vw, vh, vignette);

        // 四边内侧压暗
        float edge = Math.max(2f, Math.min(vw, vh) * 0.05f);
        Paint edgePaint = new Paint();
        edgePaint.setShader(new LinearGradient(0f, 0f, 0f, edge, EDGE_DARK, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, vw, edge, edgePaint);
        edgePaint.setShader(new LinearGradient(0f, vh, 0f, vh - edge, EDGE_DARK, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, vh - edge, vw, vh, edgePaint);
        edgePaint.setShader(new LinearGradient(0f, 0f, edge, 0f, EDGE_DARK, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, edge, vh, edgePaint);
        edgePaint.setShader(new LinearGradient(vw, 0f, vw - edge, 0f, EDGE_DARK, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(vw - edge, 0f, vw, vh, edgePaint);

        bgBitmap = bmp;
    }

    /** 玻璃反光层：逐帧渐变绘制，省掉一张全屏位图 */
    private void buildGloss(int vw, int vh) {
        if (!config.gloss) {
            glossShader = null;
            return;
        }
        glossShader = new LinearGradient(
                0f, 0f, vw * 0.85f, vh * 0.95f,
                new int[]{0x30FFFFFF, 0x0CFFFFFF, 0x00000000, 0x1A000000},
                new float[]{0f, 0.30f, 0.52f, 1f},
                Shader.TileMode.CLAMP);
    }

    private void ensureGlowBitmap(int vw, int vh) {
        int gw = Math.max(1, vw / GLOW_DIV);
        int gh = Math.max(1, vh / GLOW_DIV);
        Bitmap current = glowBitmap;
        if (current == null || current.getWidth() != gw || current.getHeight() != gh) {
            Bitmap fresh = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            glowBitmap = fresh;
            glowCanvas.setBitmap(fresh);
        }
    }

    private void allocPoints() {
        int maxPoints = Math.min((panelCols + 4) * panelRows + 8, MAX_POINTS);
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i].length < maxPoints * 2) {
                buckets[i] = new float[maxPoints * 2];
            }
            bucketCount[i] = 0;
        }
    }

    // ------------------------------------------------------------ 绘制

    /** 收集当前可见的点亮灯珠，按伪随机分到 3 个亮度桶（模拟灯珠个体亮度差异） */
    private void collectPoints() {
        bucketCount[0] = 0;
        bucketCount[1] = 0;
        bucketCount[2] = 0;

        MarqueeConfig c = config;
        float raw = c.smooth ? scrollCol : (float) Math.floor(scrollCol);
        float base = (c.direction == 0) ? raw : -raw;
        int firstCol = (int) Math.floor(base) - 1;
        int lastCol = (int) Math.ceil(base + panelCols) + 1;
        int rows = panelRows;
        int cycleLocal = cycle;
        float ledSize = led;
        float ox = originX;
        float oy = originY;

        for (int col = firstCol; col <= lastCol; col++) {
            int src = col % cycleLocal;
            if (src < 0) {
                src += cycleLocal;
            }
            if (src >= stripCols) {
                continue;
            }
            float px = ox + (col - base) * ledSize;
            int index = src * rows;
            for (int y = 0; y < rows; y++) {
                if (!lit[index + y]) {
                    continue;
                }
                int bucket = (src * 7 + y * 13) % 3;
                float[] arr = buckets[bucket];
                int n = bucketCount[bucket];
                if (n * 2 + 1 >= arr.length) {
                    continue;
                }
                arr[n * 2] = px;
                arr[n * 2 + 1] = oy + y * ledSize;
                bucketCount[bucket] = n + 1;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        if (needRebuild) {
            rebuild();
        }
        if (lit.length == 0 || panelCols <= 0 || panelRows <= 0) {
            return;
        }

        MarqueeConfig c = config;
        LedPalette.LedColor palette = LedPalette.of(c.colorIndex);
        float brightness = clamp(c.brightness, MIN_BRIGHTNESS, MAX_BRIGHTNESS) / 100f;

        if (c.mirror) {
            canvas.save();
            canvas.scale(-1f, 1f, vw / 2f, vh / 2f);
        }

        if (bgBitmap != null) {
            canvas.drawBitmap(bgBitmap, 0f, 0f, bgPaint);
        }

        collectPoints();

        if (c.glow) {
            drawGlow(canvas, palette, brightness);
        }
        drawLeds(canvas, palette, brightness);

        if (c.gloss && glossShader != null) {
            glossPaint.setShader(glossShader);
            canvas.drawRect(0f, 0f, vw, vh, glossPaint);
        }

        if (c.mirror) {
            canvas.restore();
        }
    }

    /**
     * 辉光：先在 1/8 分辨率的小图上把灯珠画成较大的点，再放大回全屏。
     * 双线性放大天然产生柔和模糊，比 BlurMaskFilter 快得多，也更稳定。
     */
    private void drawGlow(Canvas canvas, LedPalette.LedColor palette, float brightness) {
        Bitmap glow = glowBitmap;
        if (glow == null) {
            return;
        }
        glow.eraseColor(0);
        glowCanvas.setBitmap(glow);
        glowCanvas.save();
        glowCanvas.scale(1f / GLOW_DIV, 1f / GLOW_DIV);
        glowDotPaint.setColor(palette.glow);
        glowDotPaint.setAlpha(clamp((int) (170f * Math.min(1f, brightness)), 24, 210));
        glowDotPaint.setStrokeWidth(led * GLOW_SPREAD);
        for (int i = 0; i < buckets.length; i++) {
            int n = bucketCount[i];
            if (n > 0) {
                glowCanvas.drawPoints(buckets[i], 0, n * 2, glowDotPaint);
            }
        }
        glowCanvas.restore();

        glowBlitPaint.setAlpha(clamp((int) (240f * Math.min(1.15f, brightness)), 40, 255));
        canvas.save();
        canvas.scale((float) GLOW_DIV, (float) GLOW_DIV, 0f, 0f);
        canvas.drawBitmap(glow, 0f, 0f, glowBlitPaint);
        canvas.restore();
    }

    /** 灯珠本体：3 个亮度桶各一次 drawPoints，圆头笔帽即圆点 */
    private void drawLeds(Canvas canvas, LedPalette.LedColor palette, float brightness) {
        int core = scaleColor(palette.led, brightness);
        for (int i = 0; i < buckets.length; i++) {
            int n = bucketCount[i];
            if (n <= 0) {
                continue;
            }
            ledPaint.setColor(core);
            ledPaint.setAlpha(i == 0 ? 255 : (i == 1 ? 236 : 214));
            ledPaint.setStrokeWidth(led * DOT_RATIO);
            canvas.drawPoints(buckets[i], 0, n * 2, ledPaint);
        }
    }

    // ------------------------------------------------------------ 交互

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestures.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    // ------------------------------------------------------------ 动画

    private void scheduleFrame() {
        if (framePosted) {
            return;
        }
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        framePosted = false;
        if (!scrolling || !isAttachedToWindow()) {
            return;
        }
        float dt = lastFrameNanos == 0L
                ? 0f
                : (frameTimeNanos - lastFrameNanos) / 1000000000f;
        lastFrameNanos = frameTimeNanos;
        if (dt > 0f && dt < 0.2f) {
            scrollCol += config.speed * dt;
            // 防止长时间运行后浮点精度下降
            scrollCol = scrollCol % cycle;
        }
        invalidate();
        scheduleFrame();
    }

    // ------------------------------------------------------------ 工具

    private String signatureOf(MarqueeConfig c) {
        return c.text + '|' + c.rows + '|' + c.strokeWeight + '|' + c.colorIndex + '|'
                + c.showUnlit + '|' + c.ledNoise + '|' + c.moduleGrid + '|' + c.gloss + '|'
                + getWidth() + '|' + getHeight();
    }

    private static int clamp(int value, int lo, int hi) {
        if (value < lo) {
            return lo;
        }
        return value > hi ? hi : value;
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return Color.argb(255,
                (int) (ar + (br - ar) * t),
                (int) (ag + (bg - ag) * t),
                (int) (ab + (bb - ab) * t));
    }

    private static int scaleColor(int color, float factor) {
        return Color.argb(255,
                clamp((int) (((color >> 16) & 0xFF) * factor), 0, 255),
                clamp((int) (((color >> 8) & 0xFF) * factor), 0, 255),
                clamp((int) ((color & 0xFF) * factor), 0, 255));
    }

    private static float hash01(int seed) {
        int x = seed * 374761393 + 668265263;
        x = (x ^ (x >>> 13)) * 1274126177;
        x = x ^ (x >>> 16);
        return ((x >>> 8) & 0xFF) / 255f;
    }
}