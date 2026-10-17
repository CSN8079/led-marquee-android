import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * 生成"灯珠配色一览"图。直接解析 LedPalette.java，保证与代码完全一致。
 *
 * 运行:
 *   javac -encoding UTF-8 -d <tmp> tools/preview/ColorPalettePreview.java
 *   java -cp <tmp> ColorPalettePreview app/src/main/java/com/ledmatrix/marquee/LedPalette.java docs/colors.png
 */
public final class ColorPalettePreview {

    private static final int COLS = 5;
    private static final int TILE_W = 360;
    private static final int PANEL_H = 180;
    private static final int TILE_H = 246;
    private static final int GAP = 22;
    private static final int HEADER = 74;
    private static final int ROWS = 16;
    private static final int SS = 3;

    private static final class Entry {
        final String name;
        final int[] rgb;

        Entry(String name, int[] rgb) {
            this.name = name;
            this.rgb = rgb;
        }
    }

    public static void main(String[] args) throws Exception {
        String paletteFile = args.length > 0 ? args[0]
                : "app/src/main/java/com/ledmatrix/marquee/LedPalette.java";
        String outFile = args.length > 1 ? args[1] : "docs/colors.png";

        List<Entry> entries = parse(paletteFile);
        int rows = (entries.size() + COLS - 1) / COLS;
        int W = GAP + COLS * (TILE_W + GAP);
        int H = HEADER + rows * (TILE_H + GAP) + GAP;

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(11, 13, 16));
        g.fillRect(0, 0, W, H);

        g.setColor(new Color(237, 239, 243));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 26));
        g.drawString("灯珠配色 · " + entries.size() + " 种", GAP, 46);
        g.setColor(new Color(154, 163, 178));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        g.drawString("默认红色 LED，可在设置页的色板中一键切换", GAP + 260, 45);

        for (int i = 0; i < entries.size(); i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = GAP + col * (TILE_W + GAP);
            int y = HEADER + row * (TILE_H + GAP);
            drawTile(g, x, y, entries.get(i));
        }

        g.dispose();
        File file = new File(outFile);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", file);
        System.out.println("已生成配色图：" + file.getAbsolutePath() + " (" + W + "x" + H + ")");
    }

    private static void drawTile(Graphics2D g, int x, int y, Entry entry) {
        int r = entry.rgb[0];
        int gr = entry.rgb[1];
        int b = entry.rgb[2];

        Graphics2D panel = (Graphics2D) g.create();
        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, TILE_W, PANEL_H, 18, 18);
        panel.setClip(shape);
        panel.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        panel.setColor(new Color((int) (5 + r * 0.10), (int) (7 + gr * 0.10),
                (int) (10 + b * 0.10)));
        panel.fill(shape);

        boolean[][] bits = rasterize("LED", ROWS);
        int cols = bits.length;
        int led = Math.min((int) Math.floor(PANEL_H * 0.86 / ROWS), TILE_W / cols);
        led = Math.max(3, led);
        float originX = x + (TILE_W - cols * led) / 2f + led * 0.5f;
        float originY = y + (PANEL_H - ROWS * led) / 2f + led * 0.5f;
        float dotR = led * 0.80f * 0.5f;

        // 未点亮灯珠
        for (int c = -2; c < cols + 4; c++) {
            for (int row = -2; row < ROWS + 2; row++) {
                float k = 0.60f + 0.40f * hash01(c * 31 + row * 17);
                int alpha = Math.max(3, Math.min(70, (int) (0.11f * 255 * k)));
                panel.setColor(new Color(r, gr, b, alpha));
                float cx = originX + c * led;
                float cy = originY + row * led;
                panel.fill(new Ellipse2D.Float(cx - dotR, cy - dotR, dotR * 2, dotR * 2));
            }
        }

        // 辉光（1/8 分辨率 + 双线性放大）
        int div = 8;
        BufferedImage glow = new BufferedImage(Math.max(1, TILE_W / div),
                Math.max(1, PANEL_H / div), BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = glow.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double s = (double) glow.getWidth() / TILE_W;
        gg.scale(s, s);
        gg.translate(-x, -y);
        gg.setColor(new Color(r, gr, b, 170));
        float glowR = led * 2.7f * 0.5f;
        for (int c = 0; c < cols; c++) {
            for (int row = 0; row < ROWS; row++) {
                if (!bits[c][row]) {
                    continue;
                }
                float cx = originX + c * led;
                float cy = originY + row * led;
                gg.fill(new Ellipse2D.Float(cx - glowR, cy - glowR, glowR * 2, glowR * 2));
            }
        }
        gg.dispose();
        panel.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        panel.drawImage(glow, x, y, TILE_W, PANEL_H, null);

        // 灯珠本体
        panel.setStroke(new BasicStroke(led * 0.80f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        for (int bucket = 0; bucket < 3; bucket++) {
            int alpha = bucket == 0 ? 255 : (bucket == 1 ? 236 : 214);
            panel.setColor(new Color(r, gr, b, alpha));
            for (int c = 0; c < cols; c++) {
                for (int row = 0; row < ROWS; row++) {
                    if (!bits[c][row] || (c * 7 + row * 13) % 3 != bucket) {
                        continue;
                    }
                    float cx = originX + c * led;
                    float cy = originY + row * led;
                    panel.fill(new Ellipse2D.Float(cx - dotR, cy - dotR, dotR * 2, dotR * 2));
                }
            }
        }

        // 玻璃反光
        panel.setPaint(new java.awt.GradientPaint(x, y, new Color(255, 255, 255, 42),
                x + TILE_W * 0.9f, y + PANEL_H, new Color(0, 0, 0, 22)));
        panel.fillRect(x, y, TILE_W, PANEL_H);
        panel.dispose();

        // 外框 + 名称
        g.setColor(new Color(38, 43, 52));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Float(x, y, TILE_W, PANEL_H, 18, 18));

        g.setColor(new Color(237, 239, 243));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 19));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(entry.name, x + 2, y + PANEL_H + 30);
        g.setColor(new Color(154, 163, 178));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        g.drawString(String.format("#%02X%02X%02X", r, gr, b),
                x + 2 + fm.stringWidth(entry.name) + 12, y + PANEL_H + 30);
    }

    private static List<Entry> parse(String file) throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        Pattern p = Pattern.compile(
                "new LedColor\\(\"([^\"]+)\",\\s*0x([0-9A-Fa-f]{8}),\\s*0x([0-9A-Fa-f]{8})\\)");
        Matcher m = p.matcher(src);
        List<Entry> list = new ArrayList<>();
        while (m.find()) {
            int argb = (int) Long.parseLong(m.group(2), 16);
            list.add(new Entry(m.group(1), new int[]{
                    (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF}));
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("未能从 " + file + " 解析出配色");
        }
        return list;
    }

    /** 3 倍超采样 + 覆盖率阈值，与应用内算法一致 */
    private static boolean[][] rasterize(String line, int rows) {
        Font font = new Font("Microsoft YaHei", Font.PLAIN,
                Math.max(4, Math.round(rows * 0.82f * SS)));
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
                bits[cx][cy] = sum * inv > 148;
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