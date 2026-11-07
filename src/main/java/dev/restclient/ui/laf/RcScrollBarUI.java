package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/** Slim scroll bar: no arrow buttons, a rounded thumb, an invisible track. */
public class RcScrollBarUI extends BasicScrollBarUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcScrollBarUI();
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return zero();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return zero();
    }

    private static JButton zero() {
        JButton b = new JButton();
        Dimension d = new Dimension(0, 0);
        b.setPreferredSize(d);
        b.setMinimumSize(d);
        b.setMaximumSize(d);
        b.setFocusable(false);
        return b;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
        // transparent
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.isEmpty() || !scrollbar.isEnabled()) return;
        Graphics2D g2 = Paint.smooth(g);
        int pad = 2;
        int w = r.width - 2 * pad;
        int h = r.height - 2 * pad;
        Paint.fill(g2, r.x + pad, r.y + pad, w, h, Math.min(w, h), isThumbRollover() || isDragging ? Theme.thumbHover() : Theme.thumb());
        g2.dispose();
    }

    @Override
    protected void setThumbRollover(boolean active) {
        super.setThumbRollover(active);
        scrollbar.repaint();
    }
}
