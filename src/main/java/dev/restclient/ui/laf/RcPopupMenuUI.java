package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.JComponent;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Menus with rounded corners and a hairline outline; the shadow comes from the popup factory. */
public class RcPopupMenuUI extends BasicPopupMenuUI {
    public static final int ARC = 10;

    public static ComponentUI createUI(JComponent c) {
        return new RcPopupMenuUI();
    }

    @Override
    public void installDefaults() {
        super.installDefaults();
        LookAndFeel.installProperty(popupMenu, "opaque", Boolean.FALSE);
        popupMenu.putClientProperty("rc.shadowArc", ARC);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        Graphics2D g2 = Paint.smooth(g);
        Paint.fill(g2, 0, 0, c.getWidth(), c.getHeight(), ARC, Theme.menuBg());
        Paint.outline(g2, 0, 0, c.getWidth(), c.getHeight(), ARC, 1f,
                Theme.dark() ? Theme.mix(Theme.separator(), java.awt.Color.WHITE, 0.08f) : Theme.mix(Theme.separator(), Theme.fieldBorder(), 0.4f));
        g2.dispose();
    }

    @Override
    public void update(Graphics g, JComponent c) {
        paint(g, c);
    }
}
