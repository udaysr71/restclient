package dev.restclient.ui;

import dev.restclient.http.CertFileStatus;
import dev.restclient.model.Settings;
import dev.restclient.store.AppPaths;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Preferences window with General, Network, Certificates and Data panes. Changes apply immediately. */
public final class SettingsDialog {
    public static final String GENERAL = "General";
    public static final String NETWORK = "Network";
    public static final String CERTIFICATES = "Certificates";
    public static final String DATA = "Data";

    private final MainFrame frame;
    private final AppContext ctx;
    private final JDialog dialog;
    private final CardLayout cards = new CardLayout();
    private final JPanel panes = new JPanel(cards);
    private final DefaultListModel<CertFileStatus> certModel = new DefaultListModel<>();
    private final JList<CertFileStatus> certList = new JList<>(certModel);
    private final JLabel certSummary = Ui.small("");
    private final JButton passwordButton = Ui.button("Password…", e -> setPassword());
    private final JButton removeButton = Ui.button("Remove…", e -> removeCert());

    private SettingsDialog(MainFrame frame, AppContext ctx) {
        this.frame = frame;
        this.ctx = ctx;
        dialog = new JDialog(frame, "Settings", java.awt.Dialog.ModalityType.MODELESS);
        dialog.setName("rc.settings");
        WindowChrome.install(dialog);
        Motion.fadeIn(dialog);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        Ui.closeOnEscape(dialog);
        build();
    }

    public static void open(MainFrame frame, AppContext ctx, String pane) {
        SettingsDialog d = new SettingsDialog(frame, ctx);
        d.show(pane == null ? GENERAL : pane);
        d.dialog.setSize(Theme.scaled(720), Theme.scaled(600));
        d.dialog.setLocationRelativeTo(frame);
        d.dialog.setVisible(true);
    }

    private final java.util.Map<String, JToggleButton> tabButtons = new java.util.HashMap<>();

    private void build() {
        JPanel root = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 6));
        bar.setBorder(Ui.line(0, 0, 1, 0));
        ButtonGroup g = new ButtonGroup();
        String[][] tabs = {{GENERAL, "gear"}, {NETWORK, "network"}, {CERTIFICATES, "shield-check"}, {DATA, "database"}};
        for (String[] t : tabs) {
            JToggleButton b = new JToggleButton(t[0], Icons.get(t[1], 22));
            b.setVerticalTextPosition(SwingConstants.BOTTOM);
            b.setHorizontalTextPosition(SwingConstants.CENTER);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setPreferredSize(new Dimension(Theme.scaled(108), Theme.scaled(58)));
            b.setRequestFocusEnabled(false); // reachable with Tab, but a click doesn't steal focus
            b.addActionListener(e -> {
                // Focus follows the selected tab, so the ring never stays on a tab you've left.
                boolean focusInBar = javax.swing.SwingUtilities.isDescendingFrom(
                        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(), bar);
                show(t[0]);
                if (focusInBar) b.requestFocusInWindow();
            });
            // Left/Right move between tabs, as in any tab list.
            b.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("LEFT"), "rc.prevTab");
            b.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("RIGHT"), "rc.nextTab");
            b.getActionMap().put("rc.prevTab", stepTab(t[0], -1));
            b.getActionMap().put("rc.nextTab", stepTab(t[0], 1));
            g.add(b);
            bar.add(b);
            tabButtons.put(t[0], b);
        }
        root.add(bar, BorderLayout.NORTH);
        panes.add(scroll(general()), GENERAL);
        panes.add(scroll(network()), NETWORK);
        panes.add(scroll(certificates()), CERTIFICATES);
        panes.add(scroll(data()), DATA);
        root.add(panes, BorderLayout.CENTER);
        dialog.setContentPane(root);
    }

    private javax.swing.Action stepTab(String from, int dir) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String[] order = {GENERAL, NETWORK, CERTIFICATES, DATA};
                int i = java.util.Arrays.asList(order).indexOf(from);
                String to = order[(i + dir + order.length) % order.length];
                show(to);
                tabButtons.get(to).requestFocusInWindow();
            }
        };
    }

    private void show(String pane) {
        cards.show(panes, pane);
        JToggleButton b = tabButtons.get(pane);
        if (b != null) b.setSelected(true);
        dialog.setTitle(pane);
        if (CERTIFICATES.equals(pane)) refreshCerts();
    }

    private static JComponent scroll(JPanel p) {
        JPanel wrap = new Ui.WidthTrackingPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    // ---- panes -----------------------------------------------------------------------------

    private JPanel general() {
        Settings s = ctx.settings();
        Form f = new Form();
        f.heading("Appearance");
        javax.swing.JComboBox<?>[] fontBox = new javax.swing.JComboBox<?>[1];
        javax.swing.JComboBox<Themes.Choice> theme = FontChoices.themeCombo(s, c -> frame.setAppearance(c),
                () -> fontBox[0] == null ? 0 : fontBox[0].getPreferredSize().width);
        theme.getAccessibleContext().setAccessibleName("Theme");
        f.row("Theme", theme);
        // problems in the chosen theme file (bad values, typos, low contrast), and the theme-file commands
        JLabel themeIssues = Ui.small("");
        themeIssues.setForeground(Theme.warnFg());
        themeIssues.getAccessibleContext().setAccessibleName("Theme file problems");
        Runnable showIssues = () -> {
            Theme.Palette p = Theme.palette();
            themeIssues.setForeground(Theme.warnFg());
            if (p.file == null || p.warnings.isEmpty()) {
                themeIssues.setText("");
                themeIssues.setVisible(false);
                return;
            }
            StringBuilder b = new StringBuilder("<html><div style='width:420px'><b>")
                    .append(Ui.escape(p.file.getFileName().toString())).append(": ")
                    .append(p.warnings.size()).append(p.warnings.size() == 1 ? " problem" : " problems").append("</b>");
            int shown = 0;
            for (String w : p.warnings) {
                if (shown++ == 5) {
                    b.append("<br>…and ").append(p.warnings.size() - 5).append(" more");
                    break;
                }
                b.append("<br>• ").append(Ui.escape(w));
            }
            themeIssues.setText(b.append("</div></html>").toString());
            themeIssues.setVisible(true);
        };
        showIssues.run();
        f.full(themeIssues);
        // under the picker, in the controls' column: what a custom theme is, and the two things you can do
        JLabel themeHint = Ui.small("Custom themes are .css files; the app updates as you save one.");
        themeHint.setToolTipText(AppPaths.display(Themes.dir()));
        f.under(themeHint);
        f.under(Ui.row(Theme.scaled(14),
                Ui.linkButton("Export Current Theme…", e -> frame.exportTheme()),
                Ui.linkButton("Open Themes Folder", e -> SettingsDialog.reveal(Themes.dir()))));
        Runnable sync = () -> {
            FontChoices.refill(theme);
            showIssues.run();
        };
        Theme.onChange(sync);
        Themes.onChange(sync);
        javax.swing.JComboBox<String> uiFont = FontChoices.combo(FontChoices.uiFamilies(), FontChoices.READABLE_UI,
                s.uiFontFamily, Theme.defaultUiFamily(), fam -> {
                    if (java.util.Objects.equals(fam, s.uiFontFamily)) return;
                    s.uiFontFamily = fam;
                    ctx.repo.saveSettings();
                    frame.applyFonts();
                });
        uiFont.getAccessibleContext().setAccessibleName("Interface font");
        fontBox[0] = uiFont;
        f.row("Interface font", uiFont);
        javax.swing.JComboBox<String> codeFont = FontChoices.combo(FontChoices.codeFamilies(), FontChoices.READABLE_CODE,
                s.codeFontFamily, Theme.defaultMonoFamily(), fam -> {
                    if (java.util.Objects.equals(fam, s.codeFontFamily)) return;
                    s.codeFontFamily = fam;
                    ctx.repo.saveSettings();
                    frame.applyFonts();
                });
        codeFont.getAccessibleContext().setAccessibleName("Code font");
        f.row("Code font", codeFont);
        f.under(Ui.small("Used for URLs, headers and bodies."));
        f.under(Ui.small("Easier-to-read fonts are listed first. Size follows View \u203a Zoom."));
        JCheckBox reduce = new JCheckBox("Reduce motion", s.reduceMotion);
        reduce.addActionListener(e -> {
            s.reduceMotion = reduce.isSelected();
            Motion.setReduced(s.reduceMotion);
            ctx.repo.saveSettings();
        });
        f.row("Motion", reduce);
        f.heading("Requests");
        JSpinner timeout = new JSpinner(new SpinnerNumberModel(s.defaultTimeoutSeconds, 1, 3600, 5));
        timeout.addChangeListener(e -> {
            s.defaultTimeoutSeconds = (Integer) timeout.getValue();
            ctx.repo.saveSettings();
            frame.settingsChanged();
        });
        f.row("Default timeout (seconds)", timeout);
        f.heading("Window");
        JCheckBox restore = new JCheckBox("Restore open tabs and unsaved drafts on launch", s.restoreTabs);
        restore.addActionListener(e -> {
            s.restoreTabs = restore.isSelected();
            ctx.repo.saveSettings();
        });
        f.full(restore);
        return f.panel;
    }

    private JPanel network() {
        Settings s = ctx.settings();
        Form f = new Form();
        f.heading("Connections");
        JCheckBox redirects = new JCheckBox("Follow redirects", s.followRedirects);
        redirects.addActionListener(e -> {
            s.followRedirects = redirects.isSelected();
            saveNetwork();
        });
        f.full(redirects);
        JCheckBox proxy = new JCheckBox("Use the system proxy settings", s.useSystemProxy);
        proxy.addActionListener(e -> {
            s.useSystemProxy = proxy.isSelected();
            saveNetwork();
        });
        f.full(proxy);
        f.note("Proxies are read from the standard Java properties (https.proxyHost, http.nonProxyHosts…).");
        JSpinner connect = new JSpinner(new SpinnerNumberModel(s.connectTimeoutSeconds, 1, 300, 1));
        connect.addChangeListener(e -> {
            s.connectTimeoutSeconds = (Integer) connect.getValue();
            saveNetwork();
        });
        f.row("Connect timeout (seconds)", connect);
        return f.panel;
    }

    private void saveNetwork() {
        ctx.repo.saveSettings();
        ctx.http.setOptions(ctx.httpOptions());
    }

    private JPanel certificates() {
        Settings s = ctx.settings();
        Form f = new Form();
        JPanel head = Ui.transparent(new BorderLayout(10, 0));
        JPanel texts = Ui.transparent(new BorderLayout(0, 2));
        texts.add(Ui.heading("Trusted certificates", 13f), BorderLayout.NORTH);
        texts.add(Ui.secondary("<html><div style='width:285px'>Loaded from <b>" + Ui.escape(AppPaths.display(ctx.tls.certsDir()))
                + "</b> and combined with the system trust store.</div></html>"), BorderLayout.SOUTH);
        head.add(texts, BorderLayout.CENTER);
        JPanel hb = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        hb.add(Ui.button(Platform.isMac() ? "Reveal in Finder" : "Open Folder", e -> reveal(ctx.tls.certsDir())));
        hb.add(Ui.button("Reload", e -> {
            String err = ctx.reloadTls();
            if (err != null) Ui.error(dialog, "Couldn't reload", err);
            refreshCerts();
            frame.status("Certificates reloaded", null, null);
        }));
        head.add(hb, BorderLayout.EAST);
        f.full(head);

        certList.setCellRenderer(new CertRenderer());
        certList.setVisibleRowCount(6);
        certList.addListSelectionListener(e -> updateCertButtons());
        JScrollPane sp = new JScrollPane(certList);
        sp.setPreferredSize(new Dimension(Theme.scaled(420), Theme.scaled(220)));
        sp.setMinimumSize(new Dimension(200, 160));
        sp.setBorder(Ui.line(1, 1, 1, 1));
        // An empty list says what to do instead of being a blank box.
        JPanel emptyCerts = new JPanel(new java.awt.GridBagLayout());
        emptyCerts.setBorder(Ui.line(1, 1, 1, 1));
        emptyCerts.setPreferredSize(sp.getPreferredSize());
        JPanel emptyText = Ui.transparent(new BorderLayout(0, 4));
        JLabel et = Ui.heading("No certificates yet", 13f);
        et.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        JLabel es = Ui.secondary("Put .pem, .crt or .p12 files in the folder, then click Reload.");
        es.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        emptyText.add(et, BorderLayout.NORTH);
        emptyText.add(es, BorderLayout.CENTER);
        emptyCerts.add(emptyText);
        certCards.add(sp, "list");
        certCards.add(emptyCerts, "empty");
        f.full(certCards);
        JPanel under = Ui.transparent(new BorderLayout());
        under.add(certSummary, BorderLayout.WEST);
        under.add(Ui.row(6, passwordButton, removeButton), BorderLayout.EAST);
        f.full(under);
        f.note("Supported: .pem .crt .cer .der certificates, and .p12 .pfx .jks stores. Stores with a private key are used "
                + "as client certificates (mTLS).");

        f.heading("Verification");
        JCheckBox skip = new JCheckBox("Skip TLS verification for new requests", s.insecureByDefault);
        skip.addActionListener(e -> {
            if (skip.isSelected() && !Ui.confirm(dialog, "Skip TLS verification?",
                    "Certificate and hostname checks will be off for every request that doesn't override it.\n"
                            + "Anyone on the network could read or change that traffic.", "Turn Off Verification")) {
                skip.setSelected(false);
                return;
            }
            s.insecureByDefault = skip.isSelected();
            ctx.repo.saveSettings();
            frame.settingsChanged();
        });
        f.full(skip);
        f.note("Turns off certificate and hostname checks by default. Each request can still override this in its Settings tab.");
        JCheckBox badge = new JCheckBox("Show a warning badge next to Send when verification is off", s.showInsecureBadge);
        badge.addActionListener(e -> {
            s.showInsecureBadge = badge.isSelected();
            ctx.repo.saveSettings();
            frame.settingsChanged();
        });
        f.full(badge);
        return f.panel;
    }

    private JPanel data() {
        Settings s = ctx.settings();
        Form f = new Form();
        f.heading("Storage");
        JPanel row = Ui.transparent(new BorderLayout(10, 0));
        JLabel path = new JLabel(AppPaths.display(ctx.repo.paths().base()));
        path.setFont(Theme.mono(12.5f));
        row.add(path, BorderLayout.CENTER);
        row.add(Ui.button(Platform.isMac() ? "Reveal in Finder" : "Open Folder", e -> reveal(ctx.repo.paths().base())), BorderLayout.EAST);
        f.row("Data folder", row);
        f.note("Collections, environments, history and settings are plain JSON files you can back up or put in version control.");
        f.heading("History");
        JSpinner limit = new JSpinner(new SpinnerNumberModel(s.historyLimit, 10, 5000, 50));
        limit.addChangeListener(e -> {
            s.historyLimit = (Integer) limit.getValue();
            ctx.repo.saveSettings();
            frame.historyChanged();
        });
        f.row("Keep the most recent", limit);
        f.full(Ui.row(0, Ui.button("Clear History…", e -> frame.clearHistory())));
        return f.panel;
    }

    // ---- certificates ----------------------------------------------------------------------

    private final java.awt.CardLayout certCardLayout = new java.awt.CardLayout();
    private final JPanel certCards = new JPanel(certCardLayout);

    private void refreshCerts() {
        certModel.clear();
        for (CertFileStatus c : ctx.tls.status()) certModel.addElement(c);
        certCardLayout.show(certCards, certModel.isEmpty() ? "empty" : "list");
        long loaded = ctx.tls.status().stream().filter(c -> c.status() == CertFileStatus.Status.LOADED).count();
        int files = ctx.tls.status().size();
        certSummary.setText(files == 0 ? "Using the system trust store only."
                : loaded + " of " + files + " files loaded · " + ctx.tls.addedTrustedCount()
                + " certificates added on top of " + ctx.tls.systemTrustedCount() + " system CAs.");
        updateCertButtons();
    }

    private void updateCertButtons() {
        CertFileStatus c = certList.getSelectedValue();
        passwordButton.setEnabled(c != null && c.isKeystore());
        removeButton.setEnabled(c != null);
    }

    private void setPassword() {
        CertFileStatus c = certList.getSelectedValue();
        if (c == null) return;
        JPasswordField pf = new JPasswordField(20);
        String existing = ctx.settings().keystorePasswords.get(c.fileName());
        if (existing != null) pf.setText(existing);
        Object r = Ui.showPane(dialog, new JOptionPane(new Object[]{"Password for " + c.fileName(), pf},
                JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION), "Keystore Password");
        if (!(r instanceof Integer i) || i != JOptionPane.OK_OPTION) return;
        String pw = new String(pf.getPassword());
        if (pw.isEmpty()) ctx.settings().keystorePasswords.remove(c.fileName());
        else ctx.settings().keystorePasswords.put(c.fileName(), pw);
        ctx.repo.saveSettings();
        ctx.reloadTls();
        refreshCerts();
    }

    private void removeCert() {
        CertFileStatus c = certList.getSelectedValue();
        if (c == null) return;
        Path p = ctx.tls.certsDir().resolve(c.fileName());
        boolean trash = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        if (!Ui.confirm(dialog, "Remove Certificate", "Remove “" + c.fileName() + "” from the certs folder?"
                + (trash ? " It will be moved to the Trash." : " The file will be deleted."), "Remove")) return;
        try {
            if (trash) Desktop.getDesktop().moveToTrash(p.toFile());
            else Files.deleteIfExists(p);
        } catch (Exception e) {
            Ui.error(dialog, "Couldn't remove", e.getMessage());
        }
        ctx.settings().keystorePasswords.remove(c.fileName());
        ctx.repo.saveSettings();
        ctx.reloadTls();
        refreshCerts();
    }

    static void reveal(Path dir) {
        try {
            File f = dir.toFile();
            if (!f.exists()) Files.createDirectories(dir);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
            }
        } catch (Exception ignored) {
            // nothing we can do
        }
    }

    private static final class CertRenderer implements ListCellRenderer<CertFileStatus> {
        private final JPanel row = new JPanel(new BorderLayout(10, 0));
        private final JLabel icon = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel detail = Ui.small("");

        CertRenderer() {
            row.setBorder(Ui.pad(7, 10, 7, 10));
            JPanel t = Ui.transparent(new BorderLayout(0, 1));
            t.add(name, BorderLayout.NORTH);
            t.add(detail, BorderLayout.SOUTH);
            row.add(icon, BorderLayout.WEST);
            row.add(t, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CertFileStatus> list, CertFileStatus c, int index,
                                                      boolean sel, boolean focus) {
            row.setBackground(sel ? Theme.selectionBg() : c.status() == CertFileStatus.Status.FAILED ? Theme.errBg() : list.getBackground());
            row.setOpaque(true);
            icon.setIcon(Icons.get(switch (c.status()) {
                case LOADED -> "ok";
                case FAILED -> "error";
                case IGNORED -> "info";
            }));
            name.setText(c.fileName());
            name.setFont(Theme.mono(12.5f));
            name.setForeground(Theme.text());
            detail.setText(c.detail());
            detail.setForeground(c.status() == CertFileStatus.Status.FAILED ? Theme.errFg() : Theme.secondary());
            return row;
        }
    }

    /** Simple label/field form with section headings. */
    private static final class Form {
        final JPanel panel = new JPanel(new GridBagLayout());
        int y;

        Form() {
            panel.setBorder(Ui.pad(18, 26, 22, 26));
        }

        void heading(String text) {
            JLabel l = Ui.heading(text, 13f);
            GridBagConstraints gc = base();
            gc.gridwidth = 2;
            gc.insets = new Insets(y == 0 ? 0 : 20, 0, 8, 0);
            panel.add(l, gc);
        }

        void row(String label, Component field) {
            GridBagConstraints gc = base();
            gc.insets = new Insets(4, 0, 4, 14);
            // Same label column width on every pane, so controls line up when you switch tabs.
            JLabel l = new JLabel(label) {
                @Override
                public Dimension getPreferredSize() {
                    Dimension d = super.getPreferredSize();
                    return new Dimension(Math.max(d.width, Theme.scaled(176)), d.height);
                }
            };
            if (field instanceof JComponent jc) l.setLabelFor(jc);
            panel.add(l, gc);
            gc.gridx = 1;
            gc.weightx = 1;
            gc.insets = new Insets(4, 0, 4, 0);
            panel.add(field, gc);
        }

        /** A hint or links under the control above, in the controls' column. */
        void under(Component c) {
            GridBagConstraints gc = base();
            gc.gridx = 1;
            gc.weightx = 1;
            gc.insets = new Insets(0, 0, 6, 0);
            panel.add(c, gc);
        }

        void full(Component c) {
            GridBagConstraints gc = base();
            gc.gridwidth = 2;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.insets = new Insets(4, 0, 4, 0);
            panel.add(c, gc);
        }

        void note(String text) {
            JLabel l = Ui.small("<html><div style='width:420px'>" + Ui.escape(text) + "</div></html>");
            GridBagConstraints gc = base();
            gc.gridwidth = 2;
            gc.insets = new Insets(0, 0, 6, 0);
            panel.add(l, gc);
        }

        private GridBagConstraints base() {
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = y++;
            gc.anchor = GridBagConstraints.WEST;
            return gc;
        }
    }
}
