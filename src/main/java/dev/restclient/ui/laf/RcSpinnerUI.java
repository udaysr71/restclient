package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicSpinnerUI;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Spinner drawn like a rounded field with small up/down chevrons. */
public class RcSpinnerUI extends BasicSpinnerUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcSpinnerUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        LookAndFeel.installProperty(spinner, "opaque", Boolean.FALSE);
        if (spinner.getBorder() == null || spinner.getBorder() instanceof UIResource) spinner.setBorder(new RoundBorder());
    }

    @Override
    protected Component createNextButton() {
        Component c = arrow(LafIcons.Direction.UP);
        c.setName("Spinner.nextButton");
        installNextButtonListeners(c);
        return c;
    }

    @Override
    protected Component createPreviousButton() {
        Component c = arrow(LafIcons.Direction.DOWN);
        c.setName("Spinner.previousButton");
        installPreviousButtonListeners(c);
        return c;
    }

    private static JButton arrow(LafIcons.Direction d) {
        JButton b = new JButton(new LafIcons.Chevron(d, 11, Theme::secondary));
        b.putClientProperty(RcButtonUI.TYPE, "borderless");
        b.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(18, 11));
        return b;
    }

    @Override
    protected JComponent createEditor() {
        JComponent e = super.createEditor();
        fixEditor(e);
        return e;
    }

    @Override
    protected void replaceEditor(JComponent oldEditor, JComponent newEditor) {
        super.replaceEditor(oldEditor, newEditor);
        fixEditor(newEditor);
    }

    private static void fixEditor(JComponent e) {
        e.setOpaque(false);
        if (e instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 2));
            de.getTextField().setOpaque(false);
            de.getTextField().addFocusListener(FieldSupport.FOCUS);
        }
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        Graphics2D g2 = Paint.smooth(g);
        Paint.fill(g2, 0, 0, c.getWidth(), c.getHeight(), RoundBorder.ARC, c.isEnabled() ? Theme.fieldBg() : Theme.subtleBg());
        g2.dispose();
        super.paint(g, c);
    }
}
