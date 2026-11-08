package dev.restclient.ui;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/** Robot and reflection helpers for the end-to-end checks, which drive the real app on a real (virtual) screen. */
final class Drive {
    static Robot robot;
    static MainFrame frame;
    static int passed;
    static final List<String> failures = new ArrayList<>();
    static final int MOD = Platform.isMac() ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
    private static String section = "";

    private Drive() {
    }

    static void section(String name) {
        section = name;
        System.out.println();
        System.out.println("  — " + name);
    }

    static void check(String name, Callable<Boolean> cond) {
        check(name, cond, 2000);
    }

    static void check(String name, Callable<Boolean> cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        boolean ok = false;
        String why = "";
        while (true) {
            try {
                ok = Boolean.TRUE.equals(call(cond));
            } catch (Exception e) {
                ok = false;
                why = " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
            }
            if (ok || System.currentTimeMillis() > end) break;
            robot.delay(50);
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : why));
        if (ok) passed++;
        else failures.add(section + ": " + name + why);
    }

    /** Records a check whose value is already known (e.g. computed off the EDT). */
    static void verify(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok || detail == null ? "" : "   [" + detail + "]"));
        if (ok) passed++;
        else failures.add(section + ": " + name + (detail == null ? "" : " [" + detail + "]"));
    }

    static void waitFor(String what, Callable<Boolean> cond, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            try {
                if (Boolean.TRUE.equals(call(cond))) return;
            } catch (Exception ignored) {
                // keep waiting
            }
            if (System.currentTimeMillis() > end) throw new IllegalStateException("timed out waiting for " + what);
            robot.delay(60);
        }
    }

    static <T> T call(Callable<T> c) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Object[] out = new Object[1];
        RuntimeException[] err = new RuntimeException[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    out[0] = c.call();
                } catch (Exception e) {
                    err[0] = e instanceof RuntimeException r ? r : new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (err[0] != null) throw err[0];
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    static void edt(Runnable r) {
        call(() -> {
            r.run();
            return null;
        });
    }

    /** Runs something that opens a modal dialog, without blocking on it. */
    static void later(Runnable r) {
        SwingUtilities.invokeLater(r);
        robot.delay(350);
        robot.waitForIdle();
    }

    static void key(int... codes) {
        for (int c : codes) robot.keyPress(c);
        for (int i = codes.length - 1; i >= 0; i--) robot.keyRelease(codes[i]);
        robot.waitForIdle();
        robot.delay(40);
    }

    static void type(String s) {
        for (char ch : s.toCharArray()) {
            int code = KeyEvent.getExtendedKeyCodeForChar(ch);
            boolean shift = Character.isUpperCase(ch) || "{}\":?_+|<>~!@#$%^&*()".indexOf(ch) >= 0;
            switch (ch) {
                case '{' -> code = KeyEvent.VK_OPEN_BRACKET;
                case '}' -> code = KeyEvent.VK_CLOSE_BRACKET;
                case '"' -> code = KeyEvent.VK_QUOTE;
                case ':' -> code = KeyEvent.VK_SEMICOLON;
                case '?' -> code = KeyEvent.VK_SLASH;
                case '_' -> code = KeyEvent.VK_MINUS;
                case '>' -> code = KeyEvent.VK_PERIOD;
                case '@' -> code = KeyEvent.VK_2;
                case '#' -> code = KeyEvent.VK_3;
                default -> {
                }
            }
            if (shift) key(KeyEvent.VK_SHIFT, code);
            else key(code);
        }
    }

    static String clipboard() {
        return call(() -> {
            try {
                return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            } catch (Exception e) {
                return "";
            }
        });
    }

    static void click(Component c) {
        Point p = call(() -> {
            Point s = c.getLocationOnScreen();
            return new Point(s.x + c.getWidth() / 2, s.y + c.getHeight() / 2);
        });
        clickAt(p, InputEvent.BUTTON1_DOWN_MASK, 1);
    }

    static void doubleClickAt(Point p) {
        clickAt(p, InputEvent.BUTTON1_DOWN_MASK, 2);
    }

    static void clickAt(Point p, int button, int count) {
        robot.mouseMove(p.x, p.y);
        for (int i = 0; i < count; i++) {
            robot.mousePress(button);
            robot.mouseRelease(button);
            robot.delay(30);
        }
        robot.waitForIdle();
        robot.delay(120);
    }

    static void clickCell(JTable t, int row, int col) {
        Point p = call(() -> {
            Rectangle r = t.getCellRect(row, col, true);
            t.scrollRectToVisible(r);
            Point s = t.getLocationOnScreen();
            return new Point(s.x + r.x + Math.min(r.width / 2, 40), s.y + r.y + r.height / 2);
        });
        clickAt(p, InputEvent.BUTTON1_DOWN_MASK, 1);
    }

    static Component focusOwner() {
        return call(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    static RequestTab currentTab() {
        return call(frame::currentRequestTab);
    }

    static TabStrip tabStrip() {
        return find(frame.getContentPane(), TabStrip.class);
    }

    static AppContext ctx() {
        return field(frame, "ctx");
    }

    static javax.swing.Action action(String id) {
        java.util.Map<String, javax.swing.Action> m = field(frame, "actions");
        return m.get(id);
    }

    static void run(String actionId) {
        edt(() -> action(actionId).actionPerformed(new java.awt.event.ActionEvent(frame, 0, actionId)));
        robot.delay(150);
    }

    static AbstractButton visibleButton(String text) {
        return call(() -> {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                AbstractButton b = find(w, AbstractButton.class, x -> x.isShowing() && text.equals(x.getText()));
                if (b != null) return b;
            }
            return null;
        });
    }

    /** Picks a segment the way a click does (setSelectedIndex alone doesn't notify listeners). */
    static void segment(Ui.Segmented s, int index) {
        edt(() -> {
            List<javax.swing.JToggleButton> buttons = field(s, "buttons");
            buttons.get(index).doClick();
        });
        robot.delay(120);
    }

    /** Clicks a button that opens a modal dialog, without waiting for the dialog to close. */
    static void pressLater(String text) {
        AbstractButton b = visibleButton(text);
        if (b == null) {
            System.out.println("       (button not found: " + text + ")");
            return;
        }
        SwingUtilities.invokeLater(b::doClick);
        robot.delay(400);
        robot.waitForIdle();
    }

    /** Runs a menu action that opens a modal dialog, without waiting for it to close. */
    static void runLater(String actionId) {
        SwingUtilities.invokeLater(() -> action(actionId).actionPerformed(new java.awt.event.ActionEvent(frame, 0, actionId)));
        robot.delay(400);
        robot.waitForIdle();
    }

    /** Clicks a visible button by its text; false if there isn't one. */
    static boolean press(String text) {
        AbstractButton b = visibleButton(text);
        if (b == null) {
            System.out.println("       (button not found: " + text + ")");
            return false;
        }
        edt(b::doClick);
        robot.delay(250);
        robot.waitForIdle();
        return true;
    }

    static JDialog topDialog() {
        return call(() -> {
            JDialog top = null;
            for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof JDialog d) top = d;
            return top;
        });
    }

    static boolean dialogShowing(Predicate<JDialog> p) {
        return call(() -> {
            for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof JDialog d && p.test(d)) return true;
            return false;
        });
    }

    static boolean anyDialog() {
        return dialogShowing(d -> true);
    }

    /** Closes every open dialog (Escape, then dispose), so one failed step can't block the next. */
    static void closeDialogs() {
        for (int i = 0; i < 4 && anyDialog(); i++) {
            key(KeyEvent.VK_ESCAPE);
            robot.delay(200);
        }
        edt(() -> {
            for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof JDialog d) d.dispose();
        });
        robot.delay(150);
        refocus();
    }

    /**
     * Gives the main window the keyboard back after a dialog closes. Xvfb has no window manager, so focus doesn't
     * return on its own the way it does on a desktop; clicking an empty part of the toolbar does what a user's click would.
     */
    static void refocus() {
        try {
            Point p = call(() -> {
                // the middle of the toolbar row: no buttons there, so a click only gives the window focus
                Component content = frame.getContentPane();
                if (!content.isShowing()) return null;
                Point s = content.getLocationOnScreen();
                return new Point(s.x + content.getWidth() / 2, s.y + 18);
            });
            if (p != null) clickAt(p, InputEvent.BUTTON1_DOWN_MASK, 1);
        } catch (RuntimeException ignored) {
            // the window is busy or hidden; the next step will report what it needs
        }
    }

    static boolean anyLabel(Container root, Predicate<String> p) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel l && l.isShowing() && l.getText() != null && p.test(l.getText())) return true;
            if (c instanceof AbstractButton b && b.isShowing() && b.getText() != null && p.test(b.getText())) return true;
            if (c instanceof Container cc && anyLabel(cc, p)) return true;
        }
        return false;
    }

    static boolean anyText(Container root, Predicate<String> p) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextComponent t && t.isShowing() && p.test(t.getText())) return true;
            if (c instanceof Container cc && anyText(cc, p)) return true;
        }
        return false;
    }

    static <T> T find(Container root, Class<T> type) {
        return find(root, type, x -> true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T find(Container root, Class<T> type, Predicate<? super T> p) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && ((Predicate) p).test(c)) return (T) c;
            if (c instanceof Container cc) {
                T r = find(cc, type, p);
                if (r != null) return r;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, String name) {
        Class<?> k = owner.getClass();
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(owner);
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no field " + name + " on " + owner.getClass());
    }

    @SuppressWarnings("unchecked")
    static <T> T invoke(Object owner, String name, Object... args) {
        for (Method m : owner.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try {
                    m.setAccessible(true);
                    return (T) m.invoke(owner, args);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
                }
            }
        }
        throw new IllegalStateException("no method " + name + "/" + args.length);
    }
}
