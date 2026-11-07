package dev.restclient.ui;

import dev.restclient.model.KeyValue;
import dev.restclient.model.Settings;
import dev.restclient.model.Environment;
import dev.restclient.model.Variable;
import dev.restclient.vars.VariableResolver;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;

/**
 * Every control the app uses, on one screen, in the current theme. For checking the look and feel by eye:
 * {@code java -cp build/restclient.jar dev.restclient.ui.Gallery} (add {@code dark}, {@code sepia} or {@code solarized_dark} for another theme).
 */
public final class Gallery {
    private Gallery() {
    }

    public static void main(String[] args) {
        Settings.ThemeMode mode = Settings.ThemeMode.LIGHT;
        if (args.length > 0) {
            for (Settings.ThemeMode m : Settings.ThemeMode.values()) if (m.name().equalsIgnoreCase(args[0])) mode = m;
        }
        Settings.ThemeMode start = mode;
        SwingUtilities.invokeLater(() -> {
            Theme.apply(start, false);
            JFrame f = build();
            f.setVisible(true);
        });
    }

    public static JFrame build() {
        JFrame f = new JFrame("Component Gallery");
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        f.setJMenuBar(menuBar(f));

        Environment env = new Environment();
        env.name = "Staging";
        env.variables.add(new Variable("baseUrl", "https://staging.example.com"));
        env.variables.add(new Variable("token", "secret", true));
        VariableResolver resolver = new VariableResolver(env, null, null);
        VarSupport.Actions actions = new VarSupport.Actions() {
            @Override
            public void editVariable(String name) {
                JOptionPane.showMessageDialog(f, "Edit " + name);
            }

            @Override
            public void defineVariable(String name) {
                JOptionPane.showMessageDialog(f, "Define " + name);
            }
        };

        JPanel grid = new Ui.WidthTrackingPanel(null);
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
        grid.setBorder(Ui.pad(20, 24, 20, 24));

        grid.add(section("Buttons", Ui.row(8,
                Ui.button("Default", null),
                Ui.accent("Accent", null),
                disabled(Ui.button("Disabled", null)),
                Ui.toolbarButton(Icons.get("gear"), "Toolbar button", null),
                Ui.linkButton("Link button", null))));

        JToggleButton toggle = new JToggleButton("Toggle");
        Ui.Segmented seg = new Ui.Segmented("Pretty", "Raw", "Headers");
        grid.add(section("Toggles", Ui.row(8, toggle, seg, withBadge())));

        JTextField plain = new JTextField(14);
        JTextField search = Ui.searchField(Ui.placeholder(new JTextField(14), "Search"));
        JTextField error = Ui.placeholder(new JTextField(10), "Error outline");
        error.putClientProperty("JComponent.outline", "error");
        JTextField off = new JTextField("Disabled", 8);
        off.setEnabled(false);
        grid.add(section("Text fields", column(Ui.row(8, plain, search), Ui.row(8, error, off))));

        VarTextField url = new VarTextField(() -> resolver, actions, true);
        url.setText("{{baseUrl}}/v2/orders?token={{token}}&tenant={{tenantId}}");
        JPasswordField pw = new JPasswordField("hunter2", 12);
        grid.add(section("Variable field · password", column(url, Ui.row(8, pw))));

        JComboBox<String> combo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE"});
        JComboBox<String> comboOff = new JComboBox<>(new String[]{"Disabled"});
        comboOff.setEnabled(false);
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(30, 0, 3600, 5));
        grid.add(section("Combo box · spinner", Ui.row(8, combo, comboOff, spinner)));

        JCheckBox c1 = new JCheckBox("Checked", true);
        JCheckBox c2 = new JCheckBox("Unchecked");
        JCheckBox c3 = new JCheckBox("Disabled", true);
        c3.setEnabled(false);
        JRadioButton r1 = new JRadioButton("Radio A", true);
        JRadioButton r2 = new JRadioButton("Radio B");
        ButtonGroup g = new ButtonGroup();
        g.add(r1);
        g.add(r2);
        grid.add(section("Check boxes · radios", Ui.row(10, c1, c2, c3, r1, r2)));

        JProgressBar det = new JProgressBar(0, 100);
        det.setValue(62);
        det.setPreferredSize(new Dimension(180, 6));
        JProgressBar ind = new JProgressBar();
        ind.setIndeterminate(true);
        ind.setPreferredSize(new Dimension(180, 6));
        grid.add(section("Progress", Ui.row(12, det, ind)));

        grid.add(section("Pills", Ui.row(6,
                Ui.statusPill(200, "200 OK"), Ui.statusPill(301, "301"), Ui.statusPill(404, "404 Not Found"),
                Ui.statusPill(500, "500"), new Ui.Pill("Active", Theme::okBg, Theme::okFg),
                new Ui.Pill("Warning", Theme::warnBg, Theme::warnFg))));

        KeyValueTable kv = new KeyValueTable(() -> resolver, actions, "Add header", "Value", null, null);
        kv.setRows(List.of(new KeyValue("Accept", "application/json"), new KeyValue("Authorization", "Bearer {{token}}"),
                new KeyValue("X-Tenant", "{{tenantId}}"), new KeyValue("X-Debug", "true", false)));
        kv.setPreferredSize(new Dimension(420, 170));
        grid.add(section("Key/value table", kv));

        DefaultListModel<String> lm = new DefaultListModel<>();
        for (String s : new String[]{"First item", "Second item", "Third item", "Fourth item"}) lm.addElement(s);
        JList<String> list = new JList<>(lm);
        list.setSelectedIndex(1);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode folder = new DefaultMutableTreeNode("Orders");
        folder.add(new DefaultMutableTreeNode("List orders"));
        folder.add(new DefaultMutableTreeNode("Create order"));
        root.add(folder);
        root.add(new DefaultMutableTreeNode("Health"));
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.expandRow(0);
        tree.setSelectionRow(1);
        JPanel lists = new JPanel(new GridLayout(1, 2, 10, 0));
        lists.add(scroll(list));
        lists.add(scroll(tree));
        lists.setPreferredSize(new Dimension(420, 130));
        grid.add(section("List · tree", lists));

        CodeArea code = new CodeArea();
        code.setLanguage(CodeArea.Lang.JSON);
        code.setText("{\n  \"id\": \"ord_8F2K1\",\n  \"total\": 129.50,\n  \"paid\": false,\n  \"tags\": [\"a\", null]\n}");
        JScrollPane cs = CodeArea.scroll(code, true);
        cs.setPreferredSize(new Dimension(420, 150));
        grid.add(section("Code editor (JSON)", cs));

        CodeArea xml = new CodeArea();
        xml.setLanguage(CodeArea.Lang.XML);
        xml.setText("<?xml version=\"1.0\"?>\n<order id=\"8F2K1\">\n  <!-- comment -->\n  <total>129.50</total>\n</order>");
        JScrollPane xs = CodeArea.scroll(xml, true);
        xs.setPreferredSize(new Dimension(420, 150));
        grid.add(section("Code editor (XML)", xs));

        TabStrip strip = new TabStrip();
        strip.addTab("One", new JLabel("  First tab content"));
        strip.addTab("Two", new JLabel("  Second tab content"));
        strip.setTrailingComponent(Ui.toolbarButton(Icons.get("plus", 14), "New tab", null));
        strip.setPreferredSize(new Dimension(420, 110));
        grid.add(section("Document tabs", strip));

        UnderlineTabs ut = new UnderlineTabs();
        ut.addTab("Params 2", new JLabel("  Params"));
        ut.addTab("Headers 4", new JLabel("  Headers"));
        ut.addTab("Body", new JLabel("  Body"));
        ut.setPreferredSize(new Dimension(420, 90));
        grid.add(section("Editor tabs", ut));

        JTextArea area = new JTextArea("A plain text area.\nIt wraps, selects and undoes.", 3, 30);
        area.setLineWrap(true);
        grid.add(section("Text area", scroll(area)));

        JPanel icons = Ui.row(10);
        for (String n : new String[]{"plus", "import", "search", "collection", "folder", "history", "gear", "lock", "lock-open",
                "copy", "download", "more", "eye", "check", "warning", "error", "ok", "info", "globe", "shield", "shield-check",
                "trash", "close", "environment", "database"}) {
            JLabel l = new JLabel(Icons.get(n));
            l.setToolTipText(n);
            icons.add(l);
        }
        grid.add(section("Icons", icons));

        JButton dialogs = Ui.button("Show dialog", e -> Ui.confirm(f, "Delete Request", "Delete “List orders”?", "Delete"));
        JButton theme = Ui.button("Switch theme", e -> {
            // cycles Light → Dark → Solarized Sepia → Solarized Dark
            Settings.ThemeMode[] order = {Settings.ThemeMode.LIGHT, Settings.ThemeMode.DARK, Settings.ThemeMode.SEPIA,
                    Settings.ThemeMode.SOLARIZED_DARK};
            Theme.apply(order[(Theme.palette().ordinal() + 1) % order.length], true);
        });
        grid.add(section("Dialogs · theme", Ui.row(8, dialogs, theme)));

        JScrollPane page = new JScrollPane(grid);
        page.setBorder(BorderFactory.createEmptyBorder());
        page.getVerticalScrollBar().setUnitIncrement(24);
        f.setContentPane(page);
        f.setSize(1100, 900);
        f.setLocationRelativeTo(null);
        return f;
    }

    private static JMenuBar menuBar(JFrame f) {
        JMenuBar mb = new JMenuBar();
        JMenu m = new JMenu("Menu");
        JMenuItem item = new JMenuItem("Item with shortcut");
        item.setAccelerator(Keys.NEW_TAB);
        m.add(item);
        m.add(new JCheckBoxMenuItem("Check item", true));
        m.add(new JRadioButtonMenuItem("Radio item", true));
        JMenu sub = new JMenu("Submenu");
        sub.add(new JMenuItem("Nested"));
        m.add(sub);
        m.addSeparator();
        JMenuItem dis = new JMenuItem("Disabled");
        dis.setEnabled(false);
        m.add(dis);
        mb.add(m);
        return mb;
    }

    private static JComponent withBadge() {
        JButton b = new JButton("TLS verification off", Icons.get("lock-open", 14));
        b.setBackground(Theme.warnBg());
        b.setForeground(Theme.warnFg());
        b.putClientProperty("rc.borderColor", Theme.warnBorder());
        b.addActionListener(e -> {
            JPopupMenu p = new JPopupMenu();
            p.add(new JMenuItem("Popup item"));
            p.show(b, 0, b.getHeight());
        });
        return b;
    }

    private static JButton disabled(JButton b) {
        b.setEnabled(false);
        return b;
    }

    private static JScrollPane scroll(Component c) {
        return new JScrollPane(c);
    }

    private static JPanel column(Component... cs) {
        JPanel p = Ui.transparent(null);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (Component c : cs) {
            if (c instanceof JComponent jc) jc.setAlignmentX(0f);
            p.add(c);
            p.add(Ui.vgap(8));
        }
        return p;
    }

    private static JPanel section(String title, Component content) {
        JPanel p = new JPanel(new BorderLayout(0, 8)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setBorder(Ui.pad(0, 0, 18, 0));
        p.setAlignmentX(0f);
        p.add(Ui.caption(title), BorderLayout.NORTH);
        JPanel holder = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        holder.add(content);
        p.add(content instanceof JScrollPane || content instanceof KeyValueTable || content instanceof TabStrip
                || content instanceof UnderlineTabs || content instanceof VarTextField ? content : holder, BorderLayout.CENTER);
        return p;
    }
}
