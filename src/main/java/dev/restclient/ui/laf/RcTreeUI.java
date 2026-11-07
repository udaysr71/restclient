package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.TreePath;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/** Tree with full-width rounded selection rows, like a macOS source list. */
public class RcTreeUI extends BasicTreeUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcTreeUI();
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        int[] rows = tree.getSelectionRows();
        if (rows != null && rows.length > 0) {
            Graphics2D g2 = Paint.smooth(g);
            Rectangle clip = g.getClipBounds();
            for (int row : rows) {
                TreePath p = getPathForRow(tree, row);
                Rectangle b = p == null ? null : getPathBounds(tree, p);
                if (b == null || (clip != null && !clip.intersects(0, b.y, tree.getWidth(), b.height))) continue;
                Paint.fill(g2, 4, b.y + 1, tree.getWidth() - 8, b.height - 2, 8,
                        tree.hasFocus() ? Theme.selectionBg() : Theme.mix(Theme.selectionBg(), Theme.sidebarBg(), 0.45f));
            }
            g2.dispose();
        }
        super.paint(g, c);
    }

    /** Repaints the selection (it's dimmer without focus). Removed again on uninstall, e.g. a theme switch. */
    private final java.awt.event.FocusListener focusRepaint = new java.awt.event.FocusAdapter() {
        @Override
        public void focusGained(java.awt.event.FocusEvent e) {
            e.getComponent().repaint();
        }

        @Override
        public void focusLost(java.awt.event.FocusEvent e) {
            e.getComponent().repaint();
        }
    };

    @Override
    protected void installListeners() {
        super.installListeners();
        tree.addFocusListener(focusRepaint);
    }

    @Override
    protected void uninstallListeners() {
        tree.removeFocusListener(focusRepaint);
        super.uninstallListeners();
    }
}
