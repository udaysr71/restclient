package dev.restclient.ui.laf;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTableHeaderUI;

/** Table header with leading-aligned column titles, like Finder and Settings lists. */
public class RcTableHeaderUI extends BasicTableHeaderUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcTableHeaderUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        if (header.getDefaultRenderer() instanceof JLabel l) l.setHorizontalAlignment(SwingConstants.LEADING);
    }
}
