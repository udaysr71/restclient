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

    /**
     * Space before every row's content, so the disclosure chevron sits inside the rounded highlight (which starts
     * 4 px in) with breathing room, instead of on its edge. BasicTreeUI uses getRowX for painting, hit-testing the
     * chevron and the drop line, so all of them move together.
     */
    static final int ROW_LEAD = 8;

    @Override
    protected int getRowX(int row, int depth) {
        return super.getRowX(row, depth) + Theme.scaled(ROW_LEAD);
    }

    /**
     * Where to draw the drop line while dragging rows. Swing's version throws if the drop location points at a
     * row that's gone (the tree was rebuilt mid-drag: a save, a file change, a filter); then there's no line to draw.
     */
    @Override
    protected java.awt.Rectangle getDropLineRect(javax.swing.JTree.DropLocation loc) {
        try {
            return super.getDropLineRect(loc);
        } catch (RuntimeException stale) {
            return new java.awt.Rectangle();
        }
    }

    /** Forgets cached row sizes (e.g. after the tree got narrower, so long names re-shorten to fit). */
    public void refreshSizes() {
        if (treeState != null) treeState.invalidateSizes();
        updateSize();
    }

    /** The x where a row's content starts, for renderers that size themselves to the space left. */
    public int contentX(int depth) {
        return getRowX(-1, depth);
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
