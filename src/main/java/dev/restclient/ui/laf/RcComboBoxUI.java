package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

/** Combo box drawn like a rounded field with a chevron. */
public class RcComboBoxUI extends BasicComboBoxUI {
    private final FocusListener repaint = new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
            comboBox.repaint();
        }

        @Override
        public void focusLost(FocusEvent e) {
            comboBox.repaint();
        }
    };

    public static ComponentUI createUI(JComponent c) {
        return new RcComboBoxUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        LookAndFeel.installProperty(comboBox, "opaque", Boolean.FALSE);
        if (comboBox.getBorder() == null || comboBox.getBorder() instanceof UIResource) comboBox.setBorder(new RoundBorder());
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        comboBox.addFocusListener(repaint);
    }

    @Override
    protected void uninstallListeners() {
        comboBox.removeFocusListener(repaint);
        super.uninstallListeners();
    }

    @Override
    protected JButton createArrowButton() {
        JButton b = new JButton(new LafIcons.Chevron(LafIcons.Direction.DOWN, 14, Theme::secondary));
        b.putClientProperty(RcButtonUI.TYPE, "borderless");
        b.setBorder(BorderFactory.createEmptyBorder());
        b.setFocusable(false);
        b.setRolloverEnabled(false);
        b.setName("ComboBox.arrowButton");
        return b;
    }

    @Override
    protected ComboPopup createPopup() {
        BasicComboPopup p = new BasicComboPopup(comboBox) {
            @Override
            protected javax.swing.JList<Object> createList() {
                RoundedListRenderer rounded = new RoundedListRenderer(() -> comboBox.getRenderer());
                return new javax.swing.JList<>(comboBox.getModel()) {
                    @Override
                    public javax.swing.ListCellRenderer<? super Object> getCellRenderer() {
                        return rounded;
                    }
                };
            }

            @Override
            protected void configureList() {
                super.configureList();
                list.setBackground(Theme.menuBg());
            }
        };
        // Rounded like other menus (RcPopupMenuUI paints the shape); the list sits inside the padding.
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        return p;
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        // Inside a FieldGroup the group paints the background and outline.
        if (!Boolean.TRUE.equals(c.getClientProperty("rc.embedded"))) {
            Graphics2DFill.fill(g, c, comboBox.isEnabled() ? Theme.buttonBg() : Theme.subtleBg());
        }
        super.paint(g, c);
    }

    @Override
    public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
        // background is the rounded shape painted in paint()
    }

    @Override
    public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
        ListCellRenderer<Object> r = comboBox.getRenderer();
        @SuppressWarnings("unchecked")
        JList<Object> list = (JList<Object>) (JList<?>) listBox;
        Component c = r.getListCellRendererComponent(list, comboBox.getSelectedItem(), -1, false, false);
        c.setFont(comboBox.getFont());
        if (c instanceof JComponent jc) {
            jc.setOpaque(false);
            if (jc.getForeground() instanceof UIResource || !comboBox.isEnabled()) {
                jc.setForeground(comboBox.isEnabled() ? Theme.text() : Theme.disabledText());
            }
        }
        currentValuePane.paintComponent(g, c, comboBox, bounds.x + 4, bounds.y, bounds.width - 4, bounds.height, false);
    }

    /** Rounded background for the whole combo. */
    private static final class Graphics2DFill {
        static void fill(Graphics g, JComponent c, java.awt.Color color) {
            java.awt.Graphics2D g2 = Paint.smooth(g);
            Paint.fill(g2, 0, 0, c.getWidth(), c.getHeight(), RoundBorder.ARC, color);
            g2.dispose();
        }
    }
}
