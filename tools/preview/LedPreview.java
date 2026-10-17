import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 桌面预览：用与 Android 端 LedMarqueeView 完全相同的点阵算法渲染一张 LED 面板效果图。
 * 仅用于设计校验 / 生成文档图，不参与 APK。
 *
 * 运行:
 *   javac -encoding UTF-8 -d <tmp> tools/preview/LedPreview.java
 *   java -cp <tmp> LedPreview docs/preview.png
 */
public final class LedPreview {

    private static final int W = 2880;
    private static final int H = 900;
    /** 展示图参数：辉光关闭、显示未点亮灯珠、点阵字高 40 行 */
    private static final boolean GLOW = false;
    private static final int SS = 3;
    private static final float GLYPH_FILL = 0.82f;
    private static final float DOT_RATIO = 0.80f;
    private static final float UNLIT_ALPHA = 0.11f;
    private static final int[] THRESHOLDS = {200, 172, 148, 122, 96};
    private static final int THRESHOLD = THRESHOLDS[2];
    private static final int LED_R = 255, LED_G = 52, LED_B = 24;
    private static final int GLOW_R = 255, GLOW_G = 16, GLOW_B = 0;

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "docs/preview.png";
        String[] lines = {
                "欢迎光临  ·  LED 字幕",
                "LED  MATRIX"
        };
        int rowsPerLine = 40;
        int gapRows = 8;
        int totalRows = lines.length * rowsPerLine + (lines.length - 1) * gapRows;

        int led = Math.max(3, (int) Math.floor(H * 0.88 / totalRows));
        int panelCols = W / led;
        float originX = (W - panelCols * led) / 2f + led * 0.5f;
        float originY = (H - totalRows * led) / 2f + led * 0.5f;

        boolean[][][] bands = new boolean[lines.length][][];
        int stripCols = 1;
        for (int i = 0; i < lines.length; i++) {
            bands[i] = rasterize(lines[i], rowsPerLine);
            stripCols = Math.max(stripCols, bands[i].length);
        }

        boolean[] lit = new boolean[stripCols * totalRows];
        for (int i = 0; i < bands.length; i++) {
            boolean[][] band = bands[i];
            int rowBase = i * (rowsPerLine + gapRows);
            for (int x = 0; x < band.length; x++) {
                for (int y = 0; y < rowsPerLine; y++) {
                    if (band[x][y]) {
                        lit[x * totalRows + rowBase + y] = true;
                    }
                }
            }
        }

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new RoundRectangle2D.Float(0, 0, W, H, 34, 34));

        // 面板底色（黑 + 10% 灯珠色）
        g.setColor(new Color(11, 6, 5));
        g.fillRect(0, 0, W, H);

        // 未点亮灯珠
        float dotR = led * DOT_RATIO * 0.5f;
        for (int col = -2; col < panelCols + 2; col++) {
            for (int row = -2; row < totalRows + 2; row++) {
                float k = 0.60f + 0.40f * hash01(col * 31 + row * 17);
                int alpha = Math.max(3, Math.min(70, (int) (UNLIT_ALPHA * 255 * k)));
                g.setColor(new Color(LED_R, LED_G, LED_B, alpha));
                float cx = originX + col * led;
                float cy = originY + row * led;
                g.fill(new java.awt.geom.Ellipse2D.Float(cx - dotR, cy - dotR, dotR * 2, dotR * 2));
            }
        }

        // 辉光层：1/8 分辨率 + 双线性放大（GLOW = false 时整体跳过）
        if (GLOW) {
            int div = 8;
            BufferedImage glow = new BufferedImage(W / div, H / div, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gg = glow.createGraphics();
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gg.scale(1.0 / div, 1.0 / div);
            gg.setColor(new Color(GLOW_R, GLOW_G, GLOW_B, 170));
            float glowR = led * 2.7f * 0.5f;
            for (int col = 0; col < panelCols; col++) {
                for (int row = 0; row < totalRows; row++) {
                    if (col >= stripCols || !lit[col * totalRows + row]) {
                        continue;
                    }
                    float cx = originX + col * led;
                    float cy = originY + row * led;
                    gg.fill(new java.awt.geom.Ellipse2D.Float(cx - glowR, cy - glowR, glowR * 2, glowR * 2));
                }
            }
            gg.dispose();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(glow, 0, 0, W, H, null);
        }

        // 灯珠本体
        g.setStroke(new BasicStroke(led * DOT_RATIO, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int bucket = 0; bucket < 3; bucket++) {
            int alpha = bucket == 0 ? 255 : (bucket == 1 ? 236 : 214);
            g.setColor(new Color(LED_R, LED_G, LED_B, alpha));
            for (int col = 0; col < panelCols; col++) {
                for (int row = 0; row < totalRows; row++) {
                    if (col >= stripCols || !lit[col * totalRows + row]) {
                        continue;
                    }
                    if ((col * 7 + row * 13) % 3 != bucket) {
                        continue;
                    }
                    float cx = originX + col * led;
                    float cy = originY + row * led;
                    g.fill(new java.awt.geom.Ellipse2D.Float(cx - dotR, cy - dotR, dotR * 2, dotR * 2));
                }
            }
        }

        // 渐晕 + 四边压暗
        RadialGradientPaint vignette = new RadialGradientPaint(
                new Point2D.Float(W / 2f, H / 2f), Math.max(W, H) * 0.78f,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), new Color(0, 0, 0, 136)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g.setPaint(vignette);
        g.fillRect(0, 0, W, H);

        // 玻璃反光
        g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 48),
                W * 0.85f, H * 0.95f, new Color(0, 0, 0, 26)));
        g.fillRect(0, 0, W, H);

        g.dispose();
        File file = new File(out);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", file);
        System.out.println("已生成预览：" + file.getAbsolutePath() + "  (" + W + "x" + H + ")");
    }

    /** 文字 -> 二值点阵：3 倍超采样 + 方块覆盖率阈值 */
    private static boolean[][] rasterize(String line, int rows) {
        Font font = new Font("Microsoft YaHei", Font.PLAIN,
                Math.max(4, Math.round(rows * GLYPH_FILL * SS)));
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int textW = fm.stringWidth(line);
        int cols = Math.max(1, (int) Math.ceil(textW / (float) SS) + 2);
        int bw = cols * SS;
        int bh = rows * SS;
        pg.dispose();

        BufferedImage tmp = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tmp.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        tg.setFont(font);
        tg.setColor(Color.WHITE);
        int baseline = (bh - (fm.getDescent() + fm.getAscent())) / 2 + fm.getAscent();
        tg.drawString(line, SS, baseline);
        tg.dispose();

        int[] px = tmp.getRGB(0, 0, bw, bh, null, 0, bw);
        boolean[][] bits = new boolean[cols][rows];
        float inv = 1f / (SS * SS);
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int sum = 0;
                int base = cy * SS * bw + cx * SS;
                for (int sy = 0; sy < SS; sy++) {
                    int rowOffset = base + sy * bw;
                    for (int sx = 0; sx < SS; sx++) {
                        sum += (px[rowOffset + sx] >>> 24) & 0xFF;
                    }
                }
                bits[cx][cy] = sum * inv > THRESHOLD;
            }
        }
        return bits;
    }

    private static float hash01(int seed) {
        int x = seed * 374761393 + 668265263;
        x = (x ^ (x >>> 13)) * 1274126177;
        x = x ^ (x >>> 16);
        return ((x >>> 8) & 0xFF) / 255f;
    }
}