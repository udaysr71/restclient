package dev.restclient.ui.laf;

import javax.swing.JComponent;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTextFieldUI;
import java.awt.Graphics;
import java.beans.PropertyChangeEvent;

/** Rounded field with placeholder text and an optional leading icon. */
public class RcTextFieldUI extends BasicTextFieldUI {
    public static ComponentUI createUI(JComponent c) {
        return new RcTextFieldUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        LookAndFeel.installProperty(getComponent(), "opaque", Boolean.FALSE);
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        getComponent().addFocusListener(FieldSupport.FOCUS);
    }

    @Override
    protected void uninstallListeners() {
        getComponent().removeFocusListener(FieldSupport.FOCUS);
        super.uninstallListeners();
    }

    @Override
    protected void paintSafely(Graphics g) {
        // The text views clip the graphics to the text area, so keep an unclipped copy for the icon.
        Graphics extras = g.create();
        try {
            FieldSupport.paintBackground(g, getComponent());
            super.paintSafely(g);
            FieldSupport.paintExtras(extras, getComponent());
        } finally {
            extras.dispose();
        }
    }

    @Override
    protected void propertyChange(PropertyChangeEvent e) {
        super.propertyChange(e);
        FieldSupport.propertyChange(e, getComponent());
    }
}
