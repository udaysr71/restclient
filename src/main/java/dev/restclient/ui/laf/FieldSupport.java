package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.Icon;
import javax.swing.plaf.UIResource;
import javax.swing.text.JTextComponent;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeEvent;

/** Background, placeholder text and leading icon for single-line text fields. */
final class FieldSupport {
    static final String PLACEHOLDER = "JTextField.placeholderText";

    /** Repaints the field (and a surrounding combo box or spinner) when focus changes, for the focus ring. */
    static final java.awt.event.FocusListener FOCUS = new java.awt.event.FocusAdapter() {
        @Override
        public void focusGained(java.awt.event.FocusEvent e) {
            repaint(e.getComponent());
        }

        @Override
        public void focusLost(java.awt.event.FocusEvent e) {
            repaint(e.getComponent());
        }

        private void repaint(java.awt.Component c) {
            c.repaint();
            java.awt.Container p = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JSpinner.class, c);
            if (p == null) p = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JComboBox.class, c);
            if (p != null) p.repaint();
        }
    };

    private FieldSupport() {
    }

    /** Call before the Basic UI paints text. */
    static void paintBackground(Graphics g, JTextComponent c) {
        if (c.isOpaque()) return; // Basic paints a plain rectangle
        if (c.getBorder() instanceof RoundBorder) {
            RoundBorder.paintBackground(g, c);
        } else if (!(c.getBackground() instanceof UIResource)) {
            g.setColor(c.getBackground());
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
        }
    }

    /** Call after the Basic UI painted text. */
    static void paintExtras(Graphics g, JTextComponent c) {
        Insets in = c.getInsets();
        if (c.getClientProperty(RoundBorder.LEADING_ICON) instanceof Icon icon && c.getBorder() instanceof RoundBorder) {
            icon.paintIcon(c, g, 9, (c.getHeight() - icon.getIconHeight()) / 2);
        }
        if (c.getDocument().getLength() == 0 && c.getClientProperty(PLACEHOLDER) instanceof String text && !text.isEmpty()) {
            Graphics2D g2 = (Graphics2D) g.create();
            Paint.text(g2); // the OS setting (ClearType on Windows), like Swing's own text
            g2.setFont(c.getFont());
            g2.setColor(Theme.secondary());
            FontMetrics fm = g2.getFontMetrics();
            int x = in.left;
            int baseline;
            try {
                Rectangle2D r = c.modelToView2D(0);
                baseline = r == null ? in.top + fm.getAscent() : (int) Math.round(r.getY()) + fm.getAscent();
                if (r != null) x = (int) Math.round(r.getX());
            } catch (Exception e) {
                baseline = in.top + fm.getAscent();
            }
            g2.clipRect(in.left, 0, c.getWidth() - in.left - in.right, c.getHeight());
            g2.drawString(text, x, baseline);
            g2.dispose();
        }
    }

    static void propertyChange(PropertyChangeEvent e, JTextComponent c) {
        String n = e.getPropertyName();
        if (PLACEHOLDER.equals(n) || RoundBorder.LEADING_ICON.equals(n) || RoundBorder.OUTLINE.equals(n)) {
            c.revalidate();
            c.repaint();
        }
    }
}
