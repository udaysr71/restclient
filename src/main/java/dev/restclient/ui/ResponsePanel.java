package dev.restclient.ui;

import dev.restclient.http.ResponseData;
import dev.restclient.http.TlsManager;
import dev.restclient.model.KeyValue;
import dev.restclient.store.AppPaths;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Shows the response: status, timing, pretty/raw body, headers, or a helpful error. */
public class ResponsePanel extends JPanel {
    /** What the error screen's buttons do. */
    public interface ErrorActions {
        void trust(ResponseData r);

        void retryInsecure();

        void retry();
    }

    private static final int MAX_DISPLAY = 5 * 1024 * 1024;
    private static final int MAX_PRETTY = 2 * 1024 * 1024;

    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel header = Ui.transparent(new BorderLayout(12, 0));
    /** Status, time and size. Kept at its natural height so it centres on the "Response" title. */
    private final JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)) {
        {
            setOpaque(false);
        }

        @Override
        public java.awt.Dimension getMaximumSize() {
            return getPreferredSize();
        }
    };
    private final JPanel tools = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    private final Ui.Segmented views = new Ui.Segmented("Pretty", "Raw", "Headers");
    private final CodeArea body = new CodeArea();
    private final HeadersView headersView = new HeadersView();
    private javax.swing.JButton copyButton;
    private final JPanel warnings = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JPanel findBar = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JTextField findField = Ui.searchField(new JTextField(22));
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JLabel findStatus = Ui.small("");
    private final JLabel loadingLabel = Ui.secondary("Waiting for response");
    private final JButton loadingCancel = Ui.button("Cancel", null);
    private final JPanel errorHolder = new JPanel(new BorderLayout());
    private final JLabel binaryLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel binaryInfo = Ui.secondary("");
    private final JLabel emptyLabel = Ui.secondary("Send a request to see the response here.");

    private Timer elapsedTimer;
    private Runnable cancelAction;
    private ResponseData current;
    private String prettyText;
    private String rawText;
    private String suggestedName = "response";

    public ResponsePanel() {
        super(new BorderLayout());
        setOpaque(false);

        // header row
        JLabel title = Ui.heading("Response", 13f);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(0, 0, 1, 0), Ui.pad(8, 14, 8, 10)));
        JPanel left = Ui.transparent(null);
        left.setLayout(new javax.swing.BoxLayout(left, javax.swing.BoxLayout.X_AXIS));
        left.add(title);
        left.add(Ui.hgap(10));
        left.add(meta);
        header.add(left, BorderLayout.CENTER); // shrinks first when space is tight
        header.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fitMeta();
            }
        });
        tools.add(views);
        tools.add(Ui.toolbarButton(Icons.get("search"), "Find in response (" + Keys.label(Keys.FIND) + ")", e -> showFind()));
        copyButton = Ui.toolbarButton(Icons.get("copy"), "Copy response body", e -> copyShown());
        tools.add(copyButton);
        tools.add(Ui.toolbarButton(Icons.get("download"), "Save response to file…", e -> saveBody()));
        header.add(tools, BorderLayout.EAST);
        views.onChange(i -> showView());

        JPanel top = Ui.transparent(null);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(header);
        findBar.setBorder(BorderFactory.createCompoundBorder(Ui.line(0, 0, 1, 0), Ui.pad(4, 10, 4, 10)));
        findBar.add(findField);
        findBar.add(Ui.button("Previous", e -> find(false)));
        findBar.add(Ui.button("Next", e -> find(true)));
        findBar.add(matchCase);
        matchCase.addActionListener(e -> liveFind());
        findBar.add(findStatus);
        findBar.add(Ui.toolbarButton(Icons.get("close", 12), "Close find bar", e -> hideFind()));
        findBar.setVisible(false);
        findField.addActionListener(e -> find(true));
        // Search as you type, like Safari and Xcode find bars.
        findField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                liveFind();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                liveFind();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        findField.registerKeyboardAction(e -> hideFind(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);
        top.add(findBar);
        warnings.setBorder(Ui.pad(6, 12, 0, 12));
        warnings.setVisible(false);
        top.add(warnings);
        add(top, BorderLayout.NORTH);

        // body
        body.setEditable(false);
        javax.swing.JScrollPane bodyScroll = CodeArea.scroll(body, true);
        bodyScroll.setBorder(BorderFactory.createEmptyBorder());


        // empty
        JPanel empty = new JPanel(new GridBagLayout());
        empty.add(emptyLabel);

        // loading
        JPanel loading = new JPanel(new GridBagLayout());
        JPanel lbox = Ui.transparent(null);
        lbox.setLayout(new BoxLayout(lbox, BoxLayout.Y_AXIS));
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(260, 4));
        bar.setMaximumSize(new Dimension(260, 4));
        bar.setAlignmentX(0.5f);
        loadingLabel.setFont(Theme.mono(12f));
        loadingLabel.setAlignmentX(0.5f);
        loadingCancel.setAlignmentX(0.5f);
        loadingCancel.addActionListener(e -> {
            if (cancelAction != null) cancelAction.run();
        });
        lbox.add(bar);
        lbox.add(Ui.vgap(12));
        lbox.add(loadingLabel);
        lbox.add(Ui.vgap(12));
        lbox.add(loadingCancel);
        loading.add(lbox);

        // binary
        JPanel binary = new JPanel(new BorderLayout());
        binary.add(binaryLabel, BorderLayout.CENTER);
        JPanel binFoot = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 8, 8));
        binFoot.add(binaryInfo);
        binFoot.add(Ui.button("Save to File…", e -> saveBody()));
        binary.add(binFoot, BorderLayout.SOUTH);

        center.add(empty, "empty");
        center.add(loading, "loading");
        center.add(bodyScroll, "body");
        center.add(headersView, "headers");
        center.add(binary, "binary");
        center.add(errorHolder, "error");
        add(center, BorderLayout.CENTER);
        showEmpty(null);
    }

    // ---- states ----------------------------------------------------------------------------

    public void showEmpty(String message) {
        stopTimer();
        current = null;
        emptyLabel.setText(message == null ? "Send a request to see the response here." : message);
        meta.removeAll();
        tools.setVisible(false);
        warnings.setVisible(false);
        cards.show(center, "empty");
        revalidate();
        repaint();
    }

    public void showLoading(Runnable cancel) {
        cancelAction = cancel;
        meta.removeAll();
        tools.setVisible(false);
        warnings.setVisible(false);
        long start = System.currentTimeMillis();
        loadingLabel.setText("Waiting for response · 0.0 s");
        stopTimer();
        elapsedTimer = new Timer(100, e -> loadingLabel.setText(String.format(Locale.ROOT,
                "Waiting for response · %.1f s", (System.currentTimeMillis() - start) / 1000.0)));
        elapsedTimer.start();
        cards.show(center, "loading");
        revalidate();
        repaint();
    }

    /**
     * When the header is too narrow, drop trailing details (size, then time) rather than letting the last one
     * get cut off mid-word.
     */
    private void fitMeta() {
        java.awt.Container left = meta.getParent();
        if (left == null || header.getWidth() == 0) return;
        java.awt.Component[] parts = meta.getComponents();
        for (java.awt.Component c : parts) c.setVisible(true);
        int available = header.getWidth() - tools.getPreferredSize().width - header.getInsets().left - header.getInsets().right;
        for (int i = parts.length - 1; i > 0 && left.getPreferredSize().width > available; i--) parts[i].setVisible(false);
        meta.revalidate();
    }

    public void showResponse(ResponseData r, List<String> notes, String fileName) {
        stopTimer();
        current = r;
        suggestedName = fileName == null || fileName.isBlank() ? "response" : fileName;
        meta.removeAll();
        meta.add(Ui.statusPill(r.status, (r.status + " " + r.reason()).trim()));
        meta.add(Ui.secondary(Formatters.duration(r.elapsedMs)));
        meta.add(Ui.secondary(Formatters.size(r.body.length)));
        fitMeta();
        tools.setVisible(true);
        views.button(2).setText("Headers " + r.headers.size());
        setNotes(notes);

        headersView.setHeaders(r.headers);

        if (r.isBinary()) {
            String ct = r.contentType();
            ImageIcon icon = null;
            boolean image = ct.toLowerCase(Locale.ROOT).startsWith("image/") && !ct.contains("svg");
            if (image) {
                // ImageIO decodes now and returns null (or throws) for damaged data, so a broken image gets a
                // message instead of an empty area; Toolkit.createImage failed later, silently.
                java.awt.image.BufferedImage img = null;
                try {
                    img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(r.body));
                } catch (java.io.IOException | RuntimeException e) {
                    img = null;
                }
                if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                    double s = Math.min(1.0, Math.min(900.0 / img.getWidth(), 600.0 / img.getHeight()));
                    icon = new ImageIcon(s < 1.0
                            ? img.getScaledInstance((int) Math.max(1, img.getWidth() * s), (int) Math.max(1, img.getHeight() * s), Image.SCALE_SMOOTH)
                            : img);
                }
            }
            binaryLabel.setIcon(icon);
            binaryLabel.setText(icon != null ? null : image ? "This image couldn't be previewed" : "Binary content");
            binaryInfo.setText((ct.isBlank() ? "binary" : ct) + " · " + Formatters.size(r.body.length));
            prettyText = null;
            rawText = null;
            views.setVisible(false);
            cards.show(center, "binary");
        } else {
            views.setVisible(true);
            String text = r.bodyText();
            String note = null;
            if (text.length() > MAX_DISPLAY) {
                text = text.substring(0, MAX_DISPLAY);
                note = "Showing the first " + Formatters.size(MAX_DISPLAY) + ". Save to file to see everything.";
            }
            rawText = text;
            String ct = r.contentType();
            CodeArea.Lang style = CodeArea.Lang.NONE;
            prettyText = null;
            if (Formatters.looksLikeJson(ct, text)) {
                style = CodeArea.Lang.JSON;
                if (text.length() <= MAX_PRETTY) prettyText = Formatters.prettyJson(text);
            } else if (Formatters.looksLikeXml(ct, text)) {
                style = CodeArea.Lang.XML;
                if (text.length() <= MAX_PRETTY) prettyText = Formatters.prettyXml(text);
            } else if (Formatters.looksLikeHtml(ct)) {
                style = CodeArea.Lang.HTML;
            }
            body.setLanguage(style);
            if (note != null) addNote(note);
            views.setSelectedIndex(prettyText != null ? 0 : 1);
            views.button(0).setEnabled(prettyText != null);
            showView();
        }
        revalidate();
        repaint();
    }

    public void showMessage(String headline, String detail) {
        stopTimer();
        meta.removeAll();
        tools.setVisible(false);
        warnings.setVisible(false);
        JPanel p = errorPanel(Icons.get("info", 28), headline, detail, null);
        errorHolder.removeAll();
        errorHolder.add(p);
        cards.show(center, "error");
        revalidate();
        repaint();
    }

    public void showError(ResponseData r, ErrorActions actions, Duration timeout, TlsManager tls, List<String> notes) {
        stopTimer();
        current = null;
        meta.removeAll();
        String badge = r.isTls() ? "TLS handshake failed" : r.errorKind == ResponseData.ErrorKind.TIMEOUT ? "Timed out" : "Connection failed";
        if (r.errorKind == ResponseData.ErrorKind.INVALID) badge = "Not sent";
        meta.add(new Ui.Pill(badge, Theme::errBg, Theme::errFg));
        if (r.elapsedMs > 0) meta.add(Ui.secondary("after " + Formatters.duration(r.elapsedMs)));
        tools.setVisible(false);
        setNotes(notes);

        String host = r.host == null ? "the server" : r.host;
        JPanel options = Ui.transparent(null);
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        String headline;
        String detail;
        switch (r.errorKind) {
            case TLS_UNTRUSTED -> {
                X509Certificate[] chain = tls.lastChain(r.host);
                String issuer = chain != null && chain.length > 0
                        ? TlsManager.commonName(chain[chain.length - 1]) : "an unknown authority";
                headline = "The server's certificate isn't trusted";
                detail = host + " presented a certificate signed by “" + issuer + "”, which isn't in the system trust store or in "
                        + AppPaths.display(tls.certsDir()) + ".";
                JButton trust = Ui.accent("View & Trust…", e -> actions.trust(r));
                trust.setEnabled(chain != null);
                options.add(optionCard("Trust this certificate", "Recommended",
                        "Saves it to " + AppPaths.display(tls.certsDir()) + " and retries.", trust));
                options.add(Ui.vgap(8));
                options.add(optionCard("Retry without verification", null,
                        "Skips certificate and hostname checks for this request only.",
                        Ui.button("Retry Insecurely", e -> actions.retryInsecure())));
            }
            case TLS_HOSTNAME -> {
                headline = "The certificate doesn't match " + host;
                detail = "The certificate is trusted but was issued for " + names(tls.lastChain(r.host))
                        + ". Use a matching host name, or skip verification for this request.";
                options.add(optionCard("Retry without verification", null,
                        "Skips certificate and hostname checks for this request only.",
                        Ui.button("Retry Insecurely", e -> actions.retryInsecure())));
            }
            case TLS_OTHER -> {
                headline = "Couldn't set up a secure connection";
                detail = String.valueOf(r.errorMessage);
                options.add(optionCard("Retry without verification", null,
                        "Skips certificate and hostname checks for this request only.",
                        Ui.button("Retry Insecurely", e -> actions.retryInsecure())));
            }
            case TIMEOUT -> {
                headline = "The request timed out";
                detail = r.errorMessage + " (" + timeout.toSeconds() + " s). You can raise the timeout in this request's Settings tab.";
                options.add(Ui.row(0, Ui.button("Retry", e -> actions.retry())));
            }
            case CONNECT -> {
                headline = "Couldn't connect to " + host;
                detail = "Nothing accepted the connection. Check that the server is running and the port is right.";
                options.add(Ui.row(0, Ui.button("Retry", e -> actions.retry())));
            }
            case DNS -> {
                headline = "Couldn't find " + host;
                detail = "Check the spelling of the host, or your network or VPN connection.";
                options.add(Ui.row(0, Ui.button("Retry", e -> actions.retry())));
            }
            case INVALID -> {
                headline = "The request couldn't be sent";
                detail = String.valueOf(r.errorMessage);
            }
            default -> {
                headline = "Something went wrong";
                detail = String.valueOf(r.errorMessage);
                options.add(Ui.row(0, Ui.button("Retry", e -> actions.retry())));
            }
        }
        JPanel p = errorPanel(Icons.get(r.isTls() ? "shield" : "error", 28), headline, detail, options);
        if (r.error != null && r.errorKind != ResponseData.ErrorKind.INVALID) addDetails(p, r.error);
        errorHolder.removeAll();
        errorHolder.add(p);
        cards.show(center, "error");
        revalidate();
        repaint();
    }

    private static String names(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) return "another name";
        try {
            var sans = chain[0].getSubjectAlternativeNames();
            if (sans != null && !sans.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (List<?> e : sans) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.get(1));
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return TlsManager.commonName(chain[0]);
    }

    private JPanel errorPanel(javax.swing.Icon icon, String headline, String detail, JComponent options) {
        JPanel outer = new JPanel(new GridBagLayout());
        Theme.bg(outer, Theme::codeBg);
        JPanel col = Ui.transparent(null);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setMaximumSize(new Dimension(640, Integer.MAX_VALUE));

        JPanel head = Ui.transparent(new BorderLayout(14, 0));
        JLabel ic = new JLabel(icon);
        ic.setVerticalAlignment(SwingConstants.TOP);
        head.add(ic, BorderLayout.WEST);
        JPanel texts = Ui.transparent(null);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        JLabel h = Ui.heading(headline, 16f);
        h.setAlignmentX(0f);
        texts.add(h);
        texts.add(Ui.vgap(6));
        JLabel d = Ui.wrappingSecondary(detail, 520);
        d.setAlignmentX(0f);
        texts.add(d);
        head.add(texts);
        head.setAlignmentX(0f);
        col.add(head);
        if (options != null) {
            col.add(Ui.vgap(16));
            JPanel indent = Ui.transparent(new BorderLayout());
            indent.setBorder(Ui.pad(0, 42, 0, 0));
            indent.add(options);
            indent.setAlignmentX(0f);
            col.add(indent);
        }
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(24, 24, 24, 24);
        gc.anchor = GridBagConstraints.NORTH;
        gc.weighty = 1;
        outer.add(col, gc);
        outer.putClientProperty("rc.col", col);
        return outer;
    }

    private void addDetails(JPanel errorPanel, Throwable t) {
        JPanel col = (JPanel) errorPanel.getClientProperty("rc.col");
        StringWriter sw = new StringWriter();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth++ < 6) {
            sw.append(c.getClass().getName()).append(": ").append(String.valueOf(c.getMessage())).append('\n');
            c = c.getCause();
        }
        JTextArea area = new JTextArea(sw.toString().trim());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.mono(11.5f));
        Theme.bg(area, Theme::subtleBg);
        area.setBorder(Ui.pad(8, 10, 8, 10));
        area.setVisible(false);
        JToggleButton toggle = new JToggleButton("Details");
        toggle.putClientProperty("JButton.buttonType", "borderless");
        toggle.setIcon(new dev.restclient.ui.laf.LafIcons.Chevron(dev.restclient.ui.laf.LafIcons.Direction.RIGHT, 12, Theme::secondary));
        toggle.setSelectedIcon(new dev.restclient.ui.laf.LafIcons.Chevron(dev.restclient.ui.laf.LafIcons.Direction.DOWN, 12, Theme::secondary));
        toggle.setIconTextGap(4);
        toggle.addActionListener(e -> {
            area.setVisible(toggle.isSelected());
            col.revalidate();
        });
        JPanel wrap = Ui.transparent(new BorderLayout(0, 6));
        wrap.setBorder(Ui.pad(10, 38, 0, 0));
        wrap.add(Ui.row(0, toggle), BorderLayout.NORTH);
        wrap.add(area, BorderLayout.CENTER);
        wrap.setAlignmentX(0f);
        col.add(wrap);
    }

    private static JComponent optionCard(String title, String tag, String detail, JButton action) {
        Ui.RoundedPanel card = new Ui.RoundedPanel(new BorderLayout(20, 0), Theme::editorBg, Theme::separator, 12);
        card.setBorder(Ui.pad(12, 16, 12, 14));
        JPanel texts = Ui.transparent(null);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        JPanel titleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ((FlowLayout) titleRow.getLayout()).setAlignOnBaseline(true);
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD));
        titleRow.add(t);
        if (tag != null) {
            JLabel tagLabel = new JLabel("  ·  " + tag) {
                @Override
                public void updateUI() {
                    super.updateUI();
                    setForeground(Theme.okFg());
                }
            };
            tagLabel.setForeground(Theme.okFg());
            titleRow.add(tagLabel);
        }
        titleRow.setAlignmentX(0f);
        texts.add(titleRow);
        JLabel d = Ui.small(detail);
        d.setAlignmentX(0f);
        texts.add(Ui.vgap(3));
        texts.add(d);
        card.add(texts, BorderLayout.CENTER);
        JPanel btn = Ui.transparent(new GridBagLayout());
        btn.add(action);
        card.add(btn, BorderLayout.EAST);
        card.setAlignmentX(0f);
        card.setMaximumSize(new Dimension(620, 96));
        return card;
    }

    private void setNotes(List<String> notes) {
        warnings.removeAll();
        if (notes != null) for (String n : notes) addNote(n);
        warnings.setVisible(warnings.getComponentCount() > 0);
    }

    private void addNote(String n) {
        Ui.Pill p = new Ui.Pill(n, Theme::warnBg, Theme::warnFg);
        p.setFont(p.getFont().deriveFont(Font.PLAIN));
        p.setIcon(Icons.get("warning", 12));
        warnings.add(p);
        warnings.setVisible(true);
    }

    private void showView() {
        int i = views.selectedIndex();
        boolean headers = i == 2;
        if (copyButton != null) {
            String tip = headers ? "Copy all headers" : "Copy response body";
            copyButton.setToolTipText(tip);
            copyButton.getAccessibleContext().setAccessibleName(tip);
        }
        if (headers) {
            cards.show(center, "headers");
            return;
        }
        String text = i == 0 && prettyText != null ? prettyText : rawText;
        body.setText(text == null ? "" : text);
        body.setCaretPosition(0);
        cards.show(center, "body");
    }

    private void stopTimer() {
        if (elapsedTimer != null) elapsedTimer.stop();
        elapsedTimer = null;
    }

    // ---- find / copy / save ----------------------------------------------------------------

    public void showFind() {
        if (current == null || current.isBinary()) return;
        if (views.selectedIndex() == 2) {
            views.setSelectedIndex(prettyText != null ? 0 : 1);
            showView();
        }
        findBar.setVisible(true);
        revalidate();
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    private void hideFind() {
        findBar.setVisible(false);
        body.clearMarks();
        revalidate();
    }

    private void liveFind() {
        if (findField.getText().isEmpty()) {
            body.clearMarks();
            findStatus.setText("");
            return;
        }
        body.setCaretPosition(0);
        find(true);
    }

    private void find(boolean forward) {
        String q = findField.getText();
        if (q.isEmpty()) return;
        int count = body.markAll(q, matchCase.isSelected());
        boolean found = body.find(q, matchCase.isSelected(), forward);
        findStatus.setText(found ? count + (count == 1 ? " match" : " matches") : "Not found");
    }

    /** The toolbar's Copy: the headers while the Headers view is showing, otherwise the body. */
    private void copyShown() {
        if (current == null) return;
        if (views.selectedIndex() == 2) headersView.copyAll();
        else copyBody();
    }

    private void copyBody() {
        if (current == null) return;
        String text = views.selectedIndex() == 0 && prettyText != null ? prettyText : current.bodyText();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void saveBody() {
        if (current == null) return;
        File file = chooseSaveFile(this, suggestedName + extension(current));
        if (file == null) return;
        try {
            Files.write(file.toPath(), current.body);
        } catch (IOException e) {
            Ui.error(this, "Couldn't save", e.getMessage());
        }
    }

    private static String extension(ResponseData r) {
        String ct = r.contentType().toLowerCase(Locale.ROOT);
        if (ct.contains("json")) return ".json";
        if (ct.contains("xml")) return ".xml";
        if (ct.contains("html")) return ".html";
        if (ct.contains("png")) return ".png";
        if (ct.contains("jpeg")) return ".jpg";
        if (ct.contains("gif")) return ".gif";
        if (ct.contains("pdf")) return ".pdf";
        if (ct.startsWith("text/")) return ".txt";
        return "";
    }

    /** Native save dialog on macOS, Swing chooser elsewhere. */
    static File chooseSaveFile(java.awt.Component parent, String name) {
        Window w = javax.swing.SwingUtilities.getWindowAncestor(parent);
        if (Platform.isMac() && w instanceof Frame f) {
            FileDialog fd = new FileDialog(f, "Save", FileDialog.SAVE);
            fd.setFile(name);
            fd.setVisible(true);
            if (fd.getFile() == null) return null;
            return new File(fd.getDirectory(), fd.getFile());
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(System.getProperty("user.home"), name));
        return fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }
}
