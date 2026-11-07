package dev.restclient.ui.laf;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicCheckBoxUI;
import java.awt.Graphics;
import java.awt.Rectangle;

/** Check box that draws disabled text in the theme's disabled colour (Basic embosses it, which vanishes on dark). */
public class RcCheckBoxUI extends BasicCheckBoxUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcCheckBoxUI();
    }

    @Override
    protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) {
        RcRadioButtonUI.paintPlainText(g, b, r, text, getPropertyPrefix(), getTextShiftOffset());
    }
}
