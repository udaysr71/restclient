package dev.restclient.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small building blocks shared by the UI. Client-property keys are read by the app's own look and feel. */
public final class Ui {
    private Ui() {
    }

    // ---- look-and-feel client properties ---------------------------------------------------

    public static <T extends JTextField> T placeholder(T field, String text) {
        field.putClientProperty("JTextField.placeholderText", text);
        return field;
    }

    public static <T extends JTextField> T leadingIcon(T field, Icon icon) {
        field.putClientProperty("JTextField.leadingIcon", icon);
        return field;
    }

    public static <T extends JTextField> T searchField(T field) {
        return leadingIcon(field, Icons.get("search", 14));
    }

    // ---- buttons ---------------------------------------------------------------------------

    public static JButton toolbarButton(Icon icon, String tooltip, ActionListener action) {
        // Icon buttons get at least a 30x30 target even when the glyph is small (Fitts's law; Practical UI,
        // "Minimise interaction cost"). Callers that set a size on purpose (tab close buttons) keep theirs.
        JButton b = new JButton(icon) {
            @Override
            public java.awt.Dimension getPreferredSize() {
                java.awt.Dimension d = super.getPreferredSize();
                if (isPreferredSizeSet() || getText() != null && !getText().isEmpty()) return d;
                int min = Theme.scaled(30);
                return new java.awt.Dimension(Math.max(min, d.width), Math.max(min, d.height));
            }
        };
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tooltip);
        b.getAccessibleContext().setAccessibleName(tooltip);
        b.setRequestFocusEnabled(false); // reachable with Tab, but a click doesn't steal focus
        if (action != null) b.addActionListener(action);
        return b;
    }

    /**
     * Shows a menu just below a button, 2px clear of it. If it would run past the window's right edge it lines
     * up with the button's right edge instead, so menus from buttons near the right edge are never cut off.
     */
    public static void showBelow(javax.swing.JPopupMenu m, Component c) {
        java.awt.Dimension d = m.getPreferredSize();
        int x = 0;
        Window w = SwingUtilities.getWindowAncestor(c);
        if (w != null) {
            java.awt.Point p = SwingUtilities.convertPoint(c, 0, 0, w);
            if (p.x + d.width > w.getWidth() - 8) x = c.getWidth() - d.width;
        }
        m.show(c, x, c.getHeight() + 2);
    }

    public static JButton button(String text, ActionListener action) {
        JButton b = new JButton(text);
        if (action != null) b.addActionListener(action);
        return b;
    }

    public static JButton button(String text, Icon icon, ActionListener action) {
        JButton b = button(text, action);
        b.setIcon(icon);
        return b;
    }

    public static JButton linkButton(String text, ActionListener action) {
        JButton b = new JButton(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(Theme.link());
            }
        };
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setForeground(Theme.link());
        b.setMargin(new Insets(0, 2, 0, 2));
        if (action != null) b.addActionListener(action);
        return b;
    }

    /** Filled accent button for the primary action. */
    public static class AccentButton extends JButton {
        public AccentButton(String text) {
            super(text);
            setFont(getFont().deriveFont(Font.BOLD));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBackground(Theme.accent());
            setForeground(Theme.onAccent());
            putClientProperty("JButton.buttonType", null);
            // from the look and feel's font, so it follows zoom changes
            setFont(javax.swing.UIManager.getFont("Button.font").deriveFont(Font.BOLD));
            setBorderPainted(false);
        }
    }

    public static AccentButton accent(String text, ActionListener action) {
        AccentButton b = new AccentButton(text);
        if (action != null) b.addActionListener(action);
        return b;
    }

    // ---- labels ----------------------------------------------------------------------------

    public static JLabel label(String text) {
        return new JLabel(text);
    }

    /** Gray, smaller supporting text. */
    public static JLabel secondary(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(Theme.secondary());
            }
        };
        l.setForeground(Theme.secondary());
        return l;
    }

    public static JLabel small(String text) {
        JLabel l = secondary(text);
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1.5f));
        return l;
    }

    public static JLabel heading(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, size));
        return l;
    }

    /** Section caption like "Requests" in lists. */
    public static JLabel caption(String text) {
        JLabel l = secondary(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() - 2f));
        return l;
    }

    /**
     * Converts a screen width to the unit Swing's HTML renderer uses for CSS "px" (it treats them as points,
     * so 300 screen pixels is about 225 CSS px).
     */
    public static int css(int screenPx) {
        return Math.round(screenPx * 0.75f);
    }

    /** A label that wraps to the given width. */
    public static JLabel wrapping(String text, int width) {
        return new JLabel("<html><div style='width:" + css(width) + "px'>" + escape(text) + "</div></html>");
    }

    public static JLabel wrappingSecondary(String text, int width) {
        JLabel l = secondary("<html><div style='width:" + css(width) + "px'>" + escape(text) + "</div></html>");
        return l;
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- layout helpers --------------------------------------------------------------------

    public static JPanel row(int gap, Component... children) {
        // FlowLayout puts a gap before the first child too; shift it out so rows line up with labels above.
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0) {
            @Override
            public void layoutContainer(java.awt.Container target) {
                super.layoutContainer(target);
                for (Component c : target.getComponents()) c.setLocation(c.getX() - getHgap(), c.getY());
            }
        });
        p.setOpaque(false);
        for (Component c : children) p.add(c);
        return p;
    }

    public static JPanel transparent(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    public static Component hgap(int w) {
        return Box.createRigidArea(new Dimension(w, 0));
    }

    public static Component vgap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    public static Border pad(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /** A 1px line on the given sides, colored from the theme at paint time. */
    public static Border line(int top, int left, int bottom, int right) {
        return new Border() {
            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                g.setColor(Theme.separator());
                if (top > 0) g.fillRect(x, y, w, top);
                if (bottom > 0) g.fillRect(x, y + h - bottom, w, bottom);
                if (left > 0) g.fillRect(x, y, left, h);
                if (right > 0) g.fillRect(x + w - right, y, right, h);
            }

            @Override
            public Insets getBorderInsets(Component c) {
                return new Insets(top, left, bottom, right);
            }

            @Override
            public boolean isBorderOpaque() {
                return false;
            }
        };
    }

    /** Panel with a rounded background and optional border, colors resolved at paint time. */
    public static class RoundedPanel extends JPanel {
        private final Supplier<Color> bg;
        private final Supplier<Color> border;
        private final int arc;
        private int borderWidth = 1;

        public RoundedPanel(LayoutManager lm, Supplier<Color> bg, Supplier<Color> border, int arc) {
            super(lm);
            this.bg = bg;
            this.border = border;
            this.arc = arc;
            setOpaque(false);
        }

        public RoundedPanel borderWidth(int w) {
            this.borderWidth = w;
            repaint();
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float half = borderWidth / 2f;
            RoundRectangle2D r = new RoundRectangle2D.Float(half, half, getWidth() - borderWidth, getHeight() - borderWidth, arc, arc);
            if (bg != null && bg.get() != null) {
                g2.setColor(bg.get());
                g2.fill(r);
            }
            if (border != null && border.get() != null) {
                g2.setColor(border.get());
                g2.setStroke(new BasicStroke(borderWidth));
                g2.draw(r);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static RoundedPanel card(LayoutManager lm) {
        RoundedPanel p = new RoundedPanel(lm, Theme::editorBg, Theme::separator, 12);
        p.setBorder(pad(12, 14, 12, 14));
        return p;
    }

    /** Panel for use inside a JScrollPane that always fits the viewport width. */
    public static class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
        public WidthTrackingPanel(LayoutManager lm) {
            super(lm);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return r.height - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Sets a split's divider to a proportion once it has a real size. */
    public static void initialDivider(javax.swing.JSplitPane split, double ratio) {
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (split.getWidth() > 0 && split.getHeight() > 0) {
                    split.setDividerLocation(ratio);
                    split.removeComponentListener(this);
                }
            }
        });
    }

    // ---- pill / badge ----------------------------------------------------------------------

    /** Rounded colored label: status badges, scope chips, warnings. */
    public static class Pill extends JLabel {
        private Supplier<Color> bg;
        private Supplier<Color> fg;

        public Pill(String text, Supplier<Color> bg, Supplier<Color> fg) {
            super(text);
            this.bg = bg;
            this.fg = fg;
            setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1f));
            setBorder(pad(2, 8, 2, 8));
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        public void setColors(Supplier<Color> bg, Supplier<Color> fg) {
            this.bg = bg;
            this.fg = fg;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (bg != null) {
                g2.setColor(bg.get());
                int h = getHeight();
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, Math.min(h, 14), Math.min(h, 14)));
            }
            g2.dispose();
            if (fg != null) setForeground(fg.get());
            super.paintComponent(g);
        }
    }

    public static Pill statusPill(int status, String text) {
        return new Pill(text, () -> Theme.statusColors(status)[0], () -> Theme.statusColors(status)[1]);
    }

    /** A small filled circle, e.g. the environment color dot. */
    public static Icon dot(Supplier<Color> color, int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color col = color.get();
                if (col == null) {
                    g2.setColor(Theme.secondary());
                    g2.setStroke(new BasicStroke(1.4f));
                    g2.drawOval(x + 1, y + 1, size - 2, size - 2);
                } else {
                    g2.setColor(col);
                    g2.fillOval(x, y, size, size);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    // ---- segmented control -----------------------------------------------------------------

    /** Mutually exclusive toggle buttons on a shared rounded track. */
    public static class Segmented extends RoundedPanel {
        private final List<JToggleButton> buttons = new ArrayList<>();
        private final ButtonGroup group = new ButtonGroup();
        private final List<Consumer<Integer>> listeners = new ArrayList<>();

        public Segmented(String... labels) {
            super(new java.awt.GridLayout(1, labels.length, 2, 0), Theme::subtleBg, null, 10);
            setBorder(pad(2, 2, 2, 2));
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                JToggleButton b = new JToggleButton(labels[i]);
                b.putClientProperty("JButton.buttonType", "segment");
                b.putClientProperty("rc.thumbByParent", Boolean.TRUE);
                b.setRequestFocusEnabled(false); // reachable with Tab, but a click doesn't steal focus
                b.addActionListener(e -> fire(index));
                group.add(b);
                buttons.add(b);
                add(b);
            }
            if (!buttons.isEmpty()) buttons.get(0).setSelected(true);
            updateStyles();
        }

        /** The selected-segment thumb slides from the old segment to the new one. */
        private java.awt.geom.Rectangle2D.Double thumb;
        private Motion.Handle thumbAnim;
        private int thumbFor = -1;

        private java.awt.Rectangle thumbTarget() {
            int i = selectedIndex();
            if (i < 0) return null;
            AbstractButton b = buttons.get(i);
            return b.getWidth() == 0 ? null : b.getBounds();
        }

        private void slideThumb() {
            java.awt.Rectangle to = thumbTarget();
            int idx = selectedIndex();
            if (thumbFor == idx) return;
            java.awt.geom.Rectangle2D.Double from = thumb;
            if (from == null && thumbFor >= 0 && thumbFor < buttons.size() && buttons.get(thumbFor).getWidth() > 0) {
                java.awt.Rectangle b = buttons.get(thumbFor).getBounds();
                from = new java.awt.geom.Rectangle2D.Double(b.x, b.y, b.width, b.height);
            }
            java.awt.geom.Rectangle2D.Double start = from;
            thumbFor = idx;
            if (thumbAnim != null) thumbAnim.stop();
            if (from == null || to == null || !isShowing() || !Motion.enabled()) {
                thumb = null;
                repaint();
                return;
            }
            thumbAnim = Motion.animate(200, t -> {
                java.awt.Rectangle target = thumbTarget();
                if (target == null) return;
                thumb = new java.awt.geom.Rectangle2D.Double(Motion.lerp(start.x, target.x, t), target.y,
                        Motion.lerp(start.width, target.width, t), target.height);
                repaint();
            }, () -> {
                thumb = null;
                thumbAnim = null;
                repaint();
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            java.awt.geom.Rectangle2D r = thumb;
            if (r == null) {
                java.awt.Rectangle t = thumbTarget();
                if (t == null) return;
                r = t;
                thumbFor = selectedIndex();
            }
            Graphics2D g2 = dev.restclient.ui.laf.Paint.smooth(g);
            g2.translate(r.getX(), r.getY());
            boolean dark = Theme.dark();
            dev.restclient.ui.laf.Paint.raised(g2, (float) r.getWidth(), (float) r.getHeight(), 8, Theme.segmentSelected(),
                    Theme.segmentSelected(), dark ? new Color(255, 255, 255, 22) : null, new Color(0, 0, 0, dark ? 90 : 26));
            if (!dark) dev.restclient.ui.laf.Paint.outline(g2, 0, 0, (float) r.getWidth(), (float) r.getHeight() - 1, 8, 1f,
                    Theme.fieldBorder());
            g2.dispose();
        }

        private void fire(int index) {
            slideThumb();
            updateStyles();
            for (Consumer<Integer> l : listeners) l.accept(index);
        }

        private void updateStyles() {
            for (JToggleButton b : buttons) {
                b.setFont(b.getFont().deriveFont(b.isSelected() ? Font.BOLD : Font.PLAIN));
            }
        }

        public void onChange(Consumer<Integer> l) {
            listeners.add(l);
        }

        /** Tighter padding and a smaller font, for narrow places like the sidebar. */
        public Segmented compact() {
            for (JToggleButton b : buttons) {
                b.setMargin(new Insets(2, 3, 2, 3));
                b.setFont(b.getFont().deriveFont(b.getFont().getSize2D() - 1.5f));
            }
            return this;
        }

        public void setSelectedIndex(int i) {
            if (i >= 0 && i < buttons.size()) {
                buttons.get(i).setSelected(true);
                slideThumb();
                updateStyles();
            }
        }

        public int selectedIndex() {
            for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).isSelected()) return i;
            return -1;
        }

        public AbstractButton button(int i) {
            return buttons.get(i);
        }
    }

    // ---- dialogs ---------------------------------------------------------------------------

    /** Modal dialog that shows as a sheet attached to the window title on macOS. */
    public static JDialog sheet(Window owner, String title) {
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.DOCUMENT_MODAL);
        if (Platform.isMac()) d.getRootPane().putClientProperty("apple.awt.documentModalSheet", Boolean.TRUE);
        WindowChrome.install(d, false);
        Motion.fadeIn(d);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        closeOnEscape(d);
        return d;
    }

    public static void closeOnEscape(JDialog d) {
        JRootPane rp = d.getRootPane();
        rp.registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /** A footer with buttons in the platform's order: Windows puts the primary button first. */
    public static JPanel buttonBar(JButton primary, JButton... others) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        p.setOpaque(false);
        if (Platform.isWindows()) {
            p.add(primary);
            for (JButton b : others) p.add(b);
        } else {
            for (JButton b : others) p.add(b);
            p.add(primary);
        }
        return p;
    }

    /**
     * Shows an option pane in a dialog with the app's own title bar (JOptionPane's own dialogs always get the
     * system title bar, which is white over a dark app on Windows). Returns the pane's value.
     */
    public static Object showPane(Component parent, JOptionPane pane, String title) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.DOCUMENT_MODAL);
        WindowChrome.install(d, true);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        closeOnEscape(d);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(pad(14, 18, 14, 18));
        pane.setBorder(BorderFactory.createEmptyBorder());
        wrap.add(pane);
        d.setContentPane(wrap);
        pane.addPropertyChangeListener(e -> {
            if (d.isVisible() && (JOptionPane.VALUE_PROPERTY.equals(e.getPropertyName())
                    || JOptionPane.INPUT_VALUE_PROPERTY.equals(e.getPropertyName()) && pane.getValue() != JOptionPane.UNINITIALIZED_VALUE)) {
                d.dispose();
            }
        });
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                pane.selectInitialValue();
            }
        });
        d.pack();
        d.setLocationRelativeTo(owner);
        Motion.fadeIn(d);
        d.setVisible(true);
        return pane.getValue();
    }

    private static int optionIndex(JOptionPane pane, Object value) {
        Object[] opts = pane.getOptions();
        if (value == null || opts == null) return JOptionPane.CLOSED_OPTION;
        for (int i = 0; i < opts.length; i++) if (opts[i].equals(value)) return i;
        return JOptionPane.CLOSED_OPTION;
    }

    /** JOptionPane.showOptionDialog, in a themed dialog. */
    public static int option(Component parent, Object message, String title, int type, Object[] options, Object initial) {
        JOptionPane pane = new JOptionPane(message, type, JOptionPane.DEFAULT_OPTION, null, options, initial);
        return optionIndex(pane, showPane(parent, pane, title));
    }

    /** JOptionPane.showMessageDialog, in a themed dialog. */
    public static void message(Component parent, Object message, String title, int type, javax.swing.Icon icon) {
        showPane(parent, new JOptionPane(message, type, JOptionPane.DEFAULT_OPTION, icon), title);
    }

    /** Yes/Cancel confirmation; primary label describes the action ("Delete", "Clear History"). */
    public static boolean confirm(Component parent, String title, String message, String primary) {
        String cancel = "Cancel";
        Object[] options = Platform.isWindows() ? new Object[]{primary, cancel} : new Object[]{cancel, primary};
        int r = option(parent, message, title, JOptionPane.WARNING_MESSAGE, options, primary);
        return r >= 0 && primary.equals(options[r]);
    }

    public enum SaveChoice { SAVE, DISCARD, CANCEL }

    public static SaveChoice askSave(Component parent, String name) {
        String save = "Save";
        // macOS and Windows say "Don't Save"; GNOME-style Linux dialogs say "Discard".
        String discard = Platform.isLinux() ? "Discard" : "Don't Save";
        String cancel = "Cancel";
        Object[] options = Platform.isWindows() ? new Object[]{save, discard, cancel} : new Object[]{discard, cancel, save};
        int r = option(parent, "Do you want to save the changes to “" + name + "”?", "Unsaved changes",
                JOptionPane.QUESTION_MESSAGE, options, save);
        if (r < 0) return SaveChoice.CANCEL;
        Object o = options[r];
        return o.equals(save) ? SaveChoice.SAVE : o.equals(discard) ? SaveChoice.DISCARD : SaveChoice.CANCEL;
    }

    public static String prompt(Component parent, String title, String label, String initial) {
        JOptionPane pane = new JOptionPane(label, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        pane.setWantsInput(true);
        pane.setInitialSelectionValue(initial);
        Object v = showPane(parent, pane, title);
        if (!(v instanceof Integer i) || i != JOptionPane.OK_OPTION) return null;
        Object r = pane.getInputValue();
        if (r == null || r == JOptionPane.UNINITIALIZED_VALUE) return null;
        String s = r.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public static void error(Component parent, String title, String message) {
        message(parent, message, title, JOptionPane.ERROR_MESSAGE, null);
    }
}
