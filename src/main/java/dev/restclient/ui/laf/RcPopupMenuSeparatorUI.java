package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicPopupMenuSeparatorUI;
import java.awt.Dimension;
import java.awt.Graphics;

/** A hairline inset to line up with the item highlights, with breathing room above and below. */
public class RcPopupMenuSeparatorUI extends BasicPopupMenuSeparatorUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcPopupMenuSeparatorUI();
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        g.setColor(Theme.separator());
        g.fillRect(MenuPainter.INSET + 6, c.getHeight() / 2, c.getWidth() - 2 * (MenuPainter.INSET + 6), 1);
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        return new Dimension(0, 9);
    }
}
