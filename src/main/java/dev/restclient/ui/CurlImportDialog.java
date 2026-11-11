package dev.restclient.ui;

import dev.restclient.curl.CurlParseException;
import dev.restclient.curl.CurlParser;
import dev.restclient.model.BodyType;
import dev.restclient.model.Environment;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;

/** Paste a curl command, see it parsed live, then import into a new or the current tab. */
public final class CurlImportDialog {
    private final MainFrame frame;
    private final AppContext ctx;
    private final RequestTab currentTab;
    private final JDialog dialog;
    private final JTextArea input = new JTextArea(14, 50);
    private final JPanel preview = Ui.transparent(null);
    private final JPanel chips = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 6));
    private final JLabel error = new JLabel();
    private final JCheckBox moveToken = new JCheckBox();
    private final JComboBox<String> destination = new JComboBox<>();
    private final Ui.AccentButton importButton = Ui.accent("Import", e -> doImport());
    private final Timer parseTimer;
    private CurlParser.Result result;
    private Object errorTag;

    private CurlImportDialog(MainFrame frame, AppContext ctx, RequestTab currentTab, String text) {
        this.frame = frame;
        this.ctx = ctx;
        this.currentTab = currentTab;
        dialog = Ui.sheet(frame, "Import cURL");
        parseTimer = new Timer(200, e -> parse());
        parseTimer.setRepeats(false);
        build();
        if (text == null) {
            try {
                Object clip = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                if (clip instanceof String s && CurlParser.looksLikeCurl(s)) text = s;
            } catch (Exception ignored) {
                // nothing useful on the clipboard
            }
        }
        if (text != null) input.setText(text.strip());
        parse();
    }

    public static void open(MainFrame frame, AppContext ctx, RequestTab currentTab, String text) {
        CurlImportDialog d = new CurlImportDialog(frame, ctx, currentTab, text);
        d.dialog.pack();
        // A comfortable fixed size: long previews scroll inside instead of stretching the sheet past the window.
        int w = Math.min(Theme.scaled(940), Math.max(Theme.scaled(640), frame.getWidth() - 80));
        int h = Math.min(Math.max(Theme.scaled(560), d.dialog.getHeight()), Math.max(Theme.scaled(420), frame.getHeight() - 80));
        d.dialog.setSize(w, h);
        d.dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(() -> d.input.requestFocusInWindow());
        d.dialog.setVisible(true);
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout());
        JPanel top = Ui.transparent(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createCompoundBorder(Ui.line(0, 0, 1, 0), Ui.pad(16, 20, 12, 20)));
        top.add(Ui.heading("Import cURL", 15f), BorderLayout.NORTH);
        top.add(Ui.secondary("Paste a command. It's parsed as you type."), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        JPanel body = Ui.transparent(new GridLayout(1, 2, 20, 0));
        body.setBorder(Ui.pad(16, 20, 16, 20));

        JPanel left = Ui.transparent(new BorderLayout(0, 8));
        JLabel cmdLabel = Ui.caption("Command");
        cmdLabel.setLabelFor(input);
        left.add(cmdLabel, BorderLayout.NORTH);
        input.setFont(Theme.mono(12.5f));
        input.setLineWrap(true);
        input.setWrapStyleWord(false);
        input.setBorder(Ui.pad(10, 10, 10, 10));
        VarSupport.undoable(input);
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                parseTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                parseTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        JScrollPane sp = new JScrollPane(input);
        left.add(sp, BorderLayout.CENTER);
        JPanel under = Ui.transparent(new BorderLayout());
        under.add(chips, BorderLayout.CENTER);
        under.add(error, BorderLayout.SOUTH);
        left.add(under, BorderLayout.SOUTH);
        body.add(left);

        JPanel right = Ui.transparent(new BorderLayout(0, 8));
        right.add(Ui.caption("Preview"), BorderLayout.NORTH);
        preview.setLayout(new BoxLayout(preview, BoxLayout.Y_AXIS));
        JScrollPane ps = new JScrollPane(preview);
        ps.setBorder(Ui.line(1, 1, 1, 1));
        right.add(ps, BorderLayout.CENTER);
        moveToken.setVisible(false);
        right.add(moveToken, BorderLayout.SOUTH);
        body.add(right);
        root.add(body, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(1, 0, 0, 0), Ui.pad(10, 20, 10, 20)));
        JPanel dest = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel dl = new JLabel("Open in");
        dl.setLabelFor(destination);
        destination.addItem("New tab");
        if (currentTab != null) destination.addItem("Current tab (replace “" + currentTab.title() + "”)");
        dest.add(dl);
        dest.add(destination);
        footer.add(dest, BorderLayout.WEST);
        footer.add(Ui.buttonBar(importButton, Ui.button("Cancel", e -> dialog.dispose())), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(importButton);
    }

    private void parse() {
        if (errorTag != null) {
            input.getHighlighter().removeHighlight(errorTag);
            errorTag = null;
        }
        chips.removeAll();
        preview.removeAll();
        error.setText(" ");
        String text = input.getText();
        if (text.isBlank()) {
            result = null;
            importButton.setEnabled(false);
            preview.add(pad(Ui.secondary("The parsed request will appear here.")));
            refresh();
            return;
        }
        try {
            result = CurlParser.parse(text);
            importButton.setEnabled(true);
            showPreview(result);
        } catch (CurlParseException e) {
            result = null;
            importButton.setEnabled(false);
            error.setText("<html><span style='color:" + hex(Theme.errFg()) + "'>" + Ui.escape(e.describe()) + "</span></html>");
            error.setIcon(Icons.get("error", 14));
            try {
                int start = Math.min(e.offset(), Math.max(0, text.length() - 1));
                errorTag = input.getHighlighter().addHighlight(start, Math.min(text.length(), start + 1),
                        new DefaultHighlighter.DefaultHighlightPainter(Theme.errBg()));
            } catch (BadLocationException ignored) {
                // ignore
            }
            preview.add(pad(Ui.secondary("Fix the problem to see a preview.")));
        }
        refresh();
    }

    private void refresh() {
        chips.revalidate();
        chips.repaint();
        preview.revalidate();
        preview.repaint();
    }

    private void showPreview(CurlParser.Result r) {
        RequestDef q = r.request();
        if (r.insecure()) {
            Ui.Pill p = new Ui.Pill("-k → TLS verification off for this request", Theme::warnBg, Theme::warnFg);
            p.setIcon(Icons.get("lock-open", 13));
            chips.add(p);
        }
        for (String ig : r.ignored()) {
            Ui.Pill p = new Ui.Pill("Ignored: " + ig, Theme::subtleBg, Theme::secondary);
            p.setFont(p.getFont().deriveFont(Font.PLAIN));
            chips.add(p);
        }
        for (String w : r.warnings()) {
            Ui.Pill p = new Ui.Pill(w, Theme::warnBg, Theme::warnFg);
            p.setFont(p.getFont().deriveFont(Font.PLAIN));
            chips.add(p);
        }

        JPanel line = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel m = new JLabel(q.method.name());
        m.setFont(Theme.mono(12.5f).deriveFont(Font.BOLD));
        m.setForeground(Theme.methodColor(q.method));
        JLabel u = new JLabel(q.url);
        u.setFont(Theme.mono(12.5f));
        line.add(m);
        line.add(u);
        preview.add(section(null, line));

        if (!q.headers.isEmpty()) {
            JPanel hs = Ui.transparent(null);
            hs.setLayout(new BoxLayout(hs, BoxLayout.Y_AXIS));
            for (KeyValue kv : q.headers) hs.add(mono(kv.key + ": " + kv.value));
            preview.add(section("Headers · " + q.headers.size(), hs));
        }
        if (q.auth.type != dev.restclient.model.Auth.Type.NONE) {
            preview.add(section("Auth", mono(q.auth.type == dev.restclient.model.Auth.Type.BASIC
                    ? "Basic · " + q.auth.username : "Bearer token")));
        }
        if (q.bodyType == BodyType.FORM) {
            JPanel fs = Ui.transparent(null);
            fs.setLayout(new BoxLayout(fs, BoxLayout.Y_AXIS));
            for (KeyValue kv : q.form) fs.add(mono(kv.key + " = " + kv.value));
            preview.add(section("Body · form", fs));
        } else if (q.bodyType == BodyType.MULTIPART) {
            JPanel fs = Ui.transparent(null);
            fs.setLayout(new BoxLayout(fs, BoxLayout.Y_AXIS));
            for (KeyValue kv : q.multipart) fs.add(mono(kv.key + " = " + (kv.file ? "file " : "") + kv.value));
            preview.add(section("Body · multipart", fs));
        } else if (q.bodyType == BodyType.BINARY) {
            preview.add(section("Body · file", mono(q.bodyFile)));
        } else if (q.bodyType != BodyType.NONE) {
            String b = q.body.length() > 600 ? q.body.substring(0, 600) + "…" : q.body;
            String pretty = q.bodyType == BodyType.JSON ? Formatters.prettyJson(b) : null;
            JTextArea area = new JTextArea(pretty != null ? pretty : b);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setFont(Theme.mono(12f));
            area.setOpaque(false);
            preview.add(section("Body · " + q.bodyType.label, area));
        }

        String auth = q.header("Authorization");
        if (auth != null && auth.startsWith("Bearer ") && !auth.contains("{{")) {
            Environment env = ctx.activeEnvironment();
            moveToken.setText("Move the bearer token into {{token}} in " + (env != null ? env.name : "Globals"));
            moveToken.setSelected(true);
            moveToken.setVisible(true);
        } else {
            moveToken.setVisible(false);
        }
    }

    private static JPanel section(String title, java.awt.Component content) {
        JPanel p = Ui.transparent(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createCompoundBorder(Ui.line(0, 0, 1, 0), Ui.pad(8, 12, 8, 12)));
        if (title != null) p.add(Ui.caption(title), BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        p.setAlignmentX(0f);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + 400));
        return p;
    }

    private static JPanel pad(java.awt.Component c) {
        JPanel p = Ui.transparent(new BorderLayout());
        p.setBorder(Ui.pad(12, 12, 12, 12));
        p.add(c);
        p.setAlignmentX(0f);
        return p;
    }

    private static JLabel mono(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.mono(12f));
        return l;
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void doImport() {
        if (result == null) return;
        RequestDef r = result.request().copy();
        if (moveToken.isVisible() && moveToken.isSelected()) {
            String auth = r.header("Authorization");
            String token = auth.substring("Bearer ".length()).trim();
            Environment env = ctx.activeEnvironment();
            Environment target = env != null ? env : ctx.repo.globals();
            String name = "token";
            Variable existing = null;
            for (Variable v : target.variables) if (v.key.equals(name)) existing = v;
            if (existing != null && !existing.value.equals(token)) {
                int i = 2;
                while (true) {
                    String n = "token" + i;
                    boolean taken = target.variables.stream().anyMatch(v -> v.key.equals(n));
                    if (!taken) {
                        name = n;
                        break;
                    }
                    i++;
                }
                existing = null;
            }
            if (existing == null) target.variables.add(new Variable(name, token, true));
            ctx.repo.saveEnvironment(target);
            for (KeyValue kv : r.headers) {
                if (kv.key.equalsIgnoreCase("Authorization")) kv.value = "Bearer {{" + name + "}}";
            }
            frame.variablesChanged();
        }
        boolean replace = destination.getSelectedIndex() == 1 && currentTab != null;
        dialog.dispose();
        if (replace) currentTab.applyImported(r, result.insecure());
        else frame.openImported(r, result.insecure());
        frame.status("Imported cURL command", null, null);
    }
}
