package dev.restclient.ui.laf;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicLabelUI;
import java.awt.Graphics;

/** Label that draws disabled text flat in the disabled colour (Basic embosses it). */
public class RcLabelUI extends BasicLabelUI {
    private static final RcLabelUI INSTANCE = new RcLabelUI();

    public static ComponentUI createUI(JComponent c) {
        return INSTANCE;
    }

    @Override
    protected void paintDisabledText(JLabel l, Graphics g, String s, int textX, int textY) {
        g.setColor(UIManager.getColor("Label.disabledForeground"));
        BasicGraphicsUtils.drawStringUnderlineCharAt(l, (java.awt.Graphics2D) g, s, l.getDisplayedMnemonicIndex(), textX, textY);
    }
}
