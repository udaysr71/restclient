package dev.restclient.ui;

import dev.restclient.curl.CurlExporter;
import dev.restclient.http.HttpService;
import dev.restclient.http.PreparedRequest;
import dev.restclient.http.ResponseData;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HistoryEntry;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.vars.VariableResolver;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One open request: request bar, editor tabs (Params, Headers, Body, Auth, Settings) and the response. */
public class RequestTab extends JPanel {
    private static final List<String> COMMON_HEADERS = List.of(
            "Accept", "Accept-Encoding", "Accept-Language", "Authorization", "Cache-Control", "Content-Type",
            "Cookie", "If-Match", "If-None-Match", "If-Modified-Since", "Idempotency-Key", "Origin", "Pragma",
            "Range", "Referer", "User-Agent", "X-API-Key", "X-Correlation-Id", "X-Forwarded-For", "X-Request-Id",
            "X-Requested-With", "X-Tenant-Id");
    private static final List<String> MEDIA_TYPES = List.of(
            "application/json", "application/xml", "application/x-www-form-urlencoded", "multipart/form-data",
            "text/plain", "text/html", "text/csv", "application/octet-stream", "*/*");

    private final AppContext ctx;
    private final MainFrame frame;
    private RequestDef draft;
    private RequestDef saved;
    private final RequestDef blank;
    private String collectionId;
    private String nodeId;

    private final JComboBox<HttpMethod> methodBox = new JComboBox<>(HttpMethod.values());
    private final VarTextField urlField;
    private final JButton tlsBadge = new JButton("TLS off");
    private final Ui.AccentButton sendButton = Ui.accent("Send", null);
    private final UnderlineTabs editorTabs = new UnderlineTabs();
    private final KeyValueTable paramsTable;
    private final KeyValueTable headersTable;
    private final Ui.Segmented bodyType = new Ui.Segmented("None", "JSON", "XML", "Text", "Form", "Multipart", "File");
    private final JButton beautify = Ui.button("Beautify", null);
    private final CodeArea bodyEditor = new CodeArea(10, 60);
    private final KeyValueTable formTable;
    private final KeyValueTable multipartTable;
    private final VarTextField bodyFile;
    private final KeyValueTable capturesTable;
    private final KeyValueTable localsTable;
    private final Ui.Segmented paramsView = new Ui.Segmented("Query", "Local variables");
    private final CardLayout paramsCards = new CardLayout();
    private final JPanel paramsCardPanel = new JPanel(paramsCards);
    /** The last response, so the Capture tab can suggest what's in it. */
    private ResponseData lastResponse;
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel bodyCardPanel = new JPanel(bodyCards);
    private final JComboBox<String> authType = new JComboBox<>(new String[]{"No auth", "Basic", "Bearer token"});
    private final VarTextField authUser;
    private final VarTextField authPass;
    private final VarTextField authToken;
    private final CardLayout authCards = new CardLayout();
    private final JPanel authCardPanel = new JPanel(authCards);
    private final JComboBox<String> tlsMode = new JComboBox<>();
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(0, 0, 3600, 5));
    private final JLabel timeoutHint = Ui.small("");
    private final ResponsePanel response = new ResponsePanel();
    private final JSplitPane split;

    private HttpService.Call call;
    private boolean loading;
    private boolean syncing;

    /** Method + URL as one field; outlined in red when the URL can't be sent. */
    private final FieldGroup requestGroup = new FieldGroup(new GridBagLayout());

    public RequestTab(AppContext ctx, MainFrame frame, RequestDef request, String collectionId, String nodeId, RequestDef saved) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.frame = frame;
        this.draft = request.copy();
        this.saved = saved == null ? null : saved.copy();
        this.blank = request.copy();
        this.collectionId = collectionId;
        this.nodeId = nodeId;

        urlField = new VarTextField(this::resolver, frame, true).placeholder("Enter a URL or paste a curl command");
        urlField.setCurlPasteHandler(text -> frame.importCurlInto(this, text));
        paramsTable = new KeyValueTable(this::resolver, frame, "Add parameter", "Value", null, null);
        headersTable = new KeyValueTable(this::resolver, frame, "Add header", "Value",
                text -> suggest(COMMON_HEADERS, text), this::headerValueSuggestions);
        formTable = new KeyValueTable(this::resolver, frame, "Add field", "Value", null, null);
        multipartTable = new KeyValueTable(this::resolver, frame, "Add field", "Value", null, null, true);
        bodyFile = new VarTextField(this::resolver, frame, true).placeholder("Path of the file to send");
        capturesTable = new KeyValueTable(this::resolver, frame, "Variable name", "status, header.Name, body.data.token…",
                text -> suggest(new ArrayList<>(resolver().names()), text), (key, text) -> captureSuggestions(text))
                .titles("Save to variable", "From the response");
        localsTable = new KeyValueTable(this::resolver, frame, "Variable name", "Value", null, null).titles("Variable", "Value");
        authUser = new VarTextField(this::resolver, frame, true).placeholder("Username");
        authPass = new VarTextField(this::resolver, frame, true).placeholder("Password");
        authToken = new VarTextField(this::resolver, frame, true).placeholder("Token, e.g. {{token}}");

        add(buildRequestBar(), BorderLayout.NORTH);
        JPanel editor = new JPanel(new BorderLayout());
        editor.add(buildEditorTabs());
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor, response);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(7);
        split.setContinuousLayout(true);
        if (split.getUI() instanceof BasicSplitPaneUI ui) {
            ui.getDivider().addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) split.setDividerLocation(0.45);
                }
            });
        }
        add(split, BorderLayout.CENTER);

        loadIntoUi();
        wireChanges();
        updateState();
    }

    // ---- building --------------------------------------------------------------------------

    private JPanel buildRequestBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(Ui.pad(12, 14, 10, 14));
        methodBox.setFont(Theme.mono(12.5f).deriveFont(Font.BOLD));
        methodBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof HttpMethod m) {
                    setText(m.name());
                    setFont(Theme.mono(12.5f).deriveFont(Font.BOLD));
                    if (!sel) setForeground(Theme.methodColor(m));
                }
                return this;
            }
        });
        methodBox.setToolTipText("HTTP method");
        methodBox.getAccessibleContext().setAccessibleName("HTTP method");
        urlField.getAccessibleContext().setAccessibleName("Request URL");

        tlsBadge.setIcon(Icons.get("lock-open", 14));
        tlsBadge.setFont(tlsBadge.getFont().deriveFont(Font.BOLD, tlsBadge.getFont().getSize2D() - 1f));
        styleBadge();
        Theme.onChange(this::styleBadge);
        tlsBadge.setToolTipText("Certificate and hostname checks are skipped for this request");
        tlsBadge.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            JMenuItem verify = new JMenuItem("Verify certificates for this request");
            verify.addActionListener(a -> setInsecure(Boolean.FALSE));
            JMenuItem def = new JMenuItem("Use the default setting (" + (ctx.settings().insecureByDefault ? "skip" : "verify") + ")");
            def.addActionListener(a -> setInsecure(null));
            m.add(verify);
            m.add(def);
            Ui.showBelow(m, tlsBadge);
        });

        sendButton.setToolTipText("Send (" + Keys.label(Keys.SEND) + ")");
        sendButton.addActionListener(e -> send());

        JButton more = Ui.toolbarButton(Icons.get("more"), "More actions", null);
        more.addActionListener(e -> Ui.showBelow(moreMenu(), more));

        // Method and URL are one field: they define the request together, and Send sits right beside them.
        FieldGroup request = requestGroup;
        methodBox.setBorder(Ui.pad(0, 6, 0, 2));
        urlField.setBorder(Ui.pad(0, 10, 0, 10));
        GridBagConstraints in = new GridBagConstraints();
        in.gridy = 0;
        in.fill = GridBagConstraints.VERTICAL;
        request.addPart(methodBox, in);
        request.add(FieldGroup.divider(), in);
        in.weightx = 1;
        in.fill = GridBagConstraints.BOTH;
        request.addPart(urlField, in);
        in.weightx = 0;
        in.fill = GridBagConstraints.NONE;
        in.insets = new Insets(0, 0, 0, 5);
        request.add(tlsBadge, in);
        methodBox.setPreferredSize(new java.awt.Dimension(Theme.scaled(104), 0));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.BOTH;
        gc.insets = new Insets(0, 0, 0, 8);
        bar.add(request, gc);
        gc.weightx = 0;
        gc.fill = GridBagConstraints.VERTICAL;
        bar.add(sendButton, gc);
        gc.insets = new Insets(0, 0, 0, 0);
        bar.add(more, gc);
        request.setPreferredSize(new java.awt.Dimension(300, Theme.scaled(38)));
        sendButton.setPreferredSize(new java.awt.Dimension(Theme.scaled(88), Theme.scaled(38)));
        return bar;
    }

    private void styleBadge() {
        sendButton.setPreferredSize(new java.awt.Dimension(Theme.scaled(88), Theme.scaled(38)));
        if (sendButton.getParent() != null) {
            for (Component c : sendButton.getParent().getComponents()) {
                if (c instanceof FieldGroup g) g.setPreferredSize(new java.awt.Dimension(300, Theme.scaled(38)));
            }
            methodBox.setPreferredSize(new java.awt.Dimension(Theme.scaled(104), 0));
        }
        tlsBadge.setBackground(Theme.warnBg());
        tlsBadge.setForeground(Theme.warnFg());
        tlsBadge.putClientProperty("rc.borderColor", Theme.warnBorder());
    }

    private UnderlineTabs buildEditorTabs() {
        editorTabs.setBorder(Ui.pad(0, 14, 0, 14));
        editorTabs.addTab("Params", buildParams());
        editorTabs.addTab("Headers", pad(headersTable));
        editorTabs.addTab("Body", buildBody());
        editorTabs.addTab("Auth", buildAuth());
        editorTabs.addTab("Capture", buildCapture());
        editorTabs.addTab("Settings", buildSettings());
        return editorTabs;
    }

    private static JPanel pad(Component c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(Ui.pad(8, 14, 10, 14));
        p.add(c);
        return p;
    }

    private JPanel buildBody() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(Ui.pad(8, 14, 10, 14));
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(bodyType, BorderLayout.WEST);
        beautify.setToolTipText("Format the JSON or XML body");
        beautify.addActionListener(e -> beautify());
        top.add(Ui.row(0, beautify), BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);

        VarSupport.install(bodyEditor, this::resolver, frame);
        javax.swing.JScrollPane sp = CodeArea.scroll(bodyEditor, true);
        sp.setBorder(Ui.line(1, 1, 1, 1));

        JPanel none = new JPanel(new GridBagLayout());
        none.add(Ui.secondary("This request has no body."));
        bodyCardPanel.add(none, "none");
        bodyCardPanel.add(sp, "raw");
        bodyCardPanel.add(formTable, "form");
        bodyCardPanel.add(multipartTable, "multipart");
        bodyCardPanel.add(buildBinary(), "binary");
        p.add(bodyCardPanel, BorderLayout.CENTER);
        return p;
    }

    /** Query parameters, and behind a switch the request's local variables. */
    private JPanel buildParams() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(Ui.pad(8, 14, 10, 14));
        JPanel top = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.add(paramsView);
        p.add(top, BorderLayout.NORTH);
        JPanel locals = Ui.transparent(new BorderLayout(0, 8));
        locals.add(localsTable, BorderLayout.CENTER);
        locals.add(Ui.small("<html>Use these as <b>{{name}}</b> anywhere in this request. They apply to this request only and win over "
                + "the environment, the collection and Globals. They're saved with the request.</html>"), BorderLayout.SOUTH);
        paramsCardPanel.setOpaque(false);
        paramsCardPanel.add(paramsTable, "query");
        paramsCardPanel.add(locals, "locals");
        p.add(paramsCardPanel, BorderLayout.CENTER);
        paramsView.onChange(i -> paramsCards.show(paramsCardPanel, i == 1 ? "locals" : "query"));
        return p;
    }

    /** Shows the local variable called {@code name}, if this request has one. */
    boolean showLocal(String name) {
        List<KeyValue> rows = localsTable.getRows();
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).enabled || !name.equals(rows.get(i).key.trim())) continue;
            editorTabs.setSelectedIndex(0);
            paramsView.setSelectedIndex(1);
            paramsCards.show(paramsCardPanel, "locals");
            localsTable.table().changeSelection(i, 2, false, false);
            localsTable.table().requestFocusInWindow();
            return true;
        }
        return false;
    }

    private JPanel buildBinary() {
        JPanel p = Ui.transparent(new BorderLayout());
        JButton choose = Ui.button("Choose File…", e -> {
            java.io.File f = FileChoosers.open(this, "Choose a File to Send");
            if (f != null) bodyFile.setText(f.getPath());
        });
        bodyFile.getAccessibleContext().setAccessibleName("File to send");
        JPanel line = Ui.transparent(new BorderLayout(8, 0));
        line.add(bodyFile, BorderLayout.CENTER);
        line.add(choose, BorderLayout.EAST);
        JPanel box = Ui.transparent(new BorderLayout(0, 8));
        box.add(line, BorderLayout.NORTH);
        box.add(Ui.small("The file is sent as the whole body. Content-Type comes from its extension unless you set the header yourself."),
                BorderLayout.CENTER);
        p.add(box, BorderLayout.NORTH);
        return p;
    }

    private JPanel buildCapture() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(Ui.pad(8, 14, 10, 14));
        p.add(capturesTable, BorderLayout.CENTER);
        JLabel hint = Ui.small("<html>After each response, these values are saved into variables for later requests to use, "
                + "such as a login token. A variable is updated where it's already defined; a new one goes into the active "
                + "environment, or Globals if none is selected.<br>Sources: <b>status</b> · <b>header.Location</b> · "
                + "<b>cookie.session</b> · <b>body</b> · <b>body.data.token</b> · <b>body.items[0].id</b></html>");
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    /** What the Capture tab offers while typing a source: what the last response actually contains. */
    private List<String> captureSuggestions(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : dev.restclient.capture.Capture.suggestions(lastResponse)) {
            if (o.toLowerCase(Locale.ROOT).contains(t) && !o.equals(text)) out.add(o);
            if (out.size() >= 40) break;
        }
        return out;
    }

    private JPanel buildAuth() {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBorder(Ui.pad(12, 14, 10, 14));
        // no leading gap, so "Type" lines up with the labels of the fields below it
        JPanel top = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel l = new JLabel("Type");
        l.setLabelFor(authType);
        l.setBorder(Ui.pad(0, 0, 0, 12));
        top.add(l);
        top.add(authType);
        p.add(top, BorderLayout.NORTH);

        JPanel none = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        none.add(Ui.secondary("No Authorization header is added. Headers you set yourself are always sent."));
        JPanel basic = form(new String[]{"Username", "Password"}, new Component[]{authUser, authPass});
        JPanel bearer = form(new String[]{"Token"}, new Component[]{authToken});
        authCardPanel.add(none, "none");
        authCardPanel.add(basic, "basic");
        authCardPanel.add(bearer, "bearer");
        authCardPanel.setOpaque(false);
        p.add(authCardPanel, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildSettings() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(Ui.pad(12, 14, 10, 14));
        JPanel f = form(new String[]{"TLS verification", "Timeout (seconds)"}, new Component[]{tlsMode, timeout});
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 1;
        gc.gridy = 2;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(2, 0, 0, 0);
        f.add(timeoutHint, gc);
        JLabel note = Ui.small("Skipping verification turns off certificate and hostname checks. Use it only for servers you control.");
        gc.gridy = 3;
        gc.insets = new Insets(14, 0, 0, 0);
        f.add(note, gc);
        p.add(f, BorderLayout.NORTH);
        return p;
    }

    private static JPanel form(String[] labels, Component[] fields) {
        JPanel p = Ui.transparent(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        for (int i = 0; i < labels.length; i++) {
            gc.gridx = 0;
            gc.gridy = i;
            gc.anchor = GridBagConstraints.WEST;
            gc.insets = new Insets(i == 0 ? 0 : 8, 0, 0, 12);
            gc.fill = GridBagConstraints.NONE;
            gc.weightx = 0;
            JLabel l = new JLabel(labels[i]);
            if (fields[i] instanceof javax.swing.JComponent jc) l.setLabelFor(jc);
            p.add(l, gc);
            gc.gridx = 1;
            gc.weightx = 1;
            gc.fill = fields[i] instanceof VarTextField ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            gc.insets = new Insets(i == 0 ? 0 : 8, 0, 0, 0);
            p.add(fields[i], gc);
        }
        // takes the spare height, so the rows sit at the top instead of floating in the middle
        gc.gridx = 0;
        gc.gridy = labels.length;
        gc.gridwidth = 2;
        gc.weighty = 1;
        gc.fill = GridBagConstraints.BOTH;
        p.add(Ui.transparent(null), gc);
        return p;
    }

    private JPopupMenu moreMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Copy as cURL");
        copy.setAccelerator(Keys.COPY_CURL);
        copy.addActionListener(e -> copyAsCurl(true));
        JMenuItem copyVars = new JMenuItem("Copy as cURL (keep {{variables}})");
        copyVars.addActionListener(e -> copyAsCurl(false));
        JMenuItem save = new JMenuItem("Save");
        save.setAccelerator(Keys.SAVE);
        save.addActionListener(e -> frame.saveTab(this, false));
        save.setEnabled(isDirty() || !isSaved()); // nothing to save in an unchanged, saved request
        JMenuItem saveAs = new JMenuItem("Save As…");
        saveAs.setAccelerator(Keys.SAVE_AS);
        saveAs.addActionListener(e -> frame.saveTab(this, true));
        JMenuItem rename = new JMenuItem("Rename…");
        rename.addActionListener(e -> rename());
        JMenuItem dup = new JMenuItem("Duplicate");
        dup.addActionListener(e -> frame.duplicateTab(this));
        JCheckBoxMenuItem skip = new JCheckBoxMenuItem("Skip TLS verification for this request", ctx.effectiveInsecure(draft));
        skip.addActionListener(e -> setInsecure(skip.isSelected()));
        javax.swing.JMenu code = new javax.swing.JMenu("Copy as Code");
        for (dev.restclient.codegen.CodeGen.Language l : dev.restclient.codegen.CodeGen.Language.values()) {
            JMenuItem item = new JMenuItem(l.label);
            item.addActionListener(e -> copyAsCode(l));
            code.add(item);
        }
        m.add(copy);
        m.add(copyVars);
        m.add(code);
        m.addSeparator();
        m.add(save);
        m.add(saveAs);
        m.add(rename);
        m.add(dup);
        m.addSeparator();
        m.add(skip);
        return m;
    }

    // ---- data <-> ui -----------------------------------------------------------------------

    private void loadIntoUi() {
        loading = true;
        syncing = true;
        try {
            methodBox.setSelectedItem(draft.method);
            urlField.setText(draft.url);
            List<KeyValue> params = parseQuery(draft.url);
            params.addAll(draft.disabledParams);
            paramsTable.setRows(params);
            headersTable.setRows(draft.headers);
            bodyType.setSelectedIndex(draft.bodyType.ordinal());
            bodyEditor.setText(draft.body == null ? "" : draft.body);
            VarSupport.discardEdits(bodyEditor);
            formTable.setRows(draft.form);
            multipartTable.setRows(draft.multipart);
            bodyFile.setText(draft.bodyFile == null ? "" : draft.bodyFile);
            capturesTable.setRows(draft.captures);
            localsTable.setRows(draft.locals);
            Auth a = draft.auth == null ? new Auth() : draft.auth;
            authType.setSelectedIndex(a.type.ordinal());
            authUser.setText(a.username);
            authPass.setText(a.password);
            authToken.setText(a.token);
            refreshTlsModeItems();
            timeout.setValue(draft.timeoutSeconds);
            applyBodyType();
            applyAuthType();
        } finally {
            syncing = false;
            loading = false;
        }
        response.showEmpty(null);
    }

    private void refreshTlsModeItems() {
        boolean wasLoading = loading;
        loading = true;
        tlsMode.removeAllItems();
        tlsMode.addItem("Use default (" + (ctx.settings().insecureByDefault ? "skip verification" : "verify") + ")");
        tlsMode.addItem("Verify certificates");
        tlsMode.addItem("Skip verification (insecure)");
        tlsMode.setSelectedIndex(draft.insecure == null ? 0 : draft.insecure ? 2 : 1);
        timeoutHint.setText("0 uses the default (" + ctx.settings().defaultTimeoutSeconds + " s).");
        loading = wasLoading;
    }

    private void clearUrlError() {
        if (requestGroup.getClientProperty("JComponent.outline") != null) {
            requestGroup.putClientProperty("JComponent.outline", null);
            requestGroup.repaint();
        }
    }

    private void wireChanges() {
        urlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                clearUrlError();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                clearUrlError();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        methodBox.addActionListener(e -> changed());
        urlField.getDocument().addDocumentListener(docListener(this::urlChanged));
        paramsTable.onChange(this::paramsChanged);
        headersTable.onChange(this::changed);
        formTable.onChange(this::changed);
        multipartTable.onChange(this::changed);
        capturesTable.onChange(this::changed);
        localsTable.onChange(() -> {
            changed();
            refreshVariables(); // every {{name}} in the request may now resolve differently
        });
        bodyFile.getDocument().addDocumentListener(docListener(this::changed));
        bodyEditor.getDocument().addDocumentListener(docListener(this::changed));
        bodyType.onChange(i -> {
            applyBodyType();
            changed();
        });
        authType.addActionListener(e -> {
            applyAuthType();
            changed();
        });
        for (VarTextField f : new VarTextField[]{authUser, authPass, authToken}) {
            f.getDocument().addDocumentListener(docListener(this::changed));
        }
        tlsMode.addActionListener(e -> {
            if (loading) return;
            int i = tlsMode.getSelectedIndex();
            draft.insecure = i == 0 ? null : i == 2;
            changed();
        });
        timeout.addChangeListener(e -> changed());
    }

    private static DocumentListener docListener(Runnable r) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        };
    }

    private void applyBodyType() {
        BodyType t = BodyType.values()[Math.max(0, bodyType.selectedIndex())];
        bodyCards.show(bodyCardPanel, t == BodyType.NONE ? "none" : t == BodyType.FORM ? "form"
                : t == BodyType.MULTIPART ? "multipart" : t == BodyType.BINARY ? "binary" : "raw");
        beautify.setVisible(t == BodyType.JSON || t == BodyType.XML);
        bodyEditor.setLanguage(t == BodyType.JSON ? CodeArea.Lang.JSON : t == BodyType.XML ? CodeArea.Lang.XML : CodeArea.Lang.NONE);
    }

    private void applyAuthType() {
        int i = authType.getSelectedIndex();
        authCards.show(authCardPanel, i == 1 ? "basic" : i == 2 ? "bearer" : "none");
    }

    private void urlChanged() {
        if (syncing) return;
        syncing = true;
        try {
            List<KeyValue> rows = parseQuery(urlField.getText());
            for (KeyValue kv : paramsTable.getRows()) if (!kv.enabled) rows.add(kv);
            paramsTable.setRows(rows);
        } finally {
            syncing = false;
        }
        changed();
    }

    private void paramsChanged() {
        if (syncing) return;
        syncing = true;
        try {
            List<KeyValue> enabled = new ArrayList<>();
            for (KeyValue kv : paramsTable.getRows()) if (kv.enabled) enabled.add(kv);
            String url = withQuery(urlField.getText(), enabled);
            if (!url.equals(urlField.getText())) urlField.setText(url);
        } finally {
            syncing = false;
        }
        changed();
    }

    /** Query params parsed from the URL, raw (not decoded) so variables survive. */
    static List<KeyValue> parseQuery(String url) {
        List<KeyValue> out = new ArrayList<>();
        if (url == null) return out;
        int q = url.indexOf('?');
        if (q < 0) return out;
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            out.add(eq < 0 ? new KeyValue(part, "") : new KeyValue(part.substring(0, eq), part.substring(eq + 1)));
        }
        return out;
    }

    static String withQuery(String url, List<KeyValue> params) {
        if (url == null) url = "";
        String fragment = "";
        int hash = url.indexOf('#');
        if (hash >= 0) {
            fragment = url.substring(hash);
            url = url.substring(0, hash);
        }
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        StringBuilder sb = new StringBuilder();
        for (KeyValue kv : params) {
            if (kv.key == null || kv.key.isEmpty()) continue;
            sb.append(sb.length() == 0 ? "" : "&").append(kv.key);
            if (kv.value != null && !kv.value.isEmpty()) sb.append('=').append(kv.value);
        }
        return base + (sb.length() > 0 ? "?" + sb : "") + fragment;
    }

    private void readUi() {
        draft.method = (HttpMethod) methodBox.getSelectedItem();
        draft.url = urlField.getText();
        draft.disabledParams = new ArrayList<>();
        for (KeyValue kv : paramsTable.getRows()) if (!kv.enabled) draft.disabledParams.add(kv);
        draft.headers = headersTable.getRows();
        draft.bodyType = BodyType.values()[Math.max(0, bodyType.selectedIndex())];
        draft.body = bodyEditor.getText();
        draft.form = formTable.getRows();
        draft.multipart = multipartTable.getRows();
        draft.bodyFile = bodyFile.getText();
        draft.captures = capturesTable.getRows();
        draft.locals = localsTable.getRows();
        if (draft.auth == null) draft.auth = new Auth();
        draft.auth.type = Auth.Type.values()[Math.max(0, authType.getSelectedIndex())];
        draft.auth.username = authUser.getText();
        draft.auth.password = authPass.getText();
        draft.auth.token = authToken.getText();
        draft.timeoutSeconds = (Integer) timeout.getValue();
    }

    private void changed() {
        if (loading) return;
        readUi();
        updateState();
    }

    private void updateState() {
        int params = 0;
        for (KeyValue kv : paramsTable.getRows()) if (kv.enabled) params++;
        int locals = localsTable.enabledCount();
        editorTabs.setTitleAt(0, (params > 0 ? "Params " + params : "Params") + (locals > 0 ? " · " + locals + " local" : ""));
        int headers = headersTable.enabledCount();
        editorTabs.setTitleAt(1, headers > 0 ? "Headers " + headers : "Headers");
        editorTabs.setTitleAt(2, draft.bodyType == BodyType.NONE ? "Body" : "Body · " + draft.bodyType.label);
        editorTabs.setTitleAt(3, draft.auth == null || draft.auth.type == Auth.Type.NONE ? "Auth"
                : "Auth · " + (draft.auth.type == Auth.Type.BASIC ? "Basic" : "Bearer"));
        int captures = capturesTable.enabledCount();
        editorTabs.setTitleAt(4, captures > 0 ? "Capture " + captures : "Capture");
        boolean insecure = ctx.effectiveInsecure(draft);
        tlsBadge.setVisible(insecure && ctx.settings().showInsecureBadge);
        frame.tabStateChanged(this);
    }

    // ---- public API used by the main window ------------------------------------------------

    public RequestDef draft() {
        readUi();
        return draft;
    }

    public String title() {
        return draft.name;
    }

    /**
     * What the tab shows. A request that hasn't been named yet shows where it points (its path, or its host if
     * there's no path), so several unsaved tabs can be told apart; once named or saved, its name.
     */
    public String displayTitle() {
        if (isSaved() || !draft.name.startsWith("Untitled")) return draft.name;
        String shown = pathLabel(draft.url);
        return shown.isEmpty() ? draft.name : shown;
    }

    /** "/v2/orders" for "{{baseUrl}}/v2/orders?x=1"; "example.com" for "https://example.com/"; "" if there's no URL. */
    static String pathLabel(String url) {
        String u = url == null ? "" : url.strip();
        if (u.isEmpty() || u.regionMatches(true, 0, "curl ", 0, 5)) return "";
        int cut = u.length();
        for (char stop : new char[]{'?', '#'}) {
            int i = u.indexOf(stop);
            if (i >= 0) cut = Math.min(cut, i);
        }
        u = u.substring(0, cut);
        String rest = u.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = rest.startsWith("{{") ? rest.indexOf('/', Math.max(0, rest.indexOf("}}"))) : rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String label = path.length() > 1 ? path : host;
        // nothing nameable yet (a half-typed "{{", a lone "/"): keep "Untitled request"
        if (label.chars().noneMatch(Character::isLetterOrDigit)) return "";
        // keep the end of a long path: that's the part that differs between requests
        int max = 28;
        return label.length() > max ? "…" + label.substring(label.length() - max + 1) : label;
    }

    public HttpMethod method() {
        return draft.method;
    }

    /** The last saved version (null if never saved). */
    public RequestDef savedCopy() {
        return saved == null ? null : saved.copy();
    }

    public String collectionId() {
        return collectionId;
    }

    public String nodeId() {
        return nodeId;
    }

    public boolean isDirty() {
        return saved != null ? !draft.sameContent(saved) : !draft.sameContent(blank);
    }

    public boolean isSaved() {
        return nodeId != null;
    }

    /** After saving: remember the saved state and where it lives. */
    public void markSaved(String collectionId, String nodeId) {
        this.collectionId = collectionId;
        this.nodeId = nodeId;
        this.saved = draft.copy();
        updateState();
    }

    /** The saved request was renamed/changed in the sidebar while this tab is open. */
    public void savedRequestChanged(RequestDef newSaved) {
        boolean wasDirty = isDirty();
        this.saved = newSaved.copy();
        draft.name = newSaved.name;
        if (!wasDirty) {
            draft = newSaved.copy();
            loadIntoUi();
        }
        updateState();
    }

    /** The saved request was deleted: keep the tab as an unsaved draft. */
    public void detach() {
        collectionId = null;
        nodeId = null;
        saved = null;
        updateState();
    }

    public void setRequestName(String name) {
        draft.name = name;
        updateState();
    }

    public void rename() {
        String n = Ui.prompt(this, "Rename Request", "Name", draft.name);
        if (n != null) {
            setRequestName(n);
            if (isSaved() && !isDirtyExceptName()) frame.saveTab(this, false);
        }
    }

    private boolean isDirtyExceptName() {
        if (saved == null) return true;
        RequestDef a = draft.copy();
        a.name = saved.name;
        return !a.sameContent(saved);
    }

    public void setInsecure(Boolean insecure) {
        draft.insecure = insecure;
        refreshTlsModeItems();
        changed();
    }

    /** Replaces the content with an imported request (keeps this tab's name and link). */
    public void applyImported(RequestDef r, boolean insecure) {
        String name = draft.name;
        draft = r.copy();
        draft.name = isSaved() ? name : r.name;
        if (insecure) draft.insecure = Boolean.TRUE;
        loadIntoUi();
        updateState();
        focusUrl();
    }

    public void focusUrl() {
        SwingUtilities.invokeLater(() -> {
            urlField.requestFocusInWindow();
            urlField.selectAll();
        });
    }

    public void showFind() {
        response.showFind();
    }

    public void refreshVariables() {
        urlField.refreshVariables();
        authUser.refreshVariables();
        authPass.refreshVariables();
        authToken.refreshVariables();
        VarSupport vs = VarSupport.of(bodyEditor);
        if (vs != null) vs.refresh();
        paramsTable.refreshVariables();
        headersTable.refreshVariables();
        formTable.refreshVariables();
        multipartTable.refreshVariables();
        bodyFile.refreshVariables();
    }

    public void settingsChanged() {
        refreshTlsModeItems();
        updateState();
    }

    public double dividerRatio() {
        int h = split.getHeight();
        return h <= 0 ? 0.45 : (double) split.getDividerLocation() / h;
    }

    public void setDividerRatio(double r) {
        SwingUtilities.invokeLater(() -> split.setDividerLocation(Math.max(0.15, Math.min(0.85, r))));
    }

    VariableResolver resolver() {
        VariableResolver r = ctx.resolverFor(collectionId);
        return localsTable == null ? r : r.withLocals(localsTable.getRows());
    }

    public void copyAsCurl(boolean resolved) {
        readUi();
        String cmd = CurlExporter.export(draft, resolved ? resolver() : null, ctx.effectiveInsecure(draft));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
        frame.status(resolved ? "Copied as cURL" : "Copied as cURL with {{variables}} kept", null, null);
    }

    public void copyAsCode(dev.restclient.codegen.CodeGen.Language language) {
        readUi();
        String code = dev.restclient.codegen.CodeGen.generate(language, draft, resolver(), ctx.effectiveInsecure(draft));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
        frame.status("Copied as " + language.label, null, null);
    }

    // ---- sending ---------------------------------------------------------------------------

    public boolean isSending() {
        return call != null;
    }

    public void send() {
        if (call != null) {
            cancel();
            return;
        }
        paramsTable.stopEditing();
        headersTable.stopEditing();
        formTable.stopEditing();
        multipartTable.stopEditing();
        capturesTable.stopEditing();
        localsTable.stopEditing();
        readUi();
        // recording: {{$guid}} and friends get their values here, and the copy kept for display reuses them
        VariableResolver vars = resolver().recording();
        PreparedRequest p;
        try {
            p = PreparedRequest.prepare(draft, vars);
        } catch (PreparedRequest.InvalidRequestException e) {
            if (e.urlProblem) {
                // Point at the thing to fix: outline the URL and put the cursor there.
                requestGroup.putClientProperty("JComponent.outline", "error");
                requestGroup.repaint();
                urlField.requestFocusInWindow();
            } else {
                editorTabs.setSelectedIndex(2);
            }
            response.showError(ResponseData.failed(ResponseData.ErrorKind.INVALID, null, e.getMessage(), 0, null),
                    errorActions(), ctx.timeoutFor(draft), ctx.tls, List.of());
            return;
        }
        RequestDef sent = draft.copy();
        PreparedRequest shown = PreparedRequest.prepareMasked(sent, vars.replaying(), p);
        boolean insecure = ctx.effectiveInsecure(draft);
        call = ctx.http.send(p, insecure, ctx.timeoutFor(draft));
        setSending(true);
        response.showLoading(this::cancel);
        HttpService.Call mine = call;
        call.result().thenAccept(res -> SwingUtilities.invokeLater(() -> {
            if (call != mine) return;
            done(p, shown, sent, res, vars);
        }));
    }

    public void cancel() {
        if (call != null) call.cancel();
    }

    private void setSending(boolean sending) {
        sendButton.setText(sending ? "Cancel" : "Send");
        sendButton.setToolTipText(sending ? "Cancel (Esc)" : "Send (" + Keys.label(Keys.SEND) + ")");
        frame.tabStateChanged(this);
    }

    private void done(PreparedRequest p, PreparedRequest shown, RequestDef sent, ResponseData res, VariableResolver vars) {
        call = null;
        setSending(false);
        List<String> notes = new ArrayList<>(p.warnings);
        if (res.errorKind == ResponseData.ErrorKind.CANCELLED) {
            response.showMessage("Request cancelled", "Send it again with " + Keys.label(Keys.SEND) + ".");
            return;
        }
        if (res.isError()) {
            response.showError(res, errorActions(), ctx.timeoutFor(draft), ctx.tls, notes);
        } else {
            lastResponse = res;
            List<dev.restclient.capture.Capture.Outcome> captured = dev.restclient.capture.Capture.run(sent.captures, res);
            notes.addAll(frame.storeCaptures(collectionId, captured));
            response.setSent(shown.method.name(), shown.url, shown.headers, shown.body);
            response.showResponse(res, notes, fileNameFor(p.uri));
        }
        frame.recordHistory(historyEntry(sent, shown, res, vars));
    }

    private ResponsePanel.ErrorActions errorActions() {
        return new ResponsePanel.ErrorActions() {
            @Override
            public void trust(ResponseData r) {
                frame.trustCertificate(RequestTab.this, r);
            }

            @Override
            public void retryInsecure() {
                setInsecure(Boolean.TRUE);
                send();
            }

            @Override
            public void retry() {
                send();
            }
        };
    }

    private static String fileNameFor(URI uri) {
        if (uri == null || uri.getPath() == null) return "response";
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        last = last.replaceAll("[^A-Za-z0-9._-]", "");
        return last.isEmpty() ? "response" : last;
    }

    private HistoryEntry historyEntry(RequestDef sent, PreparedRequest masked, ResponseData res, VariableResolver vars) {
        HistoryEntry h = new HistoryEntry();
        h.timestamp = System.currentTimeMillis();
        h.request = sent.copy();
        h.sentMethod = masked.method;
        h.sentUrl = masked.url;
        h.sentHeaders = masked.headers;
        h.sentBody = masked.body;
        h.environmentName = vars.environmentName();
        h.sourceName = isSaved() ? draft.name : null;
        h.elapsedMs = res.elapsedMs;
        if (res.isError()) {
            h.errorKind = res.errorKind.name();
            h.errorMessage = res.errorMessage;
        } else {
            h.status = res.status;
            h.sizeBytes = res.body.length;
            h.responseHeaders = new ArrayList<>(res.headers);
            h.contentType = res.contentType();
            int limit = 32 * 1024;
            if (res.isBinary()) {
                h.responseBody = "(binary content, " + Formatters.size(res.body.length) + ")";
            } else if (res.body.length > limit) {
                h.responseBody = new String(res.body, 0, limit, StandardCharsets.UTF_8);
                h.responseTruncated = true;
            } else {
                h.responseBody = res.bodyText();
            }
        }
        return h;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void beautify() {
        BodyType t = BodyType.values()[Math.max(0, bodyType.selectedIndex())];
        String text = bodyEditor.getText();
        String out = t == BodyType.JSON ? beautifyJsonWithVariables(text) : Formatters.prettyXml(text);
        if (out == null) {
            frame.status("Couldn't format: the body isn't valid " + (t == BodyType.JSON ? "JSON" : "XML"), null, null);
            return;
        }
        bodyEditor.setText(out);
        bodyEditor.setCaretPosition(0);
    }

    /** Formats JSON that may contain {{variables}} inside or outside strings. */
    static String beautifyJsonWithVariables(String text) {
        List<String> originals = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (inString && ch == '\\' && i + 1 < text.length()) {
                sb.append(ch).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch == '"') inString = !inString;
            if (ch == '{' && text.startsWith("{{", i)) {
                int end = text.indexOf("}}", i + 2);
                if (end > 0) {
                    String token = text.substring(i, end + 2);
                    int n = originals.size();
                    originals.add(token);
                    sb.append(inString ? "__rcvar_" + n + "__" : "\"__rcvarq_" + n + "__\"");
                    i = end + 2;
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
        String pretty = Formatters.prettyJson(sb.toString());
        if (pretty == null) return null;
        for (int n = 0; n < originals.size(); n++) {
            pretty = pretty.replace("\"__rcvarq_" + n + "__\"", originals.get(n)).replace("__rcvar_" + n + "__", originals.get(n));
        }
        return pretty;
    }

    private static List<String> suggest(List<String> options, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        return out;
    }

    private List<String> headerValueSuggestions(String key, String text) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "content-type", "accept" -> suggest(MEDIA_TYPES, text);
            case "cache-control" -> suggest(List.of("no-cache", "no-store", "max-age=0"), text);
            case "accept-encoding" -> suggest(List.of("gzip, deflate", "gzip", "deflate", "identity"), text);
            case "authorization" -> suggest(List.of("Bearer {{token}}", "Basic "), text);
            default -> List.of();
        };
    }
}
