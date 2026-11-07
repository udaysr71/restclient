package dev.restclient.ui.laf;

import dev.restclient.ui.Keys;
import dev.restclient.ui.Theme;

import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Layout and painting shared by menu items and menus, in the style of macOS and Windows 11 menus:
 * <ul>
 *   <li>the highlight is a rounded shape inset from the menu's edges, not a full-width bar;</li>
 *   <li>labels and shortcuts are separate columns with a guaranteed gap, so they never run together;</li>
 *   <li>a check/icon column is only reserved when some item in the same menu needs it;</li>
 *   <li>rows are a comfortable 28 px (scaled with zoom).</li>
 * </ul>
 */
final class MenuPainter {
    /** Horizontal inset of the highlight from the menu edge. */
    static final int INSET = 5;
    /** Space inside the highlight before the first column and after the last. */
    private static final int PAD = 10;
    private static final int ICON_COL = 20;
    /** Minimum space between a label and its shortcut. */
    private static final int ACC_GAP = 36;
    private static final int ARROW_COL = 14;
    private static final int ARC = 7;

    private MenuPainter() {
    }

    static boolean topLevel(JMenuItem item) {
        return item instanceof JMenu m && m.isTopLevelMenu();
    }

    static String accelerator(JMenuItem item) {
        if (item instanceof JMenu) return null;
        KeyStroke ks = item.getAccelerator();
        return ks == null ? null : Keys.label(ks);
    }

    /** Whether any sibling in the same menu shows a check mark or icon (so all labels line up). */
    static boolean needsIconColumn(JMenuItem item) {
        Container parent = item.getParent();
        if (parent == null) return hasIconOrCheck(item);
        for (Component c : parent.getComponents()) if (c instanceof JMenuItem mi && hasIconOrCheck(mi)) return true;
        return false;
    }

    private static boolean hasIconOrCheck(JMenuItem mi) {
        return mi instanceof JCheckBoxMenuItem || mi instanceof JRadioButtonMenuItem || mi.getIcon() != null;
    }

    static Dimension preferredSize(JMenuItem item, Font accFont) {
        FontMetrics fm = item.getFontMetrics(item.getFont());
        int text = fm.stringWidth(item.getText() == null ? "" : item.getText());
        if (topLevel(item)) {
            return new Dimension(text + Theme.scaled(20), Math.max(Theme.scaled(26), fm.getHeight() + 8));
        }
        int w = INSET + PAD + text + PAD + INSET;
        if (needsIconColumn(item)) w += ICON_COL;
        String acc = accelerator(item);
        if (acc != null) w += ACC_GAP + item.getFontMetrics(accFont).stringWidth(acc);
        if (item instanceof JMenu) w += ARROW_COL + 12;
        int h = Math.max(Theme.scaled(28), fm.getHeight() + 10);
        return new Dimension(Math.max(w, Theme.scaled(160)), h);
    }

    static void paint(Graphics g, JMenuItem item, Font accFont, Icon checkIcon, Icon arrowIcon) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Object aa = javax.swing.UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa != null ? aa : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        ButtonModel m = item.getModel();
        boolean enabled = item.isEnabled();
        int w = item.getWidth();
        int h = item.getHeight();
        FontMetrics fm = g2.getFontMetrics(item.getFont());
        int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();

        if (topLevel(item)) {
            JMenu menu = (JMenu) item;
            boolean open = menu.isSelected() || m.isArmed();
            boolean hover = m.isRollover() && enabled;
            if (open || hover) {
                g2.setColor(open ? Theme.pressedSubtle() : Theme.hoverSubtle());
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(2, 3, w - 4, h - 6, ARC, ARC));
            }
            g2.setFont(item.getFont());
            g2.setColor(enabled ? Theme.text() : Theme.disabledText());
            String t = item.getText() == null ? "" : item.getText();
            g2.drawString(t, (w - fm.stringWidth(t)) / 2f, baseline);
            g2.dispose();
            return;
        }

        boolean armed = enabled && (m.isArmed() || (item instanceof JMenu jm && jm.isSelected()));
        if (armed) {
            g2.setColor(Theme.accent());
            g2.fill(new java.awt.geom.RoundRectangle2D.Float(INSET, 1, w - 2 * INSET, h - 2, ARC, ARC));
        }
        Color fg = !enabled ? Theme.disabledText() : armed ? Color.WHITE : Theme.text();
        Color accFg = !enabled ? Theme.disabledText() : armed ? new Color(255, 255, 255, 210) : Theme.secondary();

        int x = INSET + PAD;
        if (needsIconColumn(item)) {
            Icon icon = item.getIcon();
            boolean checked = (item instanceof JCheckBoxMenuItem || item instanceof JRadioButtonMenuItem) && item.isSelected();
            if (checked && checkIcon != null) {
                checkIcon.paintIcon(item, g2, x, (h - checkIcon.getIconHeight()) / 2);
            } else if (icon != null) {
                icon.paintIcon(item, g2, x, (h - icon.getIconHeight()) / 2);
            }
            x += ICON_COL;
        }
        g2.setFont(item.getFont());
        g2.setColor(fg);
        String text = item.getText() == null ? "" : item.getText();
        int right = w - INSET - PAD - (item instanceof JMenu ? ARROW_COL : 0);
        String acc = accelerator(item);
        int accW = acc == null ? 0 : g2.getFontMetrics(accFont).stringWidth(acc);
        int maxText = right - x - (acc == null ? 0 : accW + 16);
        g2.drawString(clip(text, fm, maxText), x, baseline);
        if (acc != null) {
            g2.setFont(accFont);
            g2.setColor(accFg);
            FontMetrics afm = g2.getFontMetrics();
            g2.drawString(acc, right - accW, (h - afm.getHeight()) / 2 + afm.getAscent());
        }
        if (item instanceof JMenu && arrowIcon != null) {
            arrowIcon.paintIcon(item, g2, w - INSET - PAD - arrowIcon.getIconWidth() + 2, (h - arrowIcon.getIconHeight()) / 2);
        }
        g2.dispose();
    }

    private static String clip(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end)) + fm.stringWidth(dots) > max) end--;
        return s.substring(0, end) + dots;
    }

    static boolean inMenuBar(JMenuItem item) {
        return SwingUtilities.getAncestorOfClass(JMenuBar.class, item) != null && item instanceof JMenu menu && menu.isTopLevelMenu();
    }

    static void noBorder(JComponent c) {
        c.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        c.setOpaque(false);
    }
}
