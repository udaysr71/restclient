package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;

/** Thin rounded progress bar. */
public class RcProgressBarUI extends BasicProgressBarUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcProgressBarUI();
    }

    @Override
    protected void paintDeterminate(Graphics g, JComponent c) {
        Insets in = progressBar.getInsets();
        int w = c.getWidth() - in.left - in.right;
        int h = c.getHeight() - in.top - in.bottom;
        Graphics2D g2 = Paint.smooth(g);
        Paint.fill(g2, in.left, in.top, w, h, h, Theme.subtleBg());
        int amount = getAmountFull(in, w, h);
        Paint.fill(g2, in.left, in.top, amount, h, h, Theme.accent());
        g2.dispose();
    }

    @Override
    protected void paintIndeterminate(Graphics g, JComponent c) {
        Insets in = progressBar.getInsets();
        int w = c.getWidth() - in.left - in.right;
        int h = c.getHeight() - in.top - in.bottom;
        Graphics2D g2 = Paint.smooth(g);
        Paint.fill(g2, in.left, in.top, w, h, h, Theme.subtleBg());
        boxRect = getBox(boxRect);
        if (boxRect != null) {
            Rectangle r = boxRect;
            Paint.fill(g2, r.x, in.top, r.width, h, h, Theme.accent());
        }
        g2.dispose();
    }
}
