package dev.restclient.ui.laf;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/** Small painting helpers shared by the UI delegates. */
public final class Paint {
    private Paint() {
    }

    public static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    /**
     * Text smoothing as the OS does it, for text we draw ourselves: ClearType (sub-pixel, LCD) on Windows when
     * it's on, greyscale elsewhere. RcLookAndFeel reads the desktop's setting into UIManager; Swing's own
     * components use it automatically, and this applies the same to a custom-painted Graphics.
     */
    public static Graphics2D text(Graphics2D g) {
        Object aa = javax.swing.UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa != null ? aa : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Object contrast = javax.swing.UIManager.get(RenderingHints.KEY_TEXT_LCD_CONTRAST);
        if (contrast != null) g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, contrast);
        return g;
    }

    public static void fill(Graphics2D g, float x, float y, float w, float h, float arc, Color c) {
        if (c == null || w <= 0 || h <= 0) return;
        g.setColor(c);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
    }

    /** Strokes a rounded outline whose outer edge sits exactly on the given bounds. */
    public static void outline(Graphics2D g, float x, float y, float w, float h, float arc, float width, Color c) {
        if (c == null || w <= 0 || h <= 0) return;
        g.setColor(c);
        g.setStroke(new BasicStroke(width));
        float half = width / 2f;
        g.draw(new RoundRectangle2D.Float(x + half, y + half, w - width, h - width, Math.max(0, arc - half), Math.max(0, arc - half)));
    }

    /**
     * A raised control body, as on macOS push buttons: a soft contact shadow underneath, an optional vertical
     * gradient and a hairline highlight along the top edge. The body is 1px shorter than the bounds to make room
     * for the shadow.
     */
    public static void raised(Graphics2D g, float w, float h, float arc, Color top, Color bottom, Color highlight, Color shadow) {
        if (top == null || w <= 0 || h <= 1) return;
        if (shadow != null) fill(g, 0, 1, w, h - 1, arc, shadow);
        g.setPaint(bottom == null || bottom.equals(top) ? top : new java.awt.GradientPaint(0, 0, top, 0, h - 1, bottom));
        g.fill(new RoundRectangle2D.Float(0, 0, w, h - 1, arc, arc));
        if (highlight != null) {
            java.awt.Shape clip = g.getClip();
            g.clip(new RoundRectangle2D.Float(0, 0, w, h - 1, arc, arc));
            g.setColor(highlight);
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h + arc, arc, arc));
            g.setClip(clip);
        }
    }

    public static Color alpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * a));
    }
}
