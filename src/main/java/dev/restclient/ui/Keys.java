package dev.restclient.ui;

import javax.swing.KeyStroke;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * The single keymap. Menus, the command palette and tooltips all render shortcuts from here,
 * so labels never disagree: ⌘ symbols on macOS, "Ctrl+Shift+K" style elsewhere.
 */
public final class Keys {
    /** ⌘ on macOS, Ctrl elsewhere. */
    public static final int MOD = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    public static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    public static final int ALT = InputEvent.ALT_DOWN_MASK;
    public static final int CTRL = InputEvent.CTRL_DOWN_MASK;

    public static final KeyStroke SEND = mod(KeyEvent.VK_ENTER);
    public static final KeyStroke NEW_TAB = mod(KeyEvent.VK_T);
    public static final KeyStroke CLOSE_TAB = mod(KeyEvent.VK_W);
    public static final KeyStroke SAVE = mod(KeyEvent.VK_S);
    public static final KeyStroke SAVE_AS = of(KeyEvent.VK_S, MOD | SHIFT);
    public static final KeyStroke NEW_COLLECTION = of(KeyEvent.VK_N, MOD | SHIFT);
    public static final KeyStroke IMPORT_CURL = of(KeyEvent.VK_I, MOD | SHIFT);
    public static final KeyStroke COPY_CURL = of(KeyEvent.VK_C, MOD | ALT);
    public static final KeyStroke FOCUS_URL = mod(KeyEvent.VK_L);
    public static final KeyStroke SWITCH_ENV = mod(KeyEvent.VK_E);
    public static final KeyStroke PALETTE = mod(KeyEvent.VK_K);
    public static final KeyStroke SETTINGS = mod(KeyEvent.VK_COMMA);
    public static final KeyStroke FIND = mod(KeyEvent.VK_F);
    public static final KeyStroke REOPEN_TAB = of(KeyEvent.VK_T, MOD | SHIFT);
    public static final KeyStroke TOGGLE_APPEARANCE = of(KeyEvent.VK_L, MOD | SHIFT);
    public static final KeyStroke ZOOM_IN = mod(KeyEvent.VK_EQUALS);
    public static final KeyStroke ZOOM_OUT = mod(KeyEvent.VK_MINUS);
    public static final KeyStroke ZOOM_RESET = mod(KeyEvent.VK_0);
    /** Other keys that also zoom: Shift for "+", and the numeric keypad. */
    public static final KeyStroke[] ZOOM_IN_ALT = {of(KeyEvent.VK_EQUALS, MOD | SHIFT), mod(KeyEvent.VK_PLUS),
            mod(KeyEvent.VK_ADD), of(KeyEvent.VK_PLUS, MOD | SHIFT)};
    public static final KeyStroke[] ZOOM_OUT_ALT = {mod(KeyEvent.VK_SUBTRACT)};
    public static final KeyStroke[] ZOOM_RESET_ALT = {mod(KeyEvent.VK_NUMPAD0)};
    public static final KeyStroke UNDO = mod(KeyEvent.VK_Z);
    public static final KeyStroke REDO = Platform.isMac() ? of(KeyEvent.VK_Z, MOD | SHIFT) : mod(KeyEvent.VK_Y);
    public static final KeyStroke TOGGLE_SIDEBAR = Platform.isMac()
            ? of(KeyEvent.VK_S, MOD | CTRL) : mod(KeyEvent.VK_B);
    public static final KeyStroke NEXT_TAB = Platform.isMac()
            ? of(KeyEvent.VK_CLOSE_BRACKET, MOD | SHIFT) : mod(KeyEvent.VK_PAGE_DOWN);
    public static final KeyStroke PREV_TAB = Platform.isMac()
            ? of(KeyEvent.VK_OPEN_BRACKET, MOD | SHIFT) : mod(KeyEvent.VK_PAGE_UP);

    private Keys() {
    }

    public static KeyStroke mod(int key) {
        return KeyStroke.getKeyStroke(key, MOD);
    }

    public static KeyStroke of(int key, int modifiers) {
        return KeyStroke.getKeyStroke(key, modifiers);
    }

    /** "⇧⌘I" on macOS, "Ctrl+Shift+I" elsewhere. */
    public static String label(KeyStroke ks) {
        if (ks == null) return "";
        int m = ks.getModifiers();
        String key = keyName(ks.getKeyCode());
        if (Platform.isMac()) {
            StringBuilder sb = new StringBuilder();
            if ((m & InputEvent.CTRL_DOWN_MASK) != 0) sb.append('⌃');
            if ((m & InputEvent.ALT_DOWN_MASK) != 0) sb.append('⌥');
            if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append('⇧');
            if ((m & InputEvent.META_DOWN_MASK) != 0) sb.append('⌘');
            return sb + key;
        }
        StringBuilder sb = new StringBuilder();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) sb.append("Alt+");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if ((m & InputEvent.META_DOWN_MASK) != 0) sb.append("Meta+");
        return sb + key;
    }

    /** Label for a modifier-only hint, e.g. "⌘↩" vs "Ctrl+Enter". */
    public static String modLabel(String keyMac, String keyOther) {
        return Platform.isMac() ? "⌘" + keyMac : "Ctrl+" + keyOther;
    }

    private static String keyName(int code) {
        boolean mac = Platform.isMac();
        return switch (code) {
            case KeyEvent.VK_ENTER -> mac ? "↩" : "Enter";
            case KeyEvent.VK_ESCAPE -> mac ? "⎋" : "Esc";
            case KeyEvent.VK_TAB -> mac ? "⇥" : "Tab";
            case KeyEvent.VK_BACK_SPACE -> mac ? "⌫" : "Backspace";
            case KeyEvent.VK_DELETE -> mac ? "⌦" : "Del";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_DOWN -> "↓";
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_EQUALS -> mac ? "+" : "=";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            case KeyEvent.VK_PAGE_UP -> mac ? "⇞" : "PgUp";
            case KeyEvent.VK_PAGE_DOWN -> mac ? "⇟" : "PgDn";
            default -> KeyEvent.getKeyText(code);
        };
    }
}
