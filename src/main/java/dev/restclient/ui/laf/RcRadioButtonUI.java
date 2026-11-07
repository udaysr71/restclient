package dev.restclient.ui.laf;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicRadioButtonUI;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;

/** Radio button that draws disabled text in the theme's disabled colour instead of Basic's emboss. */
public class RcRadioButtonUI extends BasicRadioButtonUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcRadioButtonUI();
    }

    @Override
    protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) {
        paintPlainText(g, b, r, text, getPropertyPrefix(), getTextShiftOffset());
    }

    static void paintPlainText(Graphics g, AbstractButton b, Rectangle r, String text, String prefix, int shift) {
        FontMetrics fm = b.getFontMetrics(b.getFont());
        int mnemonic = b.getDisplayedMnemonicIndex();
        g.setColor(b.getModel().isEnabled() ? b.getForeground() : UIManager.getColor(prefix + "disabledText"));
        BasicGraphicsUtils.drawStringUnderlineCharAt(b, (java.awt.Graphics2D) g, text, mnemonic,
                r.x + shift, r.y + fm.getAscent() + shift);
    }
}
