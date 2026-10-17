import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 生成"矢量文字模式"效果图（桌面预览，不参与 APK）。
 *
 * 运行:
 *   javac -encoding UTF-8 -d <tmp> tools/preview/VectorPreview.java
 *   java -cp <tmp> VectorPreview docs/vector-preview.png
 */
public final class VectorPreview {

    private static final int W = 2400;
    private static final int H = 1180;

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "docs/vector-preview.png";
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setPaint(new GradientPaint(0, 0, new Color(18, 20, 26), 0, H, new Color(7, 8, 11)));
        g.fillRect(0, 0, W, H);

        g.setColor(new Color(237, 239, 243));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 38));
        g.drawString("矢量文字模式", 80, 92);
        g.setColor(new Color(154, 163, 178));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 20));
        g.drawString("真实字体渲染（抗锯齿）· 可调字号 / 字体 / 字形 / 字间距 / 行距 / 发光 / 描边",
                80, 132);

        drawCard(g, 80, 180, 2240, 280,
                "默认黑体 · 粗体 · 字号 60% · 发光",
                "欢迎光临  ·  矢量文字",
                new Color(255, 52, 24), new Color(255, 16, 0),
                new Font("Microsoft YaHei", Font.BOLD, 150), true);

        drawCard(g, 80, 490, 2240, 250,
                "衬线体 · 常规 · 字号 44% · 描边",
                "Hello  Vector  Text  2026",
                new Color(255, 160, 0), new Color(255, 114, 0),
                new Font("SimSun", Font.PLAIN, 112), false);

        drawCard(g, 80, 770, 2240, 250,
                "等宽体 · 粗体 · 字间距 +12",
                "1234567890  ABCabc",
                new Color(43, 255, 120), new Color(0, 226, 74),
                new Font("Consolas", Font.BOLD, 104), true);

        g.setColor(new Color(107, 116, 132));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 17));
        g.drawString("点阵 LED 与矢量文字可在设置页「渲染方式」中随时切换，滚动 / 方向 / 速度 / 亮度 / 镜像等参数通用。",
                80, H - 40);

        g.dispose();
        File file = new File(out);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        ImageIO.write(img, "png", file);
        System.out.println("已生成矢量预览：" + file.getAbsolutePath() + " (" + W + "x" + H + ")");
    }

    private static void drawCard(Graphics2D g, int x, int y, int w, int h,
                                 String caption, String text, Color color, Color glow,
                                 Font font, boolean withGlow) {
        Graphics2D c = (Graphics2D) g.create();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        c.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, 22, 22);
        c.setClip(shape);
        c.setColor(new Color((int) (6 + color.getRed() * 0.045),
                (int) (7 + color.getGreen() * 0.045),
                (int) (9 + color.getBlue() * 0.045)));
        c.fill(shape);

        c.setFont(new Font("Microsoft YaHei", Font.PLAIN, 18));
        c.setColor(new Color(154, 163, 178));
        c.drawString(caption, x + 34, y + 46);

        c.setFont(font);
        FontMetrics fm = c.getFontMetrics();
        float textX = x + (w - fm.stringWidth(text)) / 2f;
        float textY = y + h * 0.5f + (fm.getAscent() - fm.getDescent()) * 0.55f + 18;

        if (withGlow) {
            // 用加粗轮廓叠出柔和光晕（近似 bloom）
            for (int i = 4; i >= 1; i--) {
                c.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 16));
                c.setStroke(new BasicStroke(i * 9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                c.drawString(text, textX, textY);
            }
        }
        c.setStroke(new BasicStroke(1f));
        c.setColor(color);
        c.drawString(text, textX, textY);

        c.dispose();
        g.setColor(new Color(38, 43, 52));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 22, 22));
    }
}