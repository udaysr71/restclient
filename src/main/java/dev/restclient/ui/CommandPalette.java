package dev.restclient.ui;

import dev.restclient.model.HttpMethod;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * ⌘K / Ctrl+K: one search box for requests, environments, actions and history.
 * Prefixes narrow it: {@code >} actions, {@code @} environments, {@code #} history.
 */
public final class CommandPalette {
    public enum Group {
        RECENT("Recent"), REQUESTS("Requests"), ENVIRONMENTS("Environments"), ACTIONS("Actions"), HISTORY("History");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    /** One result. {@code runAlt} is ⌘↩ / Ctrl+Enter (open in a new tab). */
    public record Item(Group group, String label, String detail, HttpMethod method, Icon icon, KeyStroke shortcut,
                       Runnable run, Runnable runAlt) {
    }

    private record Match(Item item, int score, boolean[] hits) {
    }

    private final JDialog dialog;
    private final JTextField input = new JTextField();
    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(model);
    private final Supplier<List<Item>> source;
    private final List<Item> items;

    private boolean shadow;

    private CommandPalette(java.awt.Frame owner, Supplier<List<Item>> source) {
        this.source = source;
        this.items = source.get();
        dialog = new JDialog(owner);
        dialog.setName("rc.palette");
        dialog.setUndecorated(true);
        shadow = Shadow.translucencySupported();
        dialog.setModal(false);
        build();
    }

    public static void open(java.awt.Frame owner, Supplier<List<Item>> source) {
        CommandPalette p = new CommandPalette(owner, source);
        Insets s = p.shadow ? Shadow.INSETS : new Insets(0, 0, 0, 0);
        p.dialog.setSize(new Dimension(Math.min(640, owner.getWidth() - 40) + s.left + s.right, 460 + s.top + s.bottom));
        Point o = owner.getLocationOnScreen();
        p.dialog.setLocation(o.x + (owner.getWidth() - p.dialog.getWidth()) / 2, o.y + 84 - s.top);
        Motion.fadeIn(p.dialog);
        p.dialog.setVisible(true);
        SwingUtilities.invokeLater(p.input::requestFocusInWindow);
    }

    private void build() {
        Ui.RoundedPanel root = new Ui.RoundedPanel(new BorderLayout(), Theme::editorBg, Theme::separator, 14);
        root.borderWidth(1);

        JPanel top = Ui.transparent(new BorderLayout(10, 0));
        top.setBorder(BorderFactory.createCompoundBorder(Ui.line(0, 0, 1, 0), Ui.pad(12, 14, 12, 14)));
        top.add(new JLabel(Icons.get("search", 18)), BorderLayout.WEST);
        input.setBorder(BorderFactory.createEmptyBorder());
        input.setOpaque(false);
        input.setFont(input.getFont().deriveFont(17f));
        input.putClientProperty("JTextField.placeholderText", "Search requests, environments and actions");
        input.getAccessibleContext().setAccessibleName("Search requests, environments and actions");
        top.add(input, BorderLayout.CENTER);
        top.add(new Ui.Pill(Platform.isMac() ? "esc" : "Esc", Theme::subtleBg, Theme::secondary), BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setOpaque(false);
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && model.get(i) instanceof Match) {
                    list.setSelectedIndex(i);
                    run((e.getModifiersEx() & Keys.MOD) != 0);
                }
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(Ui.pad(4, 6, 4, 6));
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        root.add(sp, BorderLayout.CENTER);

        JPanel foot = Ui.transparent(new BorderLayout());
        foot.setBorder(BorderFactory.createCompoundBorder(Ui.line(1, 0, 0, 0), Ui.pad(7, 14, 7, 14)));
        String enter = Platform.isMac() ? "↩" : "Enter";
        foot.add(Ui.small("↑↓ Navigate    " + enter + " Open    " + Keys.label(Keys.SEND) + " Open in new tab"), BorderLayout.WEST);
        foot.add(Ui.small("> actions · @ environments · # history"), BorderLayout.EAST);
        root.add(foot, BorderLayout.SOUTH);

        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), dialog::dispose);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> move(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> move(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), () -> run(false));
        bind(Keys.SEND, () -> run(true));
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                dialog.dispose();
            }
        });
        if (shadow) shadow = Shadow.makeTranslucent(dialog);
        if (shadow) {
            dialog.setContentPane(new Shadow.Frame(root, 14));
        } else {
            dialog.setContentPane(root);
        }
        if (!shadow) {
            // No see-through window: square corners in the palette's own colour, never a black or white edge.
            root.setOpaque(true);
            Theme.bg(root, Theme::editorBg);
            dialog.setBackground(Theme.editorBg());
        }
        filter();
    }

    private void bind(KeyStroke ks, Runnable r) {
        String key = "rc-" + ks;
        input.getInputMap(JComponent.WHEN_FOCUSED).put(ks, key);
        input.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void move(int delta) {
        int n = model.size();
        if (n == 0) return;
        int i = list.getSelectedIndex();
        for (int step = 0; step < n; step++) {
            i = (i + delta + n) % n;
            if (model.get(i) instanceof Match) break;
        }
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void run(boolean alt) {
        if (!(list.getSelectedValue() instanceof Match match)) return;
        Item item = match.item();
        dialog.dispose();
        Runnable r = alt && item.runAlt() != null ? item.runAlt() : item.run();
        SwingUtilities.invokeLater(r);
    }

    private void filter() {
        String q = input.getText().strip();
        Group only = null;
        if (q.startsWith(">")) only = Group.ACTIONS;
        else if (q.startsWith("@")) only = Group.ENVIRONMENTS;
        else if (q.startsWith("#")) only = Group.HISTORY;
        if (only != null) q = q.substring(1).strip();
        String query = q.toLowerCase(Locale.ROOT);

        model.clear();
        for (Group g : Group.values()) {
            if (only != null && g != only) continue;
            if (only == null && g == Group.HISTORY && query.isEmpty()) continue;
            if (g == Group.RECENT && !query.isEmpty()) continue;
            List<Match> matches = new ArrayList<>();
            for (Item it : items) {
                if (it.group() != g) continue;
                Match m = score(it, query);
                if (m != null) matches.add(m);
            }
            if (matches.isEmpty()) continue;
            if (!query.isEmpty()) matches.sort(Comparator.comparingInt(Match::score).reversed());
            int limit = query.isEmpty() ? 6 : g == Group.HISTORY ? 8 : 12;
            model.addElement(g.label);
            for (int i = 0; i < Math.min(limit, matches.size()); i++) model.addElement(matches.get(i));
        }
        if (model.isEmpty()) model.addElement("No matches");
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i) instanceof Match) {
                list.setSelectedIndex(i);
                break;
            }
        }
    }

    /** Subsequence fuzzy match; contiguous and word-start hits score higher. */
    static Match score(Item it, String q) {
        String text = it.label();
        boolean[] hits = new boolean[text.length()];
        if (q.isEmpty()) return new Match(it, 0, hits);
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(q);
        if (idx >= 0) {
            for (int i = idx; i < idx + q.length(); i++) hits[i] = true;
            int score = 1000 - idx + (idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1)) ? 200 : 0);
            return new Match(it, score, hits);
        }
        int ti = 0;
        int score = 0;
        int last = -2;
        for (int qi = 0; qi < q.length(); qi++) {
            char c = q.charAt(qi);
            if (c == ' ') continue;
            while (ti < lower.length() && lower.charAt(ti) != c) ti++;
            if (ti >= lower.length()) {
                String d = it.detail() == null ? "" : it.detail().toLowerCase(Locale.ROOT);
                return d.contains(q) ? new Match(it, 10, new boolean[text.length()]) : null;
            }
            hits[ti] = true;
            score += ti == last + 1 ? 15 : 5;
            if (ti == 0 || !Character.isLetterOrDigit(lower.charAt(ti - 1))) score += 10;
            last = ti;
            ti++;
        }
        return new Match(it, score, hits);
    }

    private static String highlight(String text, boolean[] hits) {
        StringBuilder sb = new StringBuilder("<html>");
        boolean on = false;
        for (int i = 0; i < text.length(); i++) {
            boolean h = i < hits.length && hits[i];
            if (h && !on) {
                sb.append("<b>");
                on = true;
            } else if (!h && on) {
                sb.append("</b>");
                on = false;
            }
            sb.append(Ui.escape(String.valueOf(text.charAt(i))));
        }
        if (on) sb.append("</b>");
        return sb.append("</html>").toString();
    }

    private static final class Renderer implements ListCellRenderer<Object> {
        private final JLabel caption = Ui.caption("");
        private final JPanel row = new JPanel(new BorderLayout(10, 0));
        private final JLabel lead = new JLabel();
        private final JLabel label = new JLabel();
        private final JLabel detail = Ui.small("");
        private final JLabel shortcut = Ui.small("");

        Renderer() {
            caption.setBorder(Ui.pad(10, 10, 4, 10));
            row.setBorder(Ui.pad(7, 10, 7, 10));
            lead.setPreferredSize(new Dimension(Theme.scaled(44), Theme.scaled(18)));
            lead.setHorizontalAlignment(JLabel.CENTER);
            JPanel center = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            center.add(label);
            center.add(detail);
            row.add(lead, BorderLayout.WEST);
            row.add(center, BorderLayout.CENTER);
            row.add(shortcut, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
            if (value instanceof String s) {
                caption.setText(s);
                return caption;
            }
            Match m = (Match) value;
            Item it = m.item();
            row.setOpaque(sel);
            row.setBackground(Theme.selectionBg());
            if (it.method() != null) {
                lead.setIcon(null);
                lead.setText(it.method().shortLabel());
                lead.setFont(Theme.mono(11f).deriveFont(Font.BOLD));
                lead.setForeground(Theme.methodColor(it.method()));
            } else {
                lead.setText(null);
                lead.setIcon(it.icon());
            }
            label.setText(highlight(it.label(), m.hits()));
            label.setForeground(Theme.text());
            detail.setText(it.detail() == null ? "" : it.detail());
            shortcut.setText(it.shortcut() != null ? Keys.label(it.shortcut()) : sel && it.runAlt() != null ? (Platform.isMac() ? "↩ Open" : "Enter") : "");
            return row;
        }
    }
}
