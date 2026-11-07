package dev.restclient.ui.laf;

import javax.swing.KeyStroke;
import javax.swing.plaf.InputMapUIResource;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;

/**
 * Keyboard bindings for text components. Swing's Basic look and feel leaves these out (Metal, Aqua and
 * FlatLaf each define their own), so without them Backspace, arrows, Home/End and shortcuts do nothing.
 * macOS gets ⌘/⌥ conventions, Windows and Linux get Ctrl conventions.
 */
final class TextKeymaps {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;
    private static final int META = InputEvent.META_DOWN_MASK;
    /** Command on macOS, Ctrl elsewhere. */
    private static final int CMD = MAC ? META : CTRL;
    /** Word-wise movement: Option on macOS, Ctrl elsewhere. */
    private static final int WORD = MAC ? ALT : CTRL;

    private TextKeymaps() {
    }

    static InputMapUIResource field() {
        InputMapUIResource m = common();
        put(m, KeyEvent.VK_ENTER, 0, javax.swing.JTextField.notifyAction); // fires ActionListeners
        if (MAC) {
            // In a single-line field, up/down jump to the start/end.
            put(m, KeyEvent.VK_UP, 0, DefaultEditorKit.beginLineAction);
            put(m, KeyEvent.VK_DOWN, 0, DefaultEditorKit.endLineAction);
            put(m, KeyEvent.VK_UP, SHIFT, DefaultEditorKit.selectionBeginLineAction);
            put(m, KeyEvent.VK_DOWN, SHIFT, DefaultEditorKit.selectionEndLineAction);
        }
        return m;
    }

    static InputMapUIResource password() {
        InputMapUIResource m = field();
        // Don't reveal word boundaries in passwords: word moves become line moves.
        put(m, KeyEvent.VK_LEFT, WORD, DefaultEditorKit.beginLineAction);
        put(m, KeyEvent.VK_RIGHT, WORD, DefaultEditorKit.endLineAction);
        put(m, KeyEvent.VK_LEFT, WORD | SHIFT, DefaultEditorKit.selectionBeginLineAction);
        put(m, KeyEvent.VK_RIGHT, WORD | SHIFT, DefaultEditorKit.selectionEndLineAction);
        return m;
    }

    static InputMapUIResource area() {
        InputMapUIResource m = common();
        put(m, KeyEvent.VK_UP, 0, DefaultEditorKit.upAction);
        put(m, KeyEvent.VK_DOWN, 0, DefaultEditorKit.downAction);
        put(m, KeyEvent.VK_UP, SHIFT, DefaultEditorKit.selectionUpAction);
        put(m, KeyEvent.VK_DOWN, SHIFT, DefaultEditorKit.selectionDownAction);
        put(m, KeyEvent.VK_PAGE_UP, 0, DefaultEditorKit.pageUpAction);
        put(m, KeyEvent.VK_PAGE_DOWN, 0, DefaultEditorKit.pageDownAction);
        put(m, KeyEvent.VK_PAGE_UP, SHIFT, "selection-page-up");
        put(m, KeyEvent.VK_PAGE_DOWN, SHIFT, "selection-page-down");
        put(m, KeyEvent.VK_ENTER, 0, DefaultEditorKit.insertBreakAction);
        put(m, KeyEvent.VK_TAB, 0, DefaultEditorKit.insertTabAction);
        if (MAC) {
            put(m, KeyEvent.VK_UP, META, DefaultEditorKit.beginAction);
            put(m, KeyEvent.VK_DOWN, META, DefaultEditorKit.endAction);
            put(m, KeyEvent.VK_UP, META | SHIFT, DefaultEditorKit.selectionBeginAction);
            put(m, KeyEvent.VK_DOWN, META | SHIFT, DefaultEditorKit.selectionEndAction);
        } else {
            put(m, KeyEvent.VK_HOME, CTRL, DefaultEditorKit.beginAction);
            put(m, KeyEvent.VK_END, CTRL, DefaultEditorKit.endAction);
            put(m, KeyEvent.VK_HOME, CTRL | SHIFT, DefaultEditorKit.selectionBeginAction);
            put(m, KeyEvent.VK_END, CTRL | SHIFT, DefaultEditorKit.selectionEndAction);
        }
        return m;
    }

    private static InputMapUIResource common() {
        InputMapUIResource m = new InputMapUIResource();
        // characters
        put(m, KeyEvent.VK_LEFT, 0, DefaultEditorKit.backwardAction);
        put(m, KeyEvent.VK_RIGHT, 0, DefaultEditorKit.forwardAction);
        put(m, KeyEvent.VK_KP_LEFT, 0, DefaultEditorKit.backwardAction);
        put(m, KeyEvent.VK_KP_RIGHT, 0, DefaultEditorKit.forwardAction);
        put(m, KeyEvent.VK_LEFT, SHIFT, DefaultEditorKit.selectionBackwardAction);
        put(m, KeyEvent.VK_RIGHT, SHIFT, DefaultEditorKit.selectionForwardAction);
        // words
        put(m, KeyEvent.VK_LEFT, WORD, DefaultEditorKit.previousWordAction);
        put(m, KeyEvent.VK_RIGHT, WORD, DefaultEditorKit.nextWordAction);
        put(m, KeyEvent.VK_LEFT, WORD | SHIFT, DefaultEditorKit.selectionPreviousWordAction);
        put(m, KeyEvent.VK_RIGHT, WORD | SHIFT, DefaultEditorKit.selectionNextWordAction);
        // lines
        put(m, KeyEvent.VK_HOME, 0, DefaultEditorKit.beginLineAction);
        put(m, KeyEvent.VK_END, 0, DefaultEditorKit.endLineAction);
        put(m, KeyEvent.VK_HOME, SHIFT, DefaultEditorKit.selectionBeginLineAction);
        put(m, KeyEvent.VK_END, SHIFT, DefaultEditorKit.selectionEndLineAction);
        if (MAC) {
            put(m, KeyEvent.VK_LEFT, META, DefaultEditorKit.beginLineAction);
            put(m, KeyEvent.VK_RIGHT, META, DefaultEditorKit.endLineAction);
            put(m, KeyEvent.VK_LEFT, META | SHIFT, DefaultEditorKit.selectionBeginLineAction);
            put(m, KeyEvent.VK_RIGHT, META | SHIFT, DefaultEditorKit.selectionEndLineAction);
            // emacs-style keys that macOS text fields support
            put(m, KeyEvent.VK_A, CTRL, DefaultEditorKit.beginLineAction);
            put(m, KeyEvent.VK_E, CTRL, DefaultEditorKit.endLineAction);
        }
        // deleting
        put(m, KeyEvent.VK_BACK_SPACE, 0, DefaultEditorKit.deletePrevCharAction);
        put(m, KeyEvent.VK_BACK_SPACE, SHIFT, DefaultEditorKit.deletePrevCharAction);
        put(m, KeyEvent.VK_DELETE, 0, DefaultEditorKit.deleteNextCharAction);
        put(m, KeyEvent.VK_BACK_SPACE, WORD, DefaultEditorKit.deletePrevWordAction);
        put(m, KeyEvent.VK_DELETE, WORD, DefaultEditorKit.deleteNextWordAction);
        put(m, KeyEvent.VK_H, CTRL, DefaultEditorKit.deletePrevCharAction);
        // clipboard and selection
        put(m, KeyEvent.VK_C, CMD, DefaultEditorKit.copyAction);
        put(m, KeyEvent.VK_V, CMD, DefaultEditorKit.pasteAction);
        put(m, KeyEvent.VK_X, CMD, DefaultEditorKit.cutAction);
        put(m, KeyEvent.VK_A, CMD, DefaultEditorKit.selectAllAction);
        put(m, KeyEvent.VK_COPY, 0, DefaultEditorKit.copyAction);
        put(m, KeyEvent.VK_PASTE, 0, DefaultEditorKit.pasteAction);
        put(m, KeyEvent.VK_CUT, 0, DefaultEditorKit.cutAction);
        if (!MAC) {
            put(m, KeyEvent.VK_INSERT, CTRL, DefaultEditorKit.copyAction);
            put(m, KeyEvent.VK_INSERT, SHIFT, DefaultEditorKit.pasteAction);
            put(m, KeyEvent.VK_DELETE, SHIFT, DefaultEditorKit.cutAction);
            put(m, KeyEvent.VK_BACK_SLASH, CTRL, "unselect");
        }
        return m;
    }

    private static void put(InputMapUIResource m, int key, int modifiers, String action) {
        m.put(KeyStroke.getKeyStroke(key, modifiers), action);
    }
}
