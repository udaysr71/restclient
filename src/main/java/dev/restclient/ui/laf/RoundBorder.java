package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.UIResource;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;

/**
 * Rounded outline for text fields, combo boxes and spinners, with an accent focus ring.
 * The matching background is painted by the component's UI delegate ({@link #paintBackground}).
 * Honours {@code JTextField.leadingIcon} (extra left inset) and {@code JComponent.outline = "error"}.
 */
public class RoundBorder extends AbstractBorder implements UIResource {
    public static final int ARC = 9;
    public static final String LEADING_ICON = "JTextField.leadingIcon";
    public static final String OUTLINE = "JComponent.outline";

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.top = 5;
        insets.bottom = 5;
        insets.left = 9;
        insets.right = 9;
        if (c instanceof JComponent jc && jc.getClientProperty(LEADING_ICON) instanceof Icon icon) {
            insets.left += icon.getIconWidth() + 6;
        }
        if (c instanceof JComboBox || c instanceof JSpinner) {
            insets.left = 4;
            insets.right = 2;
            insets.top = 2;
            insets.bottom = 2;
        }
        return insets;
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return getBorderInsets(c, new Insets(0, 0, 0, 0));
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        Graphics2D g2 = Paint.smooth(g);
        boolean focused = isFocused(c);
        Color line = Theme.fieldBorder();
        if (c instanceof JComponent jc && "error".equals(jc.getClientProperty(OUTLINE))) line = Theme.errFg();
        if (focused) {
            Paint.outline(g2, x, y, w, h, ARC, 2f, Theme.accent());
        } else {
            Paint.outline(g2, x, y, w, h, ARC, 1f, c.isEnabled() ? line : Paint.alpha(line, 0.6f));
        }
        g2.dispose();
    }

    /** Fills the rounded background inside this border. */
    public static void paintBackground(Graphics g, JComponent c) {
        Graphics2D g2 = Paint.smooth(g);
        Color bg = c.getBackground();
        if (!c.isEnabled() || (c instanceof JTextComponent t && !t.isEditable() && bg instanceof UIResource)) {
            bg = Theme.subtleBg();
        }
        Paint.fill(g2, 0, 0, c.getWidth(), c.getHeight(), ARC, bg);
        g2.dispose();
    }

    static boolean isFocused(Component c) {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner == null) return false;
        if (owner == c) return true;
        // combo box / spinner: focus is on the inner editor
        return (c instanceof JComboBox || c instanceof JSpinner) && SwingUtilities.isDescendingFrom(owner, c);
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }
}
