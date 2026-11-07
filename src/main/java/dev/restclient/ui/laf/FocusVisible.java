package dev.restclient.ui.laf;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * "Focus visible", as browsers and macOS do it: buttons, toggles, check boxes and radio buttons only show a
 * focus ring while you're using the keyboard. Click something and the ring goes away; press a key and it comes
 * back. Text fields always show where you're typing, so they don't use this.
 */
public final class FocusVisible {
    private static volatile boolean keyboard;
    private static boolean installed;

    private FocusVisible() {
    }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
                if (e.getID() == KeyEvent.KEY_PRESSED) {
                    int code = ((KeyEvent) e).getKeyCode();
                    boolean modifierOnly = code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL
                            || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH;
                    if (!modifierOnly) set(true);
                } else if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                    set(false);
                }
            }, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        } catch (RuntimeException e) {
            keyboard = true; // can't tell: always show focus, the accessible default
        }
    }

    private static void set(boolean k) {
        if (k == keyboard) return;
        keyboard = k;
        Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (f != null) f.repaint();
    }

    /** Whether to draw a focus ring on {@code c} now. */
    public static boolean show(Component c) {
        return keyboard && c.hasFocus();
    }
}
