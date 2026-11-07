package dev.restclient.ui.laf;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicRadioButtonMenuItemUI;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

/** RadioButtonMenuItem with rounded, inset highlight and separate label/shortcut columns (see {@link MenuPainter}). */
public class RcRadioButtonMenuItemUI extends BasicRadioButtonMenuItemUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcRadioButtonMenuItemUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        LookAndFeel.installProperty(menuItem, "opaque", Boolean.FALSE);
    }

    @Override
    protected Dimension getPreferredMenuItemSize(JComponent c, Icon check, Icon arrow, int gap) {
        return MenuPainter.preferredSize((JMenuItem) c, acceleratorFont);
    }

    @Override
    protected void paintMenuItem(Graphics g, JComponent c, Icon check, Icon arrow, Color bg, Color fg, int gap) {
        MenuPainter.paint(g, (JMenuItem) c, acceleratorFont, checkIcon, arrowIcon);
    }
}
