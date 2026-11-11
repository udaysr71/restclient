package dev.restclient.ui;

import dev.restclient.vars.DynamicVars;
import dev.restclient.vars.VariableResolver;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.LayeredHighlighter;
import javax.swing.text.View;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Adds the variable conveniences to any text component:
 * <ul>
 *   <li>green chips for resolved {@code {{vars}}}, red wavy underline for undefined ones</li>
 *   <li>hover popover with the value, where it comes from, and Edit/Copy/Reveal buttons</li>
 *   <li>⌘/Ctrl-click to jump to the definition</li>
 *   <li>autocomplete after typing two opening braces, plus optional plain suggestions (header names)</li>
 *   <li>undo/redo</li>
 * </ul>
 */
public final class VarSupport {
    /** What the popover buttons do; provided by the main window. */
    public interface Actions {
        void editVariable(String name);

        void defineVariable(String name);
    }

    private static final String KEY = "rc.varSupport";
    private static final String UNDO_KEY = "rc.undo";
    private static final ChipPainter OK = new ChipPainter(false);
    private static final ChipPainter MISSING = new ChipPainter(true);

    private final JTextComponent c;
    private final Supplier<VariableResolver> resolver;
    private final Actions actions;
    private final List<Object> tags = new ArrayList<>();
    private final Set<String> revealed = new HashSet<>();
    private Function<String, List<String>> plainSuggestions;

    // hover popover
    private Popup popup;
    private JComponent popupContent;
    private VariableResolver.Token hoverToken;
    private VariableResolver.Token shownToken;
    private final Timer showTimer;
    private final Timer hideTimer;
    /** While the popover is open, checks that the pointer is still near the token or the popover. */
    private final Timer watchTimer;

    // autocomplete
    private final JPopupMenu completion = new JPopupMenu();
    private final DefaultListModel<String> completionModel = new DefaultListModel<>();
    private final JList<String> completionList = new JList<>(completionModel);
    private int completionStart = -1;
    private boolean completionPlain;

    private VarSupport(JTextComponent c, Supplier<VariableResolver> resolver, Actions actions) {
        this.c = c;
        this.resolver = resolver;
        this.actions = actions;
        showTimer = new Timer(450, e -> showPopover());
        showTimer.setRepeats(false);
        hideTimer = new Timer(250, e -> maybeHide());
        hideTimer.setRepeats(false);
        watchTimer = new Timer(200, e -> watch());

        completionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        completionList.setFocusable(false);
        completionList.setFont(Theme.mono(12.5f));
        completionList.setVisibleRowCount(8);
        completionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                acceptCompletion();
            }
        });
        javax.swing.DefaultListCellRenderer plain = new javax.swing.DefaultListCellRenderer();
        completionList.setCellRenderer(new dev.restclient.ui.laf.RoundedListRenderer(() -> {
            @SuppressWarnings("unchecked")
            javax.swing.ListCellRenderer<Object> r = (javax.swing.ListCellRenderer<Object>) (javax.swing.ListCellRenderer<?>) plain;
            return r;
        }));
        Theme.bg(completionList, Theme::menuBg);
        JScrollPane sp = new JScrollPane(completionList);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        completion.setFocusable(false);
        completion.add(sp);
    }

    public static VarSupport install(JTextComponent c, Supplier<VariableResolver> resolver, Actions actions) {
        VarSupport existing = of(c);
        if (existing != null) return existing;
        VarSupport vs = new VarSupport(c, resolver, actions);
        c.putClientProperty(KEY, vs);
        vs.listen();
        installUndo(c);
        vs.refresh();
        return vs;
    }

    public static VarSupport of(JComponent c) {
        Object o = c.getClientProperty(KEY);
        return o instanceof VarSupport v ? v : null;
    }

    public void setPlainSuggestions(Function<String, List<String>> s) {
        this.plainSuggestions = s;
    }

    private void listen() {
        c.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                SwingUtilities.invokeLater(VarSupport.this::refresh);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                SwingUtilities.invokeLater(VarSupport.this::refresh);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                VariableResolver.Token t = tokenAt(e.getPoint());
                if (t == null) {
                    hoverToken = null;
                    showTimer.stop();
                    if (popup != null) hideTimer.restart();
                    return;
                }
                c.setCursor(java.awt.Cursor.getPredefinedCursor(
                        e.isMetaDown() || e.isControlDown() ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.TEXT_CURSOR));
                if (!t.equals(hoverToken)) {
                    hoverToken = t;
                    if (popup != null && !t.equals(shownToken)) hideNow();
                    showTimer.restart();
                }
                hideTimer.stop();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverToken = null;
                showTimer.stop();
                if (popup != null) hideTimer.restart();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                VariableResolver.Token t = tokenAt(e.getPoint());
                boolean shortcut = (e.getModifiersEx() & Keys.MOD) != 0;
                if (t != null && shortcut && actions != null) {
                    e.consume();
                    hideNow();
                    if (DynamicVars.isDynamic(t.name())) return;
                    if (resolver.get().isDefined(t.name())) actions.editVariable(t.name());
                    else actions.defineVariable(t.name());
                } else {
                    hideNow();
                }
            }
        };
        c.addMouseListener(mouse);
        c.addMouseMotionListener(mouse);
        c.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                hideNow();
                if (!completion.isVisible()) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        moveCompletion(1);
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        moveCompletion(-1);
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        acceptCompletion();
                        e.consume();
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        completion.setVisible(false);
                        e.consume();
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.VK_UP || k == KeyEvent.VK_DOWN || k == KeyEvent.VK_ENTER || k == KeyEvent.VK_TAB
                        || k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_SHIFT || k == KeyEvent.VK_META
                        || k == KeyEvent.VK_CONTROL || k == KeyEvent.VK_ALT) return;
                if ((e.getModifiersEx() & (Keys.MOD | KeyEvent.ALT_DOWN_MASK)) != 0) return;
                if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_HOME || k == KeyEvent.VK_END) {
                    completion.setVisible(false);
                    return;
                }
                updateCompletion();
            }
        });
        c.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                completion.setVisible(false);
            }
        });
    }

    // ---- highlights ------------------------------------------------------------------------

    public void refresh() {
        Highlighter h = c.getHighlighter();
        if (h == null) return;
        for (Object t : tags) h.removeHighlight(t);
        tags.clear();
        VariableResolver r = resolver.get();
        for (VariableResolver.Token t : VariableResolver.tokens(c.getText())) {
            try {
                tags.add(h.addHighlight(t.start(), t.end(), r.isDefined(t.name()) ? OK : MISSING));
            } catch (BadLocationException ignored) {
                // text changed underneath us; the next refresh will fix it
            }
        }
        c.repaint();
    }

    /** One-off highlighting for table cell renderers (no listeners). */
    public static void highlightOnly(JTextComponent c, VariableResolver r) {
        Highlighter h = c.getHighlighter();
        if (h == null) return;
        h.removeAllHighlights();
        for (VariableResolver.Token t : VariableResolver.tokens(c.getText())) {
            try {
                h.addHighlight(t.start(), t.end(), r.isDefined(t.name()) ? OK : MISSING);
            } catch (BadLocationException ignored) {
                // ignore
            }
        }
    }

    private static final class ChipPainter extends LayeredHighlighter.LayerPainter {
        private final boolean missing;

        ChipPainter(boolean missing) {
            this.missing = missing;
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            paintLayer(g, p0, p1, bounds, c, null);
        }

        @Override
        public Shape paintLayer(Graphics g, int p0, int p1, Shape viewBounds, JTextComponent c, View view) {
            try {
                Rectangle2D a = c.modelToView2D(p0);
                Rectangle2D b = c.modelToView2D(p1);
                if (a == null || b == null || Math.abs(a.getY() - b.getY()) > 1) return null;
                Rectangle r = new Rectangle((int) a.getX() - 2, (int) a.getY() + 1,
                        (int) (b.getX() - a.getX()) + 4, (int) a.getHeight() - 2);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 6, 6);
                if (missing) {
                    g2.setColor(Theme.varMissingBg());
                    g2.fill(shape);
                    g2.setColor(Theme.varMissingLine());
                    g2.setStroke(new BasicStroke(1.1f));
                    Path2D wave = new Path2D.Float();
                    float y = r.y + r.height - 1.5f;
                    wave.moveTo(r.x + 1, y);
                    for (float x = r.x + 1; x < r.x + r.width - 1; x += 2) {
                        wave.lineTo(x + 1, y + ((int) ((x - r.x) / 2) % 2 == 0 ? -1.2f : 1.2f));
                    }
                    g2.draw(wave);
                } else {
                    g2.setColor(Theme.varOkBg());
                    g2.fill(shape);
                    g2.setColor(Theme.varOkBorder());
                    g2.draw(shape);
                }
                g2.dispose();
                return r;
            } catch (BadLocationException e) {
                return null;
            }
        }
    }

    // ---- hover popover ---------------------------------------------------------------------

    private VariableResolver.Token tokenAt(Point p) {
        int pos = c.viewToModel2D(p);
        if (pos < 0) return null;
        for (VariableResolver.Token t : VariableResolver.tokens(c.getText())) {
            if (pos < t.start() || pos > t.end()) continue;
            try {
                Rectangle2D a = c.modelToView2D(t.start());
                Rectangle2D b = c.modelToView2D(t.end());
                if (a == null || b == null) continue;
                if (p.x >= a.getX() - 2 && p.x <= b.getX() + 2 && p.y >= a.getY() && p.y <= a.getY() + a.getHeight()) {
                    return t;
                }
            } catch (BadLocationException ignored) {
                // ignore
            }
        }
        return null;
    }

    private void showPopover() {
        VariableResolver.Token t = hoverToken;
        if (t == null || !c.isShowing()) return;
        hideNow();
        try {
            Rectangle2D a = c.modelToView2D(t.start());
            if (a == null) return;
            popupContent = buildPopover(t.name());
            Point pt = new Point((int) a.getX() - 6, (int) (a.getY() + a.getHeight() + 6));
            SwingUtilities.convertPointToScreen(pt, c);
            popup = PopupFactory.getSharedInstance().getPopup(c, popupContent, pt.x, pt.y);
            popup.show();
            shownToken = t;
            watchTimer.start();
        } catch (BadLocationException ignored) {
            // ignore
        }
    }

    private void maybeHide() {
        if (popupContent != null && popupContent.isShowing()) {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi != null) {
                Point m = pi.getLocation();
                Rectangle r = new Rectangle(popupContent.getLocationOnScreen(), popupContent.getSize());
                r.grow(4, 8);
                if (r.contains(m)) {
                    hideTimer.restart();
                    return;
                }
            }
        }
        if (hoverToken != null && hoverToken.equals(shownToken)) return;
        hideNow();
    }

    private void watch() {
        if (popup == null) {
            watchTimer.stop();
            return;
        }
        java.awt.Window w = SwingUtilities.getWindowAncestor(c);
        java.awt.Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (!c.isShowing() || w == null || (active != null && active != w && !SwingUtilities.isDescendingFrom(popupContent, active))) {
            hideNow();
            return;
        }
        PointerInfo pi = MouseInfo.getPointerInfo();
        if (pi == null) return;
        Point m = pi.getLocation();
        Rectangle field = new Rectangle(c.getLocationOnScreen(), c.getSize());
        field.grow(6, 6);
        Rectangle pop = popupContent.isShowing() ? new Rectangle(popupContent.getLocationOnScreen(), popupContent.getSize()) : new Rectangle();
        pop.grow(10, 12);
        if (!field.contains(m) && !pop.contains(m)) hideNow();
    }

    public void hideNow() {
        showTimer.stop();
        hideTimer.stop();
        watchTimer.stop();
        if (popup != null) {
            popup.hide();
            popup = null;
            popupContent = null;
            shownToken = null;
        }
    }

    private JComponent buildPopover(String name) {
        VariableResolver r = resolver.get();
        List<VariableResolver.Resolution> all = r.lookupAll(name);
        Ui.RoundedPanel p = new Ui.RoundedPanel(new BorderLayout(), Theme::editorBg, Theme::separator, 12);
        JPanel box = Ui.transparent(null);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(Ui.pad(14, 16, 14, 16));
        p.add(box);

        JPanel head = Ui.transparent(new BorderLayout(12, 0));
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(Theme.mono(12.5f).deriveFont(Font.BOLD));
        head.add(nameLabel, BorderLayout.WEST);
        head.setAlignmentX(0f);
        box.add(head);

        if (DynamicVars.isDynamic(name)) {
            head.add(new Ui.Pill("Built in", Theme::okBg, Theme::okFg), BorderLayout.EAST);
            box.add(Ui.vgap(8));
            JLabel msg = Ui.wrappingSecondary(DynamicVars.describe(name) + ". Each place it's used gets its own value.", 270);
            msg.setAlignmentX(0f);
            box.add(msg);
        } else if (all.isEmpty()) {
            Ui.Pill pill = new Ui.Pill("Not defined", Theme::errBg, Theme::errFg);
            head.add(pill, BorderLayout.EAST);
            box.add(Ui.vgap(8));
            String where = r.environmentName() != null ? "the “" + r.environmentName() + "” environment, this collection or globals"
                    : "any environment, collection or globals";
            JLabel msg = Ui.wrappingSecondary("Not defined in " + where + ". It's sent literally as {{" + name + "}}.", 270);
            msg.setAlignmentX(0f);
            box.add(msg);
            box.add(Ui.vgap(10));
            String target = r.environmentName() != null ? r.environmentName() : "Globals";
            JButton define = Ui.button("Define in " + target + "…", e -> {
                hideNow();
                if (actions != null) actions.defineVariable(name);
            });
            JPanel buttons = Ui.row(0, define);
            buttons.setAlignmentX(0f);
            box.add(buttons);
        } else {
            VariableResolver.Resolution win = all.get(0);
            String scope = win.scope() == VariableResolver.Scope.ENVIRONMENT
                    ? win.sourceName() + " · environment" : win.scope() == VariableResolver.Scope.COLLECTION
                    ? win.sourceName() + " · collection" : "Globals";
            head.add(new Ui.Pill(scope, Theme::okBg, Theme::okFg), BorderLayout.EAST);
            box.add(Ui.vgap(8));

            boolean masked = win.secret() && !revealed.contains(name);
            String shown = masked ? VariableResolver.MASK : r.resolve(win.value());
            JLabel value = new JLabel("<html><div style='width:202px'>" + Ui.escape(shown.isEmpty() ? "(empty)" : shown) + "</div></html>");
            value.setFont(Theme.mono(12f));
            Ui.RoundedPanel valueBox = new Ui.RoundedPanel(new BorderLayout(), Theme::subtleBg, null, 8);
            valueBox.setBorder(Ui.pad(7, 10, 7, 10));
            valueBox.add(value);
            valueBox.setAlignmentX(0f);
            box.add(valueBox);

            if (all.size() > 1) {
                VariableResolver.Resolution next = all.get(1);
                String nv = next.secret() ? VariableResolver.MASK : next.value();
                box.add(Ui.vgap(6));
                JLabel ov = Ui.wrappingSecondary("Overrides the " + next.scope().label + " value " + nv, 270);
                ov.setFont(ov.getFont().deriveFont(ov.getFont().getSize2D() - 1f));
                ov.setAlignmentX(0f);
                box.add(ov);
            }
            box.add(Ui.vgap(10));
            String editLabel = win.scope() == VariableResolver.Scope.GLOBAL ? "Edit in Globals" : "Edit in " + win.sourceName();
            JButton edit = Ui.button(editLabel, e -> {
                hideNow();
                if (actions != null) actions.editVariable(name);
            });
            JButton copy = Ui.button("Copy value", e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(r.resolve(win.value())), null);
                hideNow();
            });
            JPanel buttons = Ui.row(6, edit, copy);
            if (win.secret()) {
                JButton reveal = Ui.button(masked ? "Reveal" : "Hide", e -> {
                    if (!revealed.remove(name)) revealed.add(name);
                    VariableResolver.Token t = shownToken;
                    hideNow();
                    hoverToken = t;
                    showPopover();
                });
                buttons.add(reveal);
            }
            buttons.setAlignmentX(0f);
            box.add(buttons);
        }
        p.setPreferredSize(new Dimension(Math.max(300, p.getPreferredSize().width), p.getPreferredSize().height));
        return p;
    }

    // ---- autocomplete ----------------------------------------------------------------------

    private void updateCompletion() {
        if (!c.isEditable() || !c.isShowing()) return;
        int caret = c.getCaretPosition();
        String before;
        try {
            before = c.getText(0, caret);
        } catch (BadLocationException e) {
            return;
        }
        int open = before.lastIndexOf("{{");
        if (open >= 0 && before.indexOf("}}", open) < 0) {
            String prefix = before.substring(open + 2).trim();
            if (prefix.matches("[A-Za-z0-9_.\\-$]*")) {
                List<String> matches = matchNames(prefix);
                if (!matches.isEmpty()) {
                    showCompletion(matches, open, false, caret);
                    return;
                }
            }
        }
        if (plainSuggestions != null && caret == c.getDocument().getLength() && !c.getText().isBlank()
                && !c.getText().contains("{{")) {
            List<String> s = plainSuggestions.apply(c.getText());
            if (s != null && !s.isEmpty() && !(s.size() == 1 && s.get(0).equals(c.getText()))) {
                showCompletion(s, 0, true, caret);
                return;
            }
        }
        completion.setVisible(false);
    }

    private List<String> matchNames(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        List<String> names = new ArrayList<>(resolver.get().names());
        if (p.startsWith("$")) names.addAll(DynamicVars.names()); // built-ins only once "$" is typed
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.startsWith(p)) starts.add(n);
            else if (l.contains(p)) contains.add(n);
        }
        starts.addAll(contains);
        return starts.size() > 20 ? starts.subList(0, 20) : starts;
    }

    private void showCompletion(List<String> items, int start, boolean plain, int caret) {
        completionModel.clear();
        for (String s : items) completionModel.addElement(s);
        completionList.setSelectedIndex(0);
        completionStart = start;
        completionPlain = plain;
        try {
            Rectangle2D r = c.modelToView2D(plain ? 0 : caret);
            if (r == null) return;
            completionList.setVisibleRowCount(Math.min(8, items.size()));
            completion.pack();
            completion.setPreferredSize(new Dimension(Math.max(220, completion.getPreferredSize().width), completion.getPreferredSize().height));
            completion.show(c, (int) r.getX(), (int) (r.getY() + r.getHeight() + 2));
            c.requestFocusInWindow();
        } catch (BadLocationException ignored) {
            // ignore
        }
    }

    private void moveCompletion(int delta) {
        int n = completionModel.size();
        if (n == 0) return;
        int i = (completionList.getSelectedIndex() + delta + n) % n;
        completionList.setSelectedIndex(i);
        completionList.ensureIndexIsVisible(i);
    }

    private void acceptCompletion() {
        String value = completionList.getSelectedValue();
        completion.setVisible(false);
        if (value == null) return;
        if (completionPlain) {
            c.setText(value);
            return;
        }
        int caret = c.getCaretPosition();
        try {
            String after = c.getText(caret, Math.min(2, c.getDocument().getLength() - caret));
            int end = after.startsWith("}}") ? caret + 2 : caret;
            c.getDocument().remove(completionStart, end - completionStart);
            String ins = "{{" + value + "}}";
            c.getDocument().insertString(completionStart, ins, null);
            c.setCaretPosition(completionStart + ins.length());
        } catch (BadLocationException ignored) {
            // ignore
        }
    }

    // ---- undo ------------------------------------------------------------------------------

    private static void installUndo(JTextComponent c) {
        if (c.getClientProperty(UNDO_KEY) != null) return;
        UndoManager um = new UndoManager();
        um.setLimit(200);
        c.getDocument().addUndoableEditListener(um);
        c.putClientProperty(UNDO_KEY, um);
        c.getInputMap().put(Keys.UNDO, "rc-undo");
        c.getInputMap().put(Keys.REDO, "rc-redo");
        if (!Platform.isMac()) c.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Keys.MOD | Keys.SHIFT), "rc-redo");
        c.getActionMap().put("rc-undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undo(c);
            }
        });
        c.getActionMap().put("rc-redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redo(c);
            }
        });
    }

    /** Undo in whatever text component has focus (used by the Edit menu). */
    public static boolean undo(Component focus) {
        if (focus instanceof JComponent jc && jc.getClientProperty(UNDO_KEY) instanceof UndoManager um) {
            try {
                if (um.canUndo()) um.undo();
            } catch (CannotUndoException ignored) {
                // ignore
            }
            return true;
        }
        return false;
    }

    public static boolean redo(Component focus) {
        if (focus instanceof JComponent jc && jc.getClientProperty(UNDO_KEY) instanceof UndoManager um) {
            try {
                if (um.canRedo()) um.redo();
            } catch (CannotRedoException ignored) {
                // ignore
            }
            return true;
        }
        return false;
    }

    /** Ensures there is an undo manager on a plain text component (no variable features). */
    public static void undoable(JTextComponent c) {
        installUndo(c);
    }

    /** Forgets undo history, e.g. after loading a request into an editor. */
    public static void discardEdits(JComponent c) {
        if (c.getClientProperty(UNDO_KEY) instanceof UndoManager um) um.discardAllEdits();
    }
}
