package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.plaf.UIResource;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.function.Supplier;

/** Icons drawn in code for check boxes, radio buttons, menus, trees and combo boxes. */
public final class LafIcons {
    private LafIcons() {
    }

    /** Rounded check box: accent fill with a white tick when selected. */
    static final class CheckBox implements Icon, UIResource {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            ButtonModel m = ((AbstractButton) c).getModel();
            boolean on = m.isSelected();
            Graphics2D g2 = Paint.smooth(g);
            if (!c.isEnabled()) g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
            if (on) {
                Paint.fill(g2, x, y, 16, 16, 6, m.isPressed() ? Theme.accentPressed() : Theme.accent());
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Float();
                p.moveTo(x + 4.2f, y + 8.4f);
                p.lineTo(x + 6.9f, y + 11f);
                p.lineTo(x + 11.8f, y + 5.2f);
                g2.draw(p);
            } else {
                Paint.fill(g2, x, y, 16, 16, 6, m.isPressed() ? Theme.buttonPressed() : Theme.fieldBg());
                Paint.outline(g2, x, y, 16, 16, 6, 1f, m.isRollover() ? Theme.secondary() : Theme.fieldBorder());
            }
            if (FocusVisible.show(c)) Paint.outline(g2, x - 2, y - 2, 20, 20, 9, 2f, Theme.focusRing());
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    /** Round radio button with a white dot when selected. */
    static final class Radio implements Icon, UIResource {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            ButtonModel m = ((AbstractButton) c).getModel();
            Graphics2D g2 = Paint.smooth(g);
            if (!c.isEnabled()) g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
            if (m.isSelected()) {
                g2.setColor(Theme.accent());
                g2.fill(new Ellipse2D.Float(x, y, 16, 16));
                g2.setColor(Color.WHITE);
                g2.fill(new Ellipse2D.Float(x + 5, y + 5, 6, 6));
            } else {
                g2.setColor(Theme.fieldBg());
                g2.fill(new Ellipse2D.Float(x, y, 16, 16));
                g2.setColor(m.isRollover() ? Theme.secondary() : Theme.fieldBorder());
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new Ellipse2D.Float(x + 0.5f, y + 0.5f, 15, 15));
            }
            if (FocusVisible.show(c)) {
                g2.setColor(Theme.focusRing());
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new Ellipse2D.Float(x - 1.5f, y - 1.5f, 19, 19));
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    /** Tick shown next to selected check/radio menu items. */
    static final class MenuCheck implements Icon, UIResource {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (!(c instanceof JMenuItem mi) || !mi.isSelected()) return;
            Graphics2D g2 = Paint.smooth(g);
            boolean armed = mi.getModel().isArmed() || mi.isSelected() && mi.getModel().isRollover();
            g2.setColor(armed ? Color.WHITE : Theme.text());
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Float();
            p.moveTo(x + 1.5f, y + 6.5f);
            p.lineTo(x + 4.5f, y + 9.5f);
            p.lineTo(x + 10.5f, y + 2.5f);
            g2.draw(p);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 12;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }

    public enum Direction { UP, DOWN, LEFT, RIGHT }

    /** A thin chevron. */
    public static final class Chevron implements Icon, UIResource {
        private final Direction dir;
        private final int size;
        private final Supplier<Color> color;

        public Chevron(Direction dir, int size, Supplier<Color> color) {
            this.dir = dir;
            this.size = size;
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = Paint.smooth(g);
            Color col = color.get();
            if (c instanceof JMenuItem mi && mi.isArmed()) col = Color.WHITE;
            if (c != null && !c.isEnabled()) col = Theme.disabledText();
            g2.setColor(col);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float s = size;
            float a = s * 0.28f;
            float cx = x + s / 2f;
            float cy = y + s / 2f;
            Path2D p = new Path2D.Float();
            switch (dir) {
                case DOWN -> {
                    p.moveTo(cx - a, cy - a / 2);
                    p.lineTo(cx, cy + a / 2);
                    p.lineTo(cx + a, cy - a / 2);
                }
                case UP -> {
                    p.moveTo(cx - a, cy + a / 2);
                    p.lineTo(cx, cy - a / 2);
                    p.lineTo(cx + a, cy + a / 2);
                }
                case RIGHT -> {
                    p.moveTo(cx - a / 2, cy - a);
                    p.lineTo(cx + a / 2, cy);
                    p.lineTo(cx - a / 2, cy + a);
                }
                case LEFT -> {
                    p.moveTo(cx + a / 2, cy - a);
                    p.lineTo(cx - a / 2, cy);
                    p.lineTo(cx + a / 2, cy + a);
                }
            }
            g2.draw(p);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
