package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Graphics;

/** Split pane whose divider is a hairline (with a grab handle when it's wide enough). */
public class RcSplitPaneUI extends BasicSplitPaneUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcSplitPaneUI();
    }

    @Override
    public BasicSplitPaneDivider createDefaultDivider() {
        return new BasicSplitPaneDivider(this) {
            @Override
            public void paint(Graphics g) {
                int w = getWidth();
                int h = getHeight();
                boolean vertical = splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT;
                g.setColor(Theme.panelBg());
                g.fillRect(0, 0, w, h);
                g.setColor(Theme.separator());
                if (vertical) g.fillRect(0, h / 2, w, 1);
                else g.fillRect(w / 2, 0, 1, h);
                if ((vertical ? h : w) >= 6) {
                    java.awt.Graphics2D g2 = Paint.smooth(g);
                    g2.setColor(Theme.fieldBorder());
                    if (vertical) g2.fillRoundRect(w / 2 - 18, h / 2 - 1, 36, 3, 3, 3);
                    else g2.fillRoundRect(w / 2 - 1, h / 2 - 18, 3, 36, 3, 3);
                    g2.dispose();
                }
            }

            @Override
            public void setBorder(javax.swing.border.Border b) {
                // no border
            }
        };
    }
}
