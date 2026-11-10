package dev.restclient.ui;

import dev.restclient.http.ResponseData;
import dev.restclient.model.HistoryEntry;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/** Every sent request, newest first, grouped by day. */
public class HistoryPanel extends JPanel {
    private final AppContext ctx;
    private final MainFrame frame;
    private final JTextField filter = Ui.searchField(Ui.placeholder(new JTextField(), "Filter by URL, method or status"));
    private final Ui.Segmented chips = new Ui.Segmented("All", "2xx", "4xx", "5xx", "Errors");
    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(model) {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    };
    private final JLabel count = Ui.small("");
    private final JPanel empty = Ui.transparent(new GridBagLayout());
    private final JPanel center = Ui.transparent(new java.awt.CardLayout());

    public HistoryPanel(AppContext ctx, MainFrame frame) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        this.frame = frame;
        setOpaque(false);

        JPanel top = Ui.transparent(new BorderLayout(0, 8));
        top.add(filter, BorderLayout.NORTH);
        top.add(chips, BorderLayout.SOUTH);
        chips.compact();
        add(top, BorderLayout.NORTH);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        chips.onChange(i -> rebuild());

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setOpaque(false);
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Object v = list.getSelectedValue();
            if (v instanceof String) {
                int i = list.getSelectedIndex();
                if (i + 1 < model.size()) list.setSelectedIndex(i + 1);
                else list.clearSelection();
            }
        });
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("shift F10"), "rc.menu");
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_CONTEXT_MENU, 0), "rc.menu");
        list.getActionMap().put("rc.menu", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                keyboardMenu();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i < 0 || !list.getCellBounds(i, i).contains(e.getPoint())) return;
                if (!(model.get(i) instanceof HistoryEntry h) || e.getButton() != MouseEvent.BUTTON1) return;
                if (e.getClickCount() >= 2) frame.openFromHistory(h);
                else frame.showHistoryEntry(h);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "rc-open");
        list.getActionMap().put("rc-open", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (list.getSelectedValue() instanceof HistoryEntry h) frame.showHistoryEntry(h);
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "rc-del");
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "rc-del");
        list.getActionMap().put("rc-del", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (list.getSelectedValue() instanceof HistoryEntry h) ctx.repo.removeHistory(h);
            }
        });

        JScrollPane sp = new JScrollPane(list);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        JPanel emptyBox = Ui.transparent(new BorderLayout(0, 6));
        JLabel et = new JLabel("No history yet", Icons.get("history", 28), JLabel.CENTER);
        et.setHorizontalTextPosition(JLabel.CENTER);
        et.setVerticalTextPosition(JLabel.BOTTOM);
        et.setFont(et.getFont().deriveFont(Font.BOLD));
        emptyBox.add(et, BorderLayout.NORTH);
        emptyBox.add(Ui.secondary("<html><div style='text-align:center;width:128px'>Every request you send shows up here.</div></html>"), BorderLayout.CENTER);
        empty.add(emptyBox);
        center.add(sp, "list");
        center.add(empty, "empty");
        add(center, BorderLayout.CENTER);

        JPanel footer = Ui.transparent(new BorderLayout(8, 0));
        footer.add(count, BorderLayout.CENTER);
        JButton clear = Ui.button("Clear History…", e -> frame.clearHistory());
        footer.add(clear, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        rebuild();
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int i = list.locationToIndex(e.getPoint());
        if (i < 0 || !(model.get(i) instanceof HistoryEntry)) return;
        list.setSelectedIndex(i);
        showMenu(i, e.getX(), e.getY());
    }

    /** Context menu from the keyboard (Shift+F10 or the Menu key), under the selected entry. */
    private void keyboardMenu() {
        int i = list.getSelectedIndex();
        if (i < 0 || !(model.get(i) instanceof HistoryEntry)) return;
        java.awt.Rectangle r = list.getCellBounds(i, i);
        if (r != null) showMenu(i, r.x + 16, r.y + r.height);
    }

    private void showMenu(int i, int x, int y) {
        HistoryEntry h = (HistoryEntry) model.get(i);
        JPopupMenu m = new JPopupMenu();
        JMenuItem open = new JMenuItem("Open in New Tab");
        open.addActionListener(a -> frame.openFromHistory(h));
        JMenuItem save = new JMenuItem("Save to Collection…");
        save.addActionListener(a -> frame.saveHistoryToCollection(h));
        JMenuItem curl = new JMenuItem("Copy as cURL");
        curl.addActionListener(a -> frame.copyHistoryAsCurl(h));
        JMenuItem del = new JMenuItem("Delete Entry");
        del.addActionListener(a -> {
            ctx.repo.removeHistory(h);
            frame.status("Deleted history entry", "Undo", () -> ctx.repo.restoreHistory(java.util.List.of(h)));
        });
        m.add(open);
        m.add(save);
        m.add(curl);
        m.addSeparator();
        m.add(del);
        m.show(list, x, y);
    }

    public void rebuild() {
        Object sel = list.getSelectedValue();
        model.clear();
        String q = filter.getText().trim().toLowerCase(Locale.ROOT);
        int chip = chips.selectedIndex();
        String lastDay = null;
        int shown = 0;
        for (HistoryEntry h : ctx.repo.history()) {
            if (!matches(h, q, chip)) continue;
            String day = dayLabel(h.timestamp);
            if (!day.equals(lastDay)) {
                model.addElement(day);
                lastDay = day;
            }
            model.addElement(h);
            shown++;
        }
        int total = ctx.repo.history().size();
        count.setText(total + " of " + ctx.settings().historyLimit + " kept" + (shown != total ? " · " + shown + " shown" : ""));
        ((java.awt.CardLayout) center.getLayout()).show(center, total == 0 ? "empty" : "list");
        if (sel != null) list.setSelectedValue(sel, false);
    }

    private static boolean matches(HistoryEntry h, String q, int chip) {
        int s = h.status;
        switch (chip) {
            case 1 -> {
                if (s < 200 || s >= 300) return false;
            }
            case 2 -> {
                if (s < 400 || s >= 500) return false;
            }
            case 3 -> {
                if (s < 500) return false;
            }
            case 4 -> {
                if (s != 0) return false;
            }
            default -> {
            }
        }
        if (q.isEmpty()) return true;
        String hay = ((h.sentMethod == null ? "" : h.sentMethod.name()) + " " + h.sentUrl + " " + s + " "
                + (h.environmentName == null ? "" : h.environmentName) + " " + (h.sourceName == null ? "" : h.sourceName))
                .toLowerCase(Locale.ROOT);
        for (String part : q.split("\\s+")) if (!hay.contains(part)) return false;
        return true;
    }

    static String dayLabel(long ts) {
        LocalDate d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        if (d.isAfter(today.minusDays(7))) return d.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH));
        if (d.getYear() == today.getYear()) return d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH));
        return d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
    }

    /** "api.example.com:8443" from a sent URL; "" if there isn't one. */
    static String host(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?([^/?#]+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private static String path(String url) {
        if (url == null) return "";
        String p = url.replaceFirst("^[a-zA-Z]+://[^/?#]+", "");
        return p.isEmpty() ? "/" : p;
    }

    private static final class Renderer implements ListCellRenderer<Object> {
        private final JLabel header = Ui.caption("");
        private final JPanel row = new JPanel(new BorderLayout(8, 0));
        private final JLabel method = new JLabel();
        private final JLabel pathLabel = new JLabel();
        private final JLabel metaLabel = Ui.small("");
        private final Ui.Pill badge = new Ui.Pill("", Theme::okBg, Theme::okFg);

        Renderer() {
            header.setBorder(Ui.pad(10, 6, 4, 6));
            row.setBorder(Ui.pad(5, 6, 5, 6));
            method.setPreferredSize(new java.awt.Dimension(Theme.scaled(40), Theme.scaled(16)));
            method.setVerticalAlignment(JLabel.TOP);
            JPanel texts = Ui.transparent(new BorderLayout(0, 1));
            texts.add(pathLabel, BorderLayout.NORTH);
            texts.add(metaLabel, BorderLayout.SOUTH);
            // URLs come from the user and from imports: always plain text
            metaLabel.putClientProperty("html.disable", Boolean.TRUE);
            pathLabel.putClientProperty("html.disable", Boolean.TRUE);
            row.add(method, BorderLayout.WEST);
            row.add(texts, BorderLayout.CENTER);
            JPanel b = Ui.transparent(new GridBagLayout());
            b.add(badge);
            row.add(b, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            if (value instanceof String s) {
                header.setText(s);
                return header;
            }
            HistoryEntry h = (HistoryEntry) value;
            row.setOpaque(selected);
            row.setBackground(Theme.selectionBg());
            method.setText(h.sentMethod == null ? "" : h.sentMethod.shortLabel());
            method.setFont(Theme.mono(11f).deriveFont(Font.BOLD));
            method.setForeground(Theme.methodColor(h.sentMethod));
            pathLabel.setText(path(h.sentUrl));
            pathLabel.setFont(Theme.mono(12f));
            pathLabel.setForeground(Theme.text());
            String env = h.environmentName == null ? "No environment" : h.environmentName;
            String host = host(h.sentUrl);
            // host first (it tells two servers with the same path apart), then when, then which environment;
            // if the sidebar is narrow, the label shortens from the end
            metaLabel.setText((host.isEmpty() ? "" : host + " · ") + new SimpleDateFormat("HH:mm").format(new Date(h.timestamp)) + " · " + env);
            if (h.status > 0) {
                int s = h.status;
                badge.setText(String.valueOf(s));
                badge.setColors(() -> Theme.statusColors(s)[0], () -> Theme.statusColors(s)[1]);
            } else {
                boolean tls = h.errorKind != null && h.errorKind.startsWith("TLS");
                badge.setText(tls ? "TLS" : "ERR");
                badge.setColors(Theme::errBg, Theme::errFg);
            }
            row.setToolTipText(h.sentUrl + (h.status > 0 ? " — " + h.status + " " + ResponseData.reasonPhrase(h.status) : ""));
            return row;
        }
    }
}
