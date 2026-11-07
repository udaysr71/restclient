package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.LookAndFeel;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * Buttons and toggle buttons.
 * <ul>
 *   <li>default: white (dark: grey) with a hairline border; accent-filled when it's the default button
 *       or has an explicit background</li>
 *   <li>{@code JButton.buttonType = "toolBarButton"}: no chrome until hovered/pressed/selected</li>
 *   <li>{@code "borderless"}: link-like, subtle hover</li>
 *   <li>{@code "segment"}: a segment of a segmented control; selected is a raised white pill</li>
 * </ul>
 * Client properties {@code rc.arc} (Integer), {@code rc.borderColor} (Color) and {@code rc.borderWidth}
 * (Number) adjust the shape.
 */
public class RcButtonUI extends BasicButtonUI {
    public static final String TYPE = "JButton.buttonType";

    public static ComponentUI createUI(JComponent c) {
        return new RcButtonUI();
    }

    @Override
    protected void installDefaults(AbstractButton b) {
        super.installDefaults(b);
        LookAndFeel.installProperty(b, "opaque", Boolean.FALSE);
        LookAndFeel.installProperty(b, "rolloverEnabled", Boolean.TRUE);
        LookAndFeel.installProperty(b, "iconTextGap", 6);
        if (b.getBorder() == null || b.getBorder() instanceof UIResource) b.setBorder(new Pad());
    }

    private static final String HOVER = "rc.hoverT";
    private static final String HOVER_LISTENER = "rc.hoverListener";

    @Override
    protected void installListeners(AbstractButton b) {
        super.installListeners(b);
        // Hover colour eases in and out instead of snapping.
        javax.swing.event.ChangeListener l = new javax.swing.event.ChangeListener() {
            private boolean was;
            private dev.restclient.ui.Motion.Handle anim;

            @Override
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                boolean now = b.getModel().isRollover() && b.isEnabled();
                if (now == was) return;
                was = now;
                if (anim != null) anim.stop();
                float from = b.getClientProperty(HOVER) instanceof Float f ? f : 0f;
                float to = now ? 1f : 0f;
                if (!dev.restclient.ui.Motion.enabled()) {
                    b.putClientProperty(HOVER, to);
                    b.repaint();
                    return;
                }
                anim = dev.restclient.ui.Motion.animate(now ? 110 : 180, t -> {
                    b.putClientProperty(HOVER, (float) (from + (to - from) * t));
                    b.repaint();
                }, null);
            }
        };
        b.getModel().addChangeListener(l);
        b.putClientProperty(HOVER_LISTENER, l);
        java.beans.PropertyChangeListener modelSwap = e -> {
            if (e.getOldValue() instanceof javax.swing.ButtonModel o) o.removeChangeListener(l);
            if (e.getNewValue() instanceof javax.swing.ButtonModel n) n.addChangeListener(l);
        };
        b.addPropertyChangeListener("model", modelSwap);
        b.putClientProperty(HOVER_LISTENER + ".model", modelSwap);
    }

    @Override
    protected void uninstallListeners(AbstractButton b) {
        super.uninstallListeners(b);
        if (b.getClientProperty(HOVER_LISTENER) instanceof javax.swing.event.ChangeListener l) {
            b.getModel().removeChangeListener(l);
            b.putClientProperty(HOVER_LISTENER, null);
        }
        if (b.getClientProperty(HOVER_LISTENER + ".model") instanceof java.beans.PropertyChangeListener p) {
            b.removePropertyChangeListener("model", p);
            b.putClientProperty(HOVER_LISTENER + ".model", null);
        }
    }

    /** Blends from a resting colour (null = transparent) to its hover colour. */
    private static Color blend(Color rest, Color hover, float t) {
        if (hover == null) return rest;
        if (t >= 1f) return hover;
        if (rest == null) return t <= 0f ? null : Paint.alpha(hover, t);
        if (t <= 0f) return rest;
        return Theme.mix(rest, hover, t);
    }

    /** Insets only; all drawing happens in {@link #paint}. */
    static final class Pad extends EmptyBorder implements UIResource {
        Pad() {
            super(0, 0, 0, 0);
        }

        @Override
        public Insets getBorderInsets(java.awt.Component c, Insets in) {
            AbstractButton b = (AbstractButton) c;
            String type = (String) b.getClientProperty(TYPE);
            Insets m = b.getMargin();
            boolean iconOnly = (b.getText() == null || b.getText().isEmpty()) && b.getIcon() != null;
            if (m != null && !(m instanceof UIResource)) {
                in.set(m.top, m.left, m.bottom, m.right);
            } else if (iconOnly) {
                in.set(5, 6, 5, 6);
            } else if ("segment".equals(type) || "toolBarButton".equals(type)) {
                in.set(4, 10, 4, 10);
            } else if ("borderless".equals(type)) {
                in.set(3, 6, 3, 6);
            } else {
                in.set(5, 14, 5, 14);
            }
            return in;
        }

        @Override
        public Insets getBorderInsets(java.awt.Component c) {
            return getBorderInsets(c, new Insets(0, 0, 0, 0));
        }
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        Dimension d = super.getPreferredSize(c);
        if (d == null) return null;
        AbstractButton b = (AbstractButton) c;
        String type = (String) b.getClientProperty(TYPE);
        boolean hasText = b.getText() != null && !b.getText().isEmpty();
        if (type == null && hasText) {
            d.height = Math.max(d.height, 30);
            d.width = Math.max(d.width, 72);
        } else if (!"borderless".equals(type)) {
            d.height = Math.max(d.height, 28);
            if (!hasText) d.width = Math.max(d.width, 28);
        }
        return d;
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        ButtonModel m = b.getModel();
        String type = (String) b.getClientProperty(TYPE);
        int arc = b.getClientProperty("rc.arc") instanceof Integer i ? i : 8;
        Color customBorder = b.getClientProperty("rc.borderColor") instanceof Color col ? col : null;
        float borderWidth = b.getClientProperty("rc.borderWidth") instanceof Number n ? n.floatValue() : 1f;
        boolean customBg = !(b.getBackground() instanceof UIResource);
        boolean pressed = m.isArmed() && m.isPressed();
        float ht = b.getClientProperty(HOVER) instanceof Float f ? f : (m.isRollover() && b.isEnabled() ? 1f : 0f);
        if (!b.isEnabled()) ht = 0f;
        boolean selected = m.isSelected();

        Color bg = null;
        Color border = null;
        boolean raised = false;
        boolean accentStyle = false;
        if ("toolBarButton".equals(type)) {
            bg = pressed ? Theme.pressedSubtle() : selected ? Theme.selectionBg() : blend(null, Theme.hoverSubtle(), ht);
        } else if ("borderless".equals(type)) {
            bg = pressed ? Theme.pressedSubtle() : blend(null, Theme.hoverSubtle(), ht);
        } else if ("segment".equals(type)) {
            // A Segmented control paints its own sliding thumb behind the selected segment.
            boolean thumbByParent = Boolean.TRUE.equals(b.getClientProperty("rc.thumbByParent"));
            bg = selected && !thumbByParent ? Theme.segmentSelected() : pressed ? Theme.pressedSubtle()
                    : selected ? null : blend(null, Theme.hoverSubtle(), ht);
            border = selected && !thumbByParent && !Theme.dark() ? Theme.fieldBorder() : null;
            raised = selected && !thumbByParent;
        } else {
            boolean accent = customBg || (b instanceof JButton jb && jb.isDefaultButton());
            if (accent) {
                Color base = customBg ? b.getBackground() : Theme.accent();
                bg = pressed ? Theme.mix(base, Color.BLACK, 0.18f) : blend(base, Theme.mix(base, Color.WHITE, 0.10f), ht);
                border = customBorder;
                raised = !pressed;
                accentStyle = true;
            } else {
                bg = pressed ? Theme.buttonPressed() : blend(Theme.buttonBg(), Theme.buttonHover(), ht);
                if (selected && b instanceof JToggleButton) bg = Theme.selectionBg();
                border = customBorder != null ? customBorder : Theme.fieldBorder();
                raised = !pressed && customBorder == null;
            }
        }

        Graphics2D g2 = Paint.smooth(g);
        if (!b.isEnabled() && bg != null) bg = Paint.alpha(bg, 0.55f);
        if (raised && b.isEnabled() && bg != null) {
            boolean dk = Theme.dark();
            Color shadow = new Color(0, 0, 0, dk ? 90 : accentStyle ? 40 : 22);
            Color top = accentStyle ? Theme.mix(bg, Color.WHITE, 0.12f) : bg;
            Color highlight = accentStyle ? new Color(255, 255, 255, 60) : dk ? new Color(255, 255, 255, 22) : null;
            Paint.raised(g2, c.getWidth(), c.getHeight(), arc, top, bg, highlight, shadow);
            if (border != null) Paint.outline(g2, 0, 0, c.getWidth(), c.getHeight() - 1, arc, borderWidth,
                    accentStyle ? border : Paint.alpha(border, dk ? 0.6f : 1f));
        } else {
            Paint.fill(g2, 0, 0, c.getWidth(), c.getHeight(), arc, bg);
            if (border != null) Paint.outline(g2, 0, 0, c.getWidth(), c.getHeight(), arc, borderWidth, border);
        }
        if (FocusVisible.show(b) && b.isFocusPainted() && !"borderless".equals(type)) {
            if (accentStyle) {
                // On a filled accent button a tinted ring would vanish: draw a solid ring with a white gap inside.
                Paint.outline(g2, 0, 0, c.getWidth(), c.getHeight(), arc, 2f, Theme.accentPressed());
                Paint.outline(g2, 2, 2, c.getWidth() - 4, c.getHeight() - 4, Math.max(2, arc - 3), 1.5f, Color.WHITE);
            } else {
                Paint.outline(g2, 0, 0, c.getWidth(), c.getHeight(), arc, 2f, Theme.focusRing());
            }
        }
        g2.dispose();
        super.paint(g, c);
    }

    @Override
    protected void paintText(Graphics g, AbstractButton b, Rectangle textRect, String text) {
        FontMetrics fm = b.getFontMetrics(b.getFont());
        Color fg = b.getForeground();
        boolean defaultAccent = b instanceof JButton jb && jb.isDefaultButton() && b.getBackground() instanceof UIResource
                && b.getClientProperty(TYPE) == null;
        if (!b.isEnabled()) fg = Theme.disabledText();
        else if (defaultAccent) fg = Color.WHITE;
        else if (fg instanceof UIResource) fg = Theme.text();
        g.setColor(fg);
        BasicGraphicsUtils.drawStringUnderlineCharAt(b, (Graphics2D) g, text, b.getDisplayedMnemonicIndex(),
                textRect.x, textRect.y + fm.getAscent());
    }

    @Override
    protected void paintButtonPressed(Graphics g, AbstractButton b) {
        // drawn in paint()
    }

    @Override
    protected void paintFocus(Graphics g, AbstractButton b, Rectangle viewRect, Rectangle textRect, Rectangle iconRect) {
        // drawn in paint()
    }
}
