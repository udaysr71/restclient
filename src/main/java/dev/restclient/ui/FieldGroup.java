package dev.restclient.ui;

import dev.restclient.ui.laf.Paint;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.LayoutManager;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * Several controls drawn as one input: a single rounded field background and outline, with a hairline between
 * parts and one focus ring around the whole group. Used for the request bar (method + URL), so the two things
 * that define a request read as one unit and sit right next to Send (Practical UI, "Keep related actions close").
 */
public final class FieldGroup extends JPanel {
    private static final int ARC = 10;
    private final FocusAdapter repaintOnFocus = new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
            repaint();
        }

        @Override
        public void focusLost(FocusEvent e) {
            repaint();
        }
    };

    public FieldGroup(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
    }

    /** Adds a part; its own border and background are removed so the group's show through. */
    public void addPart(JComponent c, Object constraints) {
        c.putClientProperty("rc.embedded", Boolean.TRUE);
        c.setOpaque(false);
        listen(c);
        add(c, constraints);
    }

    private void listen(Component c) {
        c.addFocusListener(repaintOnFocus);
        if (c instanceof java.awt.Container k) for (Component child : k.getComponents()) listen(child);
    }

    /** A 1px divider between parts. */
    public static JComponent divider() {
        JComponent d = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.separator());
                int inset = Math.max(6, getHeight() / 5);
                g.fillRect(0, inset, 1, getHeight() - 2 * inset);
            }
        };
        d.setPreferredSize(new Dimension(1, 1));
        return d;
    }

    private boolean focusedInside() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return owner != null && SwingUtilities.isDescendingFrom(owner, this);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Paint.smooth(g);
        Paint.fill(g2, 0, 0, getWidth(), getHeight(), ARC, Theme.fieldBg());
        g2.dispose();
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        Graphics2D g2 = Paint.smooth(g);
        if ("error".equals(getClientProperty("JComponent.outline"))) {
            Paint.outline(g2, 0, 0, getWidth(), getHeight(), ARC, focusedInside() ? 2f : 1.5f, Theme.errFg());
        } else if (focusedInside()) {
            Paint.outline(g2, 0, 0, getWidth(), getHeight(), ARC, 2f, Theme.accent());
        } else {
            Paint.outline(g2, 0, 0, getWidth(), getHeight(), ARC, 1f, Theme.fieldBorder());
        }
        g2.dispose();
    }
}
