package dev.restclient.ui;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SplashScreen;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The launch splash. The picture is drawn by {@link #main} at build time into {@code splash.png} (and a 2x
 * version for HiDPI screens) and named in the jar manifest, so the JVM shows it before any app code runs.
 * While the app starts, {@link #progress} draws a filling bar on it; it closes on its own when the window opens.
 */
public final class Splash {
    public static final int W = 440;
    public static final int H = 280;
    /** Room around the card for its shadow. */
    private static final int M = 20;
    private static final int TRACK_W = 120;
    private static final int TRACK_Y = H - M - 42;

    private Splash() {
    }

    /** Writes splash.png and splash@2x.png into the given folder. Run by {@code Build.java}. */
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(out);
        ImageIO.write(render(1), "png", out.resolve("splash.png").toFile());
        ImageIO.write(render(2), "png", out.resolve("splash@2x.png").toFile());
    }

    static BufferedImage render(int scale) {
        BufferedImage img = new BufferedImage(W * scale, H * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        int cw = W - 2 * M;
        int ch = H - 2 * M;
        // soft blurred shadow, doubled for a little more depth
        Shadow.paint(g, M, M, cw, ch, 22);
        Shadow.paint(g, M, M, cw, ch, 22);
        RoundRectangle2D card = new RoundRectangle2D.Float(M, M, cw, ch, 22, 22);
        g.setPaint(new GradientPaint(0, M, new Color(0x2C2C30), 0, M + ch, new Color(0x1A1A1D)));
        g.fill(card);
        // accent glow behind the icon
        g.setClip(card);
        java.awt.RadialGradientPaint glow = new java.awt.RadialGradientPaint(W / 2f, M + 70, 150,
                new float[]{0f, 1f}, new Color[]{new Color(90, 115, 237, 80), new Color(90, 115, 237, 0)});
        g.setPaint(glow);
        g.fillRect(M, M, cw, ch);
        g.setClip(null);
        // hairline highlight along the top edge
        g.setColor(new Color(255, 255, 255, 30));
        g.draw(new RoundRectangle2D.Float(M + 0.5f, M + 0.5f, cw - 1, ch - 1, 22, 22));

        int icon = 64;
        BufferedImage ic = Theme.appIcon(icon * scale);
        g.drawImage(ic, (W - icon) / 2, M + 40, icon, icon, null);

        Font base = Theme.uiFont().deriveFont(13f);
        g.setFont(base.deriveFont(Font.BOLD, 22f));
        center(g, "REST Client", M + 40 + icon + 36, new Color(0xF5F5F7));
        g.setFont(base.deriveFont(Font.PLAIN, 12.5f));
        center(g, "Collections · Environments · cURL", M + 40 + icon + 58, new Color(0x9E9EA6));
        // empty progress track
        g.setColor(new Color(255, 255, 255, 28));
        g.fill(new RoundRectangle2D.Float((W - TRACK_W) / 2f, TRACK_Y, TRACK_W, 3, 3, 3));
        g.dispose();
        return img;
    }

    private static void center(Graphics2D g, String s, int baseline, Color c) {
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, (W - fm.stringWidth(s)) / 2f, baseline);
    }

    /** Fills the bar on the JVM's splash to {@code fraction}; does nothing if there's no splash. */
    public static void progress(double fraction) {
        try {
            SplashScreen s = SplashScreen.getSplashScreen();
            if (s == null || !s.isVisible()) return;
            Graphics2D g = s.createGraphics();
            // the overlay is drawn on top of the image; scale it if the image is larger than our layout
            double k = s.getSize().getWidth() / W;
            g.scale(k, k);
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float w = (float) (TRACK_W * Math.max(0, Math.min(1, fraction)));
            g.setColor(new Color(0x5A73ED));
            g.fill(new RoundRectangle2D.Float((W - TRACK_W) / 2f, TRACK_Y, Math.max(3, w), 3, 3, 3));
            g.dispose();
            s.update();
        } catch (IllegalStateException | UnsupportedOperationException e) {
            // splash already closed
        }
    }
}
