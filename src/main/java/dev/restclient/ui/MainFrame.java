package dev.restclient.ui;

import dev.restclient.curl.CurlExporter;
import dev.restclient.curl.CurlParseException;
import dev.restclient.curl.CurlParser;
import dev.restclient.http.ResponseData;
import dev.restclient.model.ApiCollection;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HistoryEntry;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Session;
import dev.restclient.model.Settings;
import dev.restclient.store.AppPaths;
import dev.restclient.model.Variable;
import dev.restclient.vars.VariableResolver;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultEditorKit;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The main window: toolbar, sidebar, request tabs, status bar, menus and shortcuts. */
public class MainFrame extends JFrame implements VarSupport.Actions {
    private final AppContext ctx;
    private final Sidebar sidebar;
    private final JSplitPane split;
    /** Light/dark switch in the toolbar; right-click for "Match System". */
    private final JButton themeToggle = new JButton();
    private final CardLayout contentCards = new CardLayout();
    private final JPanel content = new JPanel(contentCards);
    private final TabStrip tabs = new TabStrip();
    private final StatusBar statusBar;
    /** Environment picker; draws its own chevron because "▾" is missing from many UI fonts. */
    private final JButton envButton = new JButton() {
        private final Icon chevron = new dev.restclient.ui.laf.LafIcons.Chevron(
                dev.restclient.ui.laf.LafIcons.Direction.DOWN, 12, Theme::secondary);

        @Override
        public java.awt.Insets getInsets() {
            java.awt.Insets i = super.getInsets();
            return new java.awt.Insets(i.top, i.left, i.bottom, i.right + chevron.getIconWidth() + 6);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            java.awt.Insets i = super.getInsets();
            chevron.paintIcon(this, g, getWidth() - i.right - chevron.getIconWidth(), (getHeight() - chevron.getIconHeight()) / 2);
        }
    };
    private final JLabel breadcrumb = new JLabel();
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final LinkedList<String> recent = new LinkedList<>();
    private int sidebarWidth = 280;
    private boolean sidebarVisible = true;

    public MainFrame(AppContext ctx) {
        super("REST Client");
        this.ctx = ctx;
        ctx.setFrame(this);
        setIconImages(Theme.appIcons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(820, 560));

        if (Platform.isMac()) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        }

        statusBar = new StatusBar(ctx, () -> openSettings(SettingsDialog.CERTIFICATES));
        sidebar = new Sidebar(ctx, this);
        buildTabs();
        content.add(new EmptyStatePanel(this), "empty");
        content.add(tabs, "tabs");

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, content);
        split.setDividerSize(1);
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setDividerLocation(sidebarWidth);

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);

        buildActions();
        WindowChrome.install(this, buildMenuBar());
        installMacHandlers();

        ctx.repo.addListener(change -> SwingUtilities.invokeLater(() -> {
            switch (change) {
                case COLLECTIONS -> sidebar.collections().rebuild();
                case ENVIRONMENTS -> {
                    updateEnvButton();
                    refreshVariables();
                }
                case HISTORY -> sidebar.history().rebuild();
                case SETTINGS -> refreshStatus();
            }
        }));
        Theme.onChange(() -> {
            updateEnvButton();
            refreshStatus();
            repaint();
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                // Start where work happens: the URL of the open request, not the sidebar filter.
                RequestTab t = currentRequestTab();
                if (t != null) t.focusUrl();
                else {
                    content.setFocusable(true);
                    content.requestFocusInWindow();
                }
            }

            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });
        new Timer(30_000, e -> saveSession()).start();
        watchSystemAppearance();
        Themes.watch();
        Themes.onChange(this::themesChanged);

        restoreSession();
        syncAppearanceControls();
        updateEnvButton();
        updateContentCard();
        for (String w : ctx.repo.loadWarnings()) status(w, null, null);
    }

    // ---- layout ----------------------------------------------------------------------------

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.toolbarBg());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.separator());
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        bar.setBorder(Ui.pad(Platform.isMac() ? 8 : 6, Platform.isMac() ? 80 : 10, Platform.isMac() ? 8 : 6, 12));
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(Ui.toolbarButton(Icons.get("sidebar"), "Toggle sidebar (" + Keys.label(Keys.TOGGLE_SIDEBAR) + ")", e -> toggleSidebar()));
        breadcrumb.setFont(breadcrumb.getFont().deriveFont(Font.BOLD));
        left.add(breadcrumb);
        bar.add(left, BorderLayout.WEST);

        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        envButton.addActionListener(e -> showEnvironmentMenu());
        envButton.setRequestFocusEnabled(false);
        envButton.setToolTipText("Switch environment (" + Keys.label(Keys.SWITCH_ENV) + ")");
        themeToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        themeToggle.setRequestFocusEnabled(false);
        themeToggle.addActionListener(e -> toggleAppearance());
        themeToggle.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) appearanceMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) appearanceMenu(e);
            }
        });
        right.add(themeToggle);
        right.add(envButton);
        JButton palette = new JButton("Search or run a command") {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                int hintW = getComponentCount() > 0 ? getComponent(0).getPreferredSize().width : 0;
                return new Dimension(Math.max(Theme.scaled(280), d.width + hintW + 16), d.height);
            }
        };
        palette.setIcon(Icons.get("search", 14));
        palette.setHorizontalAlignment(JButton.LEFT);
        palette.addActionListener(e -> openPalette());
        palette.setLayout(new BorderLayout());
        JLabel hint = Ui.small(Keys.label(Keys.PALETTE) + " ");
        palette.add(hint, BorderLayout.EAST);
        palette.setRequestFocusEnabled(false);
        right.add(palette);
        bar.add(right, BorderLayout.EAST);

        if (Platform.isMac()) {
            // Dragging the unified toolbar moves the window, like a native title bar.
            MouseAdapter drag = new MouseAdapter() {
                private java.awt.Point start;

                @Override
                public void mousePressed(MouseEvent e) {
                    start = e.getPoint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (start == null || (getExtendedState() & MAXIMIZED_BOTH) != 0) return;
                    java.awt.Point p = getLocation();
                    setLocation(p.x + e.getX() - start.x, p.y + e.getY() - start.y);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) setExtendedState(getExtendedState() ^ MAXIMIZED_BOTH);
                }
            };
            bar.addMouseListener(drag);
            bar.addMouseMotionListener(drag);
        }
        return bar;
    }

    private void buildTabs() {
        tabs.setCloseHandler(this::closeTab);
        tabs.setTabMenuProvider(this::tabMenu);
        JButton plus = Ui.toolbarButton(Icons.get("plus", 14), "New tab (" + Keys.label(Keys.NEW_TAB) + ")", e -> newTab());
        tabs.setTrailingComponent(plus);
        tabs.addChangeListener(e -> updateBreadcrumb());
    }

    /** Custom tab label: colored method, name and an unsaved-changes dot. */
    private static final class TabHeader extends JPanel {
        private final JLabel method = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel dot = new JLabel(Ui.dot(Theme::accent, 7));

        TabHeader() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS));
            setOpaque(false);
            method.setFont(Theme.mono(11f).deriveFont(Font.BOLD));
            add(method);
            add(Ui.hgap(6));
            // an unnamed tab shows its URL path, which is the user's text: never rendered as HTML
            title.putClientProperty("html.disable", Boolean.TRUE);
            add(title);
            add(Ui.hgap(6));
            add(dot);
        }

        void update(HttpMethod m, String name, boolean dirty, boolean history) {
            method.setText(m == null ? "" : m.shortLabel());
            method.setForeground(Theme.methodColor(m));
            String n = name == null || name.isBlank() ? "Untitled" : name;
            title.setText(n.length() > 28 ? n.substring(0, 27) + "…" : n);
            title.setFont(title.getFont().deriveFont(history ? Font.ITALIC : Font.PLAIN));
            dot.setVisible(dirty);
            revalidate();
        }
    }

    private void updateContentCard() {
        contentCards.show(content, tabs.getTabCount() == 0 ? "empty" : "tabs");
        updateBreadcrumb();
    }

    /**
     * Keeps commands honest: Save is only offered when there's something to save (an edited request, or one that
     * was never saved), and request commands are off when no request is open. A command that's available but
     * does nothing suggests the wrong thing.
     */
    private void updateActionStates() {
        if (actions.isEmpty()) return;
        RequestTab t = currentRequestTab();
        boolean req = t != null;
        enable("save", req && (t.isDirty() || !t.isSaved()));
        enable("saveAs", req);
        for (String id : new String[]{"send", "copyCurl", "copyCurlVars", "focusUrl", "toggleTls", "find", "duplicateTab"}) {
            enable(id, req);
        }
        enable("close", tabs.getTabCount() > 0);
        enable("closeOthers", tabs.getTabCount() > 1);
        enable("closeAll", tabs.getTabCount() > 0);
        enable("nextTab", tabs.getTabCount() > 1);
        enable("prevTab", tabs.getTabCount() > 1);
        enable("reopenTab", !closedTabs.isEmpty());
    }

    private void enable(String id, boolean on) {
        javax.swing.Action a = actions.get(id);
        if (a != null) a.setEnabled(on);
    }

    private void updateBreadcrumb() {
        updateActionStates();
        Component c = tabs.getSelectedComponent();
        String text = "";
        if (c instanceof RequestTab t) {
            ApiCollection col = ctx.repo.collection(t.collectionId());
            text = col != null && t.nodeId() != null ? col.pathTo(t.nodeId()).replace(" › ", "  /  ") : "Not saved to a collection";
            setTitle(t.displayTitle() + " — REST Client");
        } else if (c instanceof HistoryTab) {
            text = "History";
            setTitle("History — REST Client");
        } else {
            setTitle("REST Client");
        }
        breadcrumb.setText(text);
        // A path is information (strong); "not saved" is a status (quiet), so they don't look alike.
        boolean status = text.startsWith("Not saved");
        Theme.fg(breadcrumb, status ? Theme::secondary : Theme::text);
        breadcrumb.setFont(breadcrumb.getFont().deriveFont(status ? Font.PLAIN : Font.BOLD));
    }

    private Motion.Handle sidebarAnim;

    /** Shows or hides the sidebar, sliding it in or out. */
    private void toggleSidebar() {
        if (sidebarAnim != null) {
            sidebarAnim.stop();
            sidebarAnim = null;
        }
        if (sidebarVisible) {
            sidebarWidth = split.getDividerLocation();
            int from = sidebarWidth;
            sidebarVisible = false;
            if (!Motion.enabled()) {
                sidebar.setVisible(false);
                return;
            }
            sidebarAnim = Motion.animate(180, t -> split.setDividerLocation((int) Math.round(from * (1 - t))), () -> {
                sidebar.setVisible(false);
                sidebarAnim = null;
            });
        } else {
            sidebarVisible = true;
            sidebar.setVisible(true);
            if (!Motion.enabled()) {
                split.setDividerLocation(sidebarWidth);
                return;
            }
            int to = sidebarWidth;
            split.setDividerLocation(0);
            sidebarAnim = Motion.animate(200, t -> split.setDividerLocation((int) Math.round(to * t)), () -> {
                split.setDividerLocation(to);
                sidebarAnim = null;
            });
        }
    }

    public void showSidebar(String tab) {
        if (!sidebarVisible) toggleSidebar();
        sidebar.showTab(tab);
    }

    // ---- environment picker ----------------------------------------------------------------

    private void updateEnvButton() {
        Environment env = ctx.activeEnvironment();
        Icon dot = Ui.dot(() -> env == null ? null : Theme.hex(env.color, Theme.accent()), 9);
        envButton.setIcon(dot);
        String sec = toHex(Theme.secondary());
        envButton.setText("<html><span style='color:" + sec + "'>Environment</span>&nbsp; <b>"
                + Ui.escape(env == null ? "None" : env.name) + "</b></html>");
    }

    private static String toHex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void showEnvironmentMenu() {
        JPopupMenu m = new JPopupMenu();
        ButtonGroup g = new ButtonGroup();
        Environment active = ctx.activeEnvironment();
        JRadioButtonMenuItem none = new JRadioButtonMenuItem("No environment", active == null);
        none.addActionListener(e -> switchEnvironment(null));
        g.add(none);
        m.add(none);
        for (Environment env : ctx.repo.environments()) {
            JRadioButtonMenuItem i = new JRadioButtonMenuItem(env.name, Ui.dot(() -> Theme.hex(env.color, Theme.accent()), 9), env == active);
            i.addActionListener(e -> switchEnvironment(env));
            g.add(i);
            m.add(i);
        }
        m.addSeparator();
        JMenuItem manage = new JMenuItem("Manage Environments…");
        manage.addActionListener(e -> showEnvironments(null, null, false));
        m.add(manage);
        if (envButton.isShowing()) Ui.showBelow(m, envButton);
    }

    private void switchEnvironment(Environment env) {
        ctx.repo.setActiveEnvironment(env);
        status(env == null ? "No environment selected" : "Switched to " + env.name, null, null);
    }

    public void showEnvironments(String scopeId, String varName, boolean define) {
        EnvironmentDialog.open(this, ctx, scopeId, varName, define);
    }

    @Override
    public void editVariable(String name) {
        showEnvironments(null, name, false);
    }

    @Override
    public void defineVariable(String name) {
        showEnvironments(null, name, true);
    }

    public VariableResolver currentResolver() {
        RequestTab t = currentRequestTab();
        return ctx.resolverFor(t == null ? null : t.collectionId());
    }

    public void variablesChanged() {
        updateEnvButton();
        refreshVariables();
    }

    private void refreshVariables() {
        for (RequestTab t : requestTabs()) t.refreshVariables();
    }

    // ---- tabs ------------------------------------------------------------------------------

    private List<RequestTab> requestTabs() {
        List<RequestTab> out = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getComponentAt(i) instanceof RequestTab t) out.add(t);
        return out;
    }

    public RequestTab currentRequestTab() {
        return tabs.getSelectedComponent() instanceof RequestTab t ? t : null;
    }

    private RequestTab addTab(RequestDef r, String collectionId, String nodeId, RequestDef saved, boolean select) {
        RequestTab t = new RequestTab(ctx, this, r, collectionId, nodeId, saved);
        tabs.addTab(r.name, t);
        int i = tabs.indexOfComponent(t);
        tabs.setTabComponentAt(i, new TabHeader());
        tabStateChanged(t);
        if (select) tabs.setSelectedIndex(i);
        updateContentCard();
        return t;
    }

    public void tabStateChanged(RequestTab t) {
        int i = tabs.indexOfComponent(t);
        if (i < 0) return;
        if (tabs.getTabComponentAt(i) instanceof TabHeader h) h.update(t.method(), t.displayTitle(), t.isDirty(), false);
        updateActionStates();
        tabs.setToolTipTextAt(i, t.displayTitle() + (t.isDirty() ? " — unsaved changes" : ""));
        if (tabs.getSelectedIndex() == i) updateBreadcrumb();
    }

    public void newTab() {
        RequestDef r = new RequestDef();
        r.name = "Untitled request";
        addTab(r, currentCollectionIdForNew(), null, null, true).focusUrl();
    }

    private String currentCollectionIdForNew() {
        RequestTab t = currentRequestTab();
        return t == null ? null : t.collectionId();
    }

    /** Recently closed request tabs, newest first, so a mistaken close is one shortcut away from undone. */
    private final java.util.Deque<ClosedTab> closedTabs = new java.util.ArrayDeque<>();

    private record ClosedTab(RequestDef draft, String collectionId, String nodeId, RequestDef saved, int index) {
    }

    private void rememberClosed(RequestTab t, int index) {
        closedTabs.push(new ClosedTab(t.draft().copy(), t.collectionId(), t.nodeId(), t.savedCopy(), index));
        while (closedTabs.size() > 20) closedTabs.removeLast();
    }

    /** Reopens the most recently closed tab where it was, including any edits it had when it closed. */
    public void reopenClosedTab() {
        ClosedTab c = closedTabs.poll();
        if (c == null) {
            status("No recently closed tabs", null, null);
            return;
        }
        // If the saved request was deleted meanwhile, reopen it as an unsaved copy.
        String nodeId = c.nodeId();
        String colId = c.collectionId();
        ApiCollection col = ctx.repo.collection(colId);
        if (nodeId != null && (col == null || col.find(nodeId) == null)) nodeId = null;
        RequestTab t = addTab(c.draft(), colId, nodeId, nodeId == null ? null : c.saved(), true);
        int at = Math.min(c.index(), tabs.getTabCount() - 1);
        tabs.moveTab(tabs.indexOfComponent(t), at);
        tabs.setSelectedIndex(at);
        updateActionStates();
    }

    public void closeTab(int i) {
        if (i < 0 || i >= tabs.getTabCount()) return;
        Component c = tabs.getComponentAt(i);
        if (c instanceof RequestTab t) {
            if (t.isDirty()) {
                tabs.setSelectedIndex(i);
                Ui.SaveChoice choice = Ui.askSave(this, t.title());
                if (choice == Ui.SaveChoice.CANCEL) return;
                if (choice == Ui.SaveChoice.SAVE && !saveTab(t, false)) return;
            }
            t.cancel();
            rememberClosed(t, i);
        }
        tabs.removeTabAt(i);
        updateContentCard();
        focusAfterClose();
    }

    /** Keyboard focus lands somewhere useful after closing tabs, so shortcuts keep working. */
    private void focusAfterClose() {
        RequestTab cur = currentRequestTab();
        if (cur != null) cur.focusUrl();
        else {
            content.setFocusable(true);
            content.requestFocusInWindow();
        }
    }

    /** Right-click menu for a tab: everything you'd do to tabs, next to the tab it acts on. */
    private javax.swing.JPopupMenu tabMenu(int index) {
        if (index < 0 || index >= tabs.getTabCount()) return null;
        tabs.setSelectedIndex(index);
        RequestTab t = tabs.getComponentAt(index) instanceof RequestTab rt ? rt : null;
        int n = tabs.getTabCount();
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        m.add(menuItem("New Tab", Keys.NEW_TAB, true, this::newTab));
        m.add(menuItem("Duplicate Tab", null, t != null, () -> duplicateTab(t)));
        m.addSeparator();
        m.add(menuItem("Save", Keys.SAVE, t != null && (t.isDirty() || !t.isSaved()), () -> saveTab(t, false)));
        m.add(menuItem("Rename…", null, t != null, () -> t.rename()));
        m.add(menuItem("Copy as cURL", Keys.COPY_CURL, t != null, () -> t.copyAsCurl(true)));
        m.addSeparator();
        m.add(menuItem("Close", Keys.CLOSE_TAB, true, () -> closeTab(index)));
        m.add(menuItem("Close Other Tabs", null, n > 1, () -> closeTabs(i -> i != index)));
        m.add(menuItem("Close Tabs to the Left", null, index > 0, () -> closeTabs(i -> i < index)));
        m.add(menuItem("Close Tabs to the Right", null, index < n - 1, () -> closeTabs(i -> i > index)));
        boolean anySaved = false;
        for (RequestTab x : requestTabs()) anySaved |= !x.isDirty();
        m.add(menuItem("Close Unmodified Tabs", null, anySaved, () -> closeTabs(i ->
                tabs.getComponentAt(i) instanceof RequestTab x && !x.isDirty())));
        m.add(menuItem("Close All Tabs", null, true, () -> closeTabs(i -> true)));
        m.addSeparator();
        m.add(menuItem("Reopen Closed Tab", Keys.REOPEN_TAB, !closedTabs.isEmpty(), this::reopenClosedTab));
        return m;
    }

    private static JMenuItem menuItem(String text, KeyStroke shortcut, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (shortcut != null) item.setAccelerator(shortcut);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * Closes every tab whose index matches. Unsaved changes are asked about once for all of them (Save All /
     * Don't Save / Cancel) rather than once per tab.
     */
    public void closeTabs(java.util.function.IntPredicate which) {
        List<Component> targets = new ArrayList<>();
        List<RequestTab> dirty = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (!which.test(i)) continue;
            Component c = tabs.getComponentAt(i);
            targets.add(c);
            if (c instanceof RequestTab t && t.isDirty()) dirty.add(t);
        }
        if (targets.isEmpty()) return;
        if (dirty.size() == 1) {
            RequestTab t = dirty.get(0);
            tabs.setSelectedIndex(tabs.indexOfComponent(t));
            Ui.SaveChoice choice = Ui.askSave(this, t.title());
            if (choice == Ui.SaveChoice.CANCEL) return;
            if (choice == Ui.SaveChoice.SAVE && !saveTab(t, false)) return;
        } else if (dirty.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < Math.min(5, dirty.size()); i++) names.append("<br>• ").append(Ui.escape(dirty.get(i).title()));
            if (dirty.size() > 5) names.append("<br>• and ").append(dirty.size() - 5).append(" more");
            Object[] options = {"Save All", Platform.isLinux() ? "Discard All" : "Don't Save", "Cancel"};
            int r = Ui.option(this, "<html><b>" + dirty.size() + " tabs have unsaved changes.</b>" + names
                    + "<br><br>Save them before closing?</html>", "Unsaved changes",
                    javax.swing.JOptionPane.QUESTION_MESSAGE, options, options[0]);
            if (r == 2 || r < 0) return;
            if (r == 0) {
                for (RequestTab t : dirty) {
                    tabs.setSelectedIndex(tabs.indexOfComponent(t));
                    if (!saveTab(t, false)) return; // cancelled a Save As: stop, keep the rest open
                }
            }
        }
        for (int k = targets.size() - 1; k >= 0; k--) {
            Component c = targets.get(k);
            int i = tabs.indexOfComponent(c);
            if (c instanceof RequestTab t) {
                t.cancel();
                rememberClosed(t, i);
            }
            if (i >= 0) tabs.removeTabAt(i);
        }
        updateContentCard();
        focusAfterClose();
    }

    private void closeCurrentTab() {
        closeTab(tabs.getSelectedIndex());
    }

    public void duplicateTab(RequestTab t) {
        RequestDef copy = t.draft().duplicate();
        copy.name = t.title() + " copy";
        addTab(copy, t.collectionId(), null, null, true);
    }

    /** Opens a saved request, or selects its tab if already open. */
    public void openSaved(ApiCollection c, CollectionNode n, boolean newTab) {
        if (c == null || n == null || n.request == null) return;
        if (!newTab) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (tabs.getComponentAt(i) instanceof RequestTab t && n.id.equals(t.nodeId())) {
                    tabs.setSelectedIndex(i);
                    touchRecent(n.id);
                    return;
                }
            }
        }
        RequestDef r = n.request.copy();
        r.name = n.name;
        addTab(r, c.id, newTab ? null : n.id, newTab ? null : r, true);
        touchRecent(n.id);
        sidebar.collections().select(n.id);
    }

    private void touchRecent(String nodeId) {
        recent.remove(nodeId);
        recent.addFirst(nodeId);
        while (recent.size() > 8) recent.removeLast();
    }

    public void openImported(RequestDef r, boolean insecure) {
        RequestDef copy = r.copy();
        if (insecure) copy.insecure = Boolean.TRUE;
        addTab(copy, currentCollectionIdForNew(), null, null, true);
    }

    public void importCurlInto(RequestTab tab, String text) {
        try {
            CurlParser.Result res = CurlParser.parse(text);
            tab.applyImported(res.request(), res.insecure());
            int skipped = res.ignored().size() + res.warnings().size();
            status("Imported cURL command" + (skipped > 0 ? " · " + skipped + " option" + (skipped == 1 ? "" : "s") + " need attention" : ""),
                    skipped > 0 ? "Review" : null, skipped > 0 ? () -> CurlImportDialog.open(this, ctx, tab, text) : null);
        } catch (CurlParseException e) {
            CurlImportDialog.open(this, ctx, tab, text);
        }
    }

    public void showCurlImport(String text) {
        CurlImportDialog.open(this, ctx, currentRequestTab(), text);
    }

    // ---- saving ----------------------------------------------------------------------------

    /** Saves a tab; returns false if the user cancelled. */
    /** A starting name for an untitled request: its last path segment ("orders" for /v2/orders?x=1). */
    static String suggestName(RequestDef r) {
        String u = r.url == null ? "" : r.url.replaceFirst("^[a-zA-Z]+://[^/]*", "").replaceFirst("[?#].*$", "");
        String[] parts = u.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (!p.isEmpty() && !p.startsWith("{{")) {
                String name = p.replace('-', ' ').replace('_', ' ');
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        }
        return "";
    }

    public boolean saveTab(RequestTab t, boolean saveAs) {
        RequestDef draft = t.draft();
        CollectionNode node = t.isSaved() ? ctx.repo.node(t.collectionId(), t.nodeId()) : null;
        if (!saveAs && node != null) {
            ApiCollection c = ctx.repo.collection(t.collectionId());
            String keepId = node.request == null ? draft.id : node.request.id;
            node.request = draft.copy();
            node.request.id = keepId;
            node.name = draft.name;
            ctx.repo.saveCollection(c);
            t.markSaved(c.id, node.id);
            status("Saved to " + c.pathTo(node.id), null, null);
            return true;
        }
        if (ctx.repo.collections().isEmpty()) {
            ApiCollection c = new ApiCollection();
            c.name = "My Requests";
            ctx.repo.addCollection(c);
        }
        SaveToDialog.Target target = SaveToDialog.open(this, ctx, draft.name.startsWith("Untitled") ? suggestName(draft) : draft.name,
                saveAs ? "Save Request As" : "Save Request", t.collectionId());
        if (target == null) return false;
        RequestDef r = draft.copy();
        r.id = UUID.randomUUID().toString();
        r.name = target.name();
        CollectionNode n = CollectionNode.request(r);
        target.into().add(n);
        ctx.repo.saveCollection(target.collection());
        t.setRequestName(target.name());
        t.markSaved(target.collection().id, n.id);
        sidebar.collections().expandedIds().add(target.collection().id);
        sidebar.collections().rebuild();
        sidebar.collections().select(n.id);
        status("Saved to " + target.collection().pathTo(n.id), null, null);
        return true;
    }

    // ---- collections -----------------------------------------------------------------------

    public void newCollection() {
        String name = Ui.prompt(this, "New Collection", "Collection name", "");
        if (name == null) return;
        ApiCollection c = new ApiCollection();
        c.name = name;
        ctx.repo.addCollection(c);
        showSidebar("collections");
        sidebar.collections().select(c.id);
    }

    public void newRequestIn(ApiCollection c, List<CollectionNode> into) {
        RequestDef r = new RequestDef();
        r.name = "New Request";
        CollectionNode n = CollectionNode.request(r);
        into.add(n);
        ctx.repo.saveCollection(c);
        sidebar.collections().expandedIds().add(c.id);
        sidebar.collections().rebuild();
        sidebar.collections().select(n.id);
        openSaved(c, n, false);
        RequestTab t = currentRequestTab();
        if (t != null) t.focusUrl();
    }

    public void renameCollection(ApiCollection c) {
        String name = Ui.prompt(this, "Rename Collection", "Name", c.name);
        if (name == null) return;
        c.name = name;
        ctx.repo.saveCollection(c);
        updateBreadcrumb();
    }

    public void duplicateCollection(ApiCollection c) {
        ApiCollection copy = new ApiCollection();
        copy.name = c.name + " copy";
        for (Variable v : c.variables) copy.variables.add(v.copy());
        for (CollectionNode n : c.items) copy.items.add(n.duplicate());
        ctx.repo.addCollection(copy);
        sidebar.collections().select(copy.id);
    }

    public void deleteCollection(ApiCollection c) {
        int[] count = {0};
        c.forEachRequest(n -> count[0]++);
        if (!Ui.confirm(this, "Delete Collection", "Delete “" + c.name + "”" + (count[0] > 0 ? " and its " + count[0]
                + " request" + (count[0] == 1 ? "" : "s") : "") + "?", "Delete")) return;
        ctx.repo.deleteCollection(c);
        for (RequestTab t : requestTabs()) if (c.id.equals(t.collectionId())) t.detach();
        status("Deleted “" + c.name + "”", "Undo", () -> ctx.repo.addCollection(c));
    }

    public void renameNode(ApiCollection c, CollectionNode n) {
        String name = Ui.prompt(this, n.folder ? "Rename Folder" : "Rename Request", "Name", n.name);
        if (name == null) return;
        n.name = name;
        if (n.request != null) n.request.name = name;
        ctx.repo.saveCollection(c);
        for (RequestTab t : requestTabs()) if (n.id.equals(t.nodeId()) && n.request != null) t.savedRequestChanged(n.request);
        updateBreadcrumb();
    }

    public void duplicateNode(ApiCollection c, CollectionNode n) {
        CollectionNode copy = n.duplicate();
        copy.name = n.name + " copy";
        if (copy.request != null) copy.request.name = copy.name;
        List<CollectionNode> list = c.parentList(n.id);
        if (list == null) return;
        list.add(list.indexOf(n) + 1, copy);
        ctx.repo.saveCollection(c);
        sidebar.collections().select(copy.id);
    }

    public void deleteNode(ApiCollection c, CollectionNode n) {
        if (c == null) return;
        int[] count = {0};
        walk(n, x -> count[0]++);
        String what = n.folder ? "“" + n.name + "”" + (count[0] > 0 ? " and its " + count[0] + " request" + (count[0] == 1 ? "" : "s") : "")
                : "“" + n.name + "”";
        if (!Ui.confirm(this, n.folder ? "Delete Folder" : "Delete Request", "Delete " + what + "?", "Delete")) return;
        List<CollectionNode> list = c.parentList(n.id);
        if (list == null) return;
        int index = list.indexOf(n);
        list.remove(n);
        ctx.repo.saveCollection(c);
        List<String> ids = new ArrayList<>();
        ids.add(n.id);
        walk(n, x -> ids.add(x.id));
        for (RequestTab t : requestTabs()) if (ids.contains(t.nodeId())) t.detach();
        status("Deleted " + what, "Undo", () -> {
            list.add(Math.min(index, list.size()), n);
            ctx.repo.saveCollection(c);
        });
    }

    private static void collectFolders(List<CollectionNode> items, List<String> out) {
        for (CollectionNode n : items) {
            if (n.folder) {
                out.add(n.id);
                collectFolders(n.children, out);
            }
        }
    }

    private static void walk(CollectionNode n, java.util.function.Consumer<CollectionNode> requestAction) {
        for (CollectionNode ch : n.children) {
            if (!ch.folder && ch.request != null) requestAction.accept(ch);
            walk(ch, requestAction);
        }
    }

    public void copyNodeAsCurl(ApiCollection c, CollectionNode n) {
        if (n.request == null) return;
        String cmd = CurlExporter.export(n.request, ctx.resolverFor(c == null ? null : c.id), ctx.effectiveInsecure(n.request));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
        status("Copied “" + n.name + "” as cURL", null, null);
    }

    // ---- Postman ---------------------------------------------------------------------------

    /** File › Import from Postman…: a collection or an environment file (the kind is detected). */
    public void importPostman() {
        java.io.File f = FileChoosers.openJson(this, "Import from Postman");
        if (f != null) importPostman(f.toPath());
    }

    public void importPostman(java.nio.file.Path file) {
        dev.restclient.postman.Postman.Result r;
        try {
            if (java.nio.file.Files.size(file) > dev.restclient.postman.Postman.MAX_BYTES) {
                Ui.error(this, "Couldn't import", "“" + file.getFileName() + "” is larger than 50 MB.");
                return;
            }
            r = dev.restclient.postman.Postman.read(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (dev.restclient.postman.Postman.ImportException e) {
            Ui.error(this, "Couldn't import “" + file.getFileName() + "”", e.getMessage());
            return;
        } catch (java.io.IOException | RuntimeException e) {
            Ui.error(this, "Couldn't read “" + file.getFileName() + "”", e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        if (r.isCollection()) {
            ApiCollection c = r.collection();
            c.name = uniqueName(c.name, ctx.repo.collections().stream().map(x -> x.name).toList());
            ctx.repo.addCollection(c);
            sidebar.collections().expandedIds().add(c.id);
            showSidebar("collections");
            sidebar.collections().rebuild();
            sidebar.collections().select(c.id);
            String what = "Imported “" + c.name + "”: " + r.requests() + (r.requests() == 1 ? " request" : " requests")
                    + (r.folders() > 0 ? " in " + r.folders() + (r.folders() == 1 ? " folder" : " folders") : "");
            if (r.notes().isEmpty()) {
                status(what, "Undo", () -> ctx.repo.deleteCollection(c));
            } else {
                status(what + " · " + r.notes().size() + (r.notes().size() == 1 ? " thing" : " things") + " to know",
                        "Details", () -> importNotes(c.name, r.notes()));
            }
        } else if (r.isGlobals()) {
            importGlobals(r.environment().variables);
        } else {
            Environment e = r.environment();
            e.name = uniqueName(e.name, ctx.repo.environments().stream().map(x -> x.name).toList());
            ctx.repo.addEnvironment(e);
            variablesChanged();
            status("Imported environment “" + e.name + "” (" + e.variables.size() + (e.variables.size() == 1 ? " variable)" : " variables)"),
                    "Use It", () -> switchEnvironment(e));
        }
    }

    /**
     * Merges a Postman globals file into Globals: new variables are added; for ones that already exist with a
     * different value, the user chooses whether the file's values replace theirs. Undo restores Globals as it was.
     */
    private void importGlobals(List<Variable> incoming) {
        Environment g = ctx.repo.globals();
        List<Variable> before = new java.util.ArrayList<>();
        for (Variable v : g.variables) before.add(copy(v));
        java.util.Map<String, Variable> mine = new java.util.LinkedHashMap<>();
        for (Variable v : g.variables) mine.putIfAbsent(v.key, v);
        List<Variable> added = new java.util.ArrayList<>();
        List<Variable> conflicts = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Variable v : incoming) {
            if (!seen.add(v.key)) continue; // a key repeated in the file: the first one counts
            Variable have = mine.get(v.key);
            if (have == null) added.add(v);
            // an empty secret in the file means "left out of the export", not "set it to nothing": never a conflict
            else if (v.secret && v.value.isEmpty()) continue;
            else if (!have.value.equals(v.value) || have.secret != v.secret || have.enabled != v.enabled) conflicts.add(v);
        }
        if (added.isEmpty() && conflicts.isEmpty()) {
            status(incoming.isEmpty() ? "That globals file has no variables." : "Globals already has everything in that file.", null, null);
            return;
        }
        boolean replace = false;
        if (!conflicts.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < conflicts.size() && i < 6; i++) names.append(i == 0 ? "" : ", ").append(Ui.escape(conflicts.get(i).key));
            if (conflicts.size() > 6) names.append(" and ").append(conflicts.size() - 6).append(" more");
            String n = conflicts.size() == 1 ? "1 variable is" : conflicts.size() + " variables are";
            Object[] options = {"Keep Mine", "Replace With File’s", "Cancel"};
            int choice = Ui.option(this, "<html><div style='width:380px'>" + n + " already in Globals with a different value:<br><b>"
                    + names + "</b><br><br>Keep your values, or replace them with the file’s?"
                    + (added.isEmpty() ? "" : " The " + added.size() + " new " + (added.size() == 1 ? "variable is" : "variables are") + " added either way.")
                    + "</div></html>", "Import Globals", javax.swing.JOptionPane.QUESTION_MESSAGE, options, options[0]);
            if (choice < 0 || choice == 2) return;
            replace = choice == 1;
        }
        if (replace) {
            for (Variable v : conflicts) {
                Variable have = mine.get(v.key);
                have.value = v.value;
                have.secret = v.secret;
                have.enabled = v.enabled;
            }
        }
        for (Variable v : added) g.variables.add(copy(v));
        if (added.isEmpty() && !replace) {
            status("Nothing imported: Globals kept your values.", null, null);
            return;
        }
        ctx.repo.saveEnvironment(g);
        variablesChanged();
        int replaced = replace ? conflicts.size() : 0;
        String what = "Imported into Globals: " + added.size() + " added"
                + (conflicts.isEmpty() ? "" : replace ? ", " + replaced + " replaced" : ", " + conflicts.size() + " kept as they were");
        status(what, "Undo", () -> {
            g.variables.clear();
            g.variables.addAll(before);
            ctx.repo.saveEnvironment(g);
            variablesChanged();
        });
    }

    private static Variable copy(Variable v) {
        Variable c = new Variable(v.key, v.value);
        c.secret = v.secret;
        c.enabled = v.enabled;
        return c;
    }

    private void importNotes(String name, List<String> notes) {
        StringBuilder b = new StringBuilder("<html><div style='width:420px'>");
        for (String n : notes) b.append("• ").append(Ui.escape(n)).append("<br><br>");
        Ui.message(this, b.append("</div></html>").toString(), "Imported “" + name + "”", javax.swing.JOptionPane.INFORMATION_MESSAGE, null);
    }

    /** "Shop API", or "Shop API (2)" if that's taken. */
    static String uniqueName(String name, List<String> taken) {
        if (!taken.contains(name)) return name;
        for (int i = 2; ; i++) if (!taken.contains(name + " (" + i + ")")) return name + " (" + i + ")";
    }

    /** Export to Postman: the given collection, or the selected one (asking if it's unclear). */
    public void exportCollection(ApiCollection c) {
        if (c == null) c = sidebar.collections().selectedCollection();
        if (c == null) {
            RequestTab t = currentRequestTab();
            if (t != null && t.collectionId() != null) c = ctx.repo.collection(t.collectionId());
        }
        if (c == null) {
            List<ApiCollection> all = ctx.repo.collections();
            if (all.isEmpty()) {
                Ui.message(this, "There are no collections to export yet.", "Export to Postman", javax.swing.JOptionPane.INFORMATION_MESSAGE, null);
                return;
            }
            javax.swing.JComboBox<String> pick = new javax.swing.JComboBox<>(all.stream().map(x -> x.name).toArray(String[]::new));
            pick.getAccessibleContext().setAccessibleName("Collection");
            int r = Ui.option(this, new Object[]{"Which collection?", pick}, "Export to Postman",
                    javax.swing.JOptionPane.PLAIN_MESSAGE, new Object[]{"Export…", "Cancel"}, "Export…");
            if (r != 0) return;
            c = all.get(Math.max(0, pick.getSelectedIndex()));
        }
        java.io.File f = FileChoosers.save(this, "Export “" + c.name + "” to Postman",
                dev.restclient.postman.Postman.fileName(c.name, "collection"));
        if (f == null) return;
        writeExport(f, dev.restclient.postman.Postman.writeCollection(c), "Exported “" + c.name + "”");
    }

    /** Export an environment to a Postman environment file, asking whether secret values go in it. */
    public void exportEnvironment(Environment e, java.awt.Component parent) {
        boolean globals = e == ctx.repo.globals();
        boolean include = false;
        if (e.variables.stream().anyMatch(v -> v.secret && !v.value.isEmpty())) {
            Object[] options = {"Leave Secrets Empty", "Include Secrets", "Cancel"};
            int r = Ui.option(parent, "<html><div style='width:360px'>" + (globals ? "Globals" : "“" + Ui.escape(e.name) + "”") + " has secret variables. "
                    + "Include their values in the file?<br><br>Anyone with the file can read them.</div></html>",
                    "Export to Postman", javax.swing.JOptionPane.QUESTION_MESSAGE, options, options[0]);
            if (r < 0 || r == 2) return;
            include = r == 1;
        }
        if (globals) {
            java.io.File f = FileChoosers.save(parent, "Export Globals to Postman", dev.restclient.postman.Postman.fileName("globals", "globals"));
            if (f != null) writeExport(f, dev.restclient.postman.Postman.writeGlobals(e, include), "Exported Globals");
            return;
        }
        java.io.File f = FileChoosers.save(parent, "Export “" + e.name + "” to Postman",
                dev.restclient.postman.Postman.fileName(e.name, "environment"));
        if (f == null) return;
        writeExport(f, dev.restclient.postman.Postman.writeEnvironment(e, include), "Exported environment “" + e.name + "”");
    }

    private void writeExport(java.io.File f, String json, String what) {
        try {
            java.nio.file.Files.writeString(f.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Path dir = f.toPath().toAbsolutePath().getParent();
            status(what + " to " + f.getName(), "Show in Folder", () -> SettingsDialog.reveal(dir));
        } catch (java.io.IOException ex) {
            Ui.error(this, "Couldn't save the file", ex.getMessage());
        }
    }

    public void collectionsChanged() {
        updateBreadcrumb();
    }

    // ---- history ---------------------------------------------------------------------------

    public void recordHistory(HistoryEntry e) {
        ctx.repo.addHistory(e);
    }

    public void showHistoryEntry(HistoryEntry h) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof HistoryTab t && t.entry() == h) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
        // Reuse a single history tab so browsing history doesn't pile up tabs.
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof HistoryTab) {
                tabs.removeTabAt(i);
                break;
            }
        }
        HistoryTab t = new HistoryTab(this, h);
        tabs.addTab(t.title(), t);
        int i = tabs.indexOfComponent(t);
        TabHeader header = new TabHeader();
        header.update(h.sentMethod, t.title(), false, true);
        tabs.setTabComponentAt(i, header);
        tabs.setToolTipTextAt(i, "History · " + new SimpleDateFormat("d MMM HH:mm").format(new Date(h.timestamp)));
        tabs.setSelectedIndex(i);
        updateContentCard();
    }

    public void openFromHistory(HistoryEntry h) {
        RequestDef r = h.request == null ? new RequestDef() : h.request.duplicate();
        r.name = h.sourceName != null ? h.sourceName : "Request from history";
        addTab(r, null, null, null, true);
    }

    public void saveHistoryToCollection(HistoryEntry h) {
        if (h.request == null) return;
        if (ctx.repo.collections().isEmpty()) {
            ApiCollection c = new ApiCollection();
            c.name = "My Requests";
            ctx.repo.addCollection(c);
        }
        SaveToDialog.Target target = SaveToDialog.open(this, ctx, h.sourceName != null ? h.sourceName : "", "Save to Collection", null);
        if (target == null) return;
        RequestDef r = h.request.duplicate();
        r.name = target.name();
        CollectionNode n = CollectionNode.request(r);
        target.into().add(n);
        ctx.repo.saveCollection(target.collection());
        showSidebar("collections");
        sidebar.collections().select(n.id);
        status("Saved to " + target.collection().pathTo(n.id), null, null);
    }

    public void copyHistoryAsCurl(HistoryEntry h) {
        if (h.request == null) return;
        String cmd = CurlExporter.export(h.request, null, Boolean.TRUE.equals(h.request.insecure));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
        status("Copied as cURL", null, null);
    }

    public void clearHistory() {
        int n = ctx.repo.history().size();
        if (n == 0) return;
        if (!Ui.confirm(this, "Clear History", "Remove all " + n + " history entries?", "Clear History")) return;
        List<HistoryEntry> removed = ctx.repo.clearHistory();
        status("History cleared", "Undo", () -> ctx.repo.restoreHistory(removed));
    }

    public void historyChanged() {
        sidebar.history().rebuild();
    }

    // ---- TLS -------------------------------------------------------------------------------

    public void trustCertificate(RequestTab tab, ResponseData r) {
        java.security.cert.X509Certificate[] chain = ctx.tls.lastChain(r.host);
        if (chain == null || chain.length == 0) {
            Ui.error(this, "No certificate to show", "The server's certificate wasn't captured. Try sending the request again.");
            return;
        }
        boolean resend = TrustCertDialog.open(this, ctx, chain, tab.title());
        refreshStatus();
        if (resend) tab.send();
    }

    public void refreshStatus() {
        statusBar.refresh();
    }

    // ---- settings & theme ------------------------------------------------------------------

    public void openSettings(String pane) {
        SettingsDialog.open(this, ctx, pane);
    }

    /** Zoom in (+1), out (-1) or back to actual size (0); the level is saved with the settings. */
    public void zoom(int direction) {
        float z = Theme.zoomStep(direction);
        float ratio = z / Theme.zoom();
        Theme.setZoom(z, true);
        if (sidebarVisible) split.setDividerLocation(Math.round(split.getDividerLocation() * ratio));
        else sidebarWidth = Math.round(sidebarWidth * ratio);
        ctx.settings().zoom = z;
        ctx.repo.saveSettings();
        updateEnvButton();
        refreshStatus();
        status("Zoom " + Math.round(z * 100) + "%", null, null);
    }

    /** Applies the font families in the settings to every open window. */
    public void applyFonts() {
        Settings st = ctx.settings();
        Theme.setFonts(st.uiFontFamily, st.codeFontFamily, true);
        updateEnvButton();
        refreshStatus();
    }

    private void bindExtra(String id, KeyStroke... strokes) {
        javax.swing.InputMap im = getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        for (KeyStroke ks : strokes) im.put(ks, "rc." + id);
        getRootPane().getActionMap().put("rc." + id, actions.get(id));
    }

    /**
     * Follows the OS light/dark setting while the theme is "Match System". Reading it means running a small
     * command (registry, defaults or gsettings), so it's polled off the UI thread every few seconds.
     */
    private void watchSystemAppearance() {
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "appearance-watch");
            t.setDaemon(true);
            return t;
        });
        boolean[] last = {Platform.systemPrefersDark()};
        ex.scheduleWithFixedDelay(() -> {
            boolean now = Platform.systemPrefersDark();
            if (now == last[0]) return;
            last[0] = now;
            SwingUtilities.invokeLater(() -> {
                if (ctx.settings().theme == Settings.ThemeMode.SYSTEM) applyTheme();
            });
        }, 3, 3, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Flips light and dark within the current family: Light and Dark, Solarized Sepia and Solarized Dark, or
     * whatever a theme file names as its {@code toggle}.
     */
    public void toggleAppearance() {
        setAppearance(Themes.toggled(Theme.palette()));
    }

    public void setAppearance(Settings.ThemeMode m) {
        setAppearance(new Themes.Choice(m, null, m.label));
    }

    public void setAppearance(Themes.Choice c) {
        ctx.settings().theme = c.mode();
        if (c.custom() != null) ctx.settings().customTheme = c.custom();
        ctx.repo.saveSettings();
        applyTheme();
    }

    private void appearanceMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        fillAppearance(menu, false);
        Ui.showBelow(menu, themeToggle);
    }

    /** The theme choices (Match System; Light, Dark; Solarized; the user's), then theme-file commands. */
    private void fillAppearance(java.awt.Container menu, boolean withCommands) {
        menu.removeAll();
        ButtonGroup g = new ButtonGroup();
        boolean userStarted = false;
        for (Themes.Choice c : Themes.choices()) {
            boolean user = c.mode() == Settings.ThemeMode.CUSTOM;
            if (c.mode() == Settings.ThemeMode.LIGHT || c.mode() == Settings.ThemeMode.SEPIA || user && !userStarted) {
                menu.add(new javax.swing.JPopupMenu.Separator());
            }
            userStarted |= user;
            JRadioButtonMenuItem i = new JRadioButtonMenuItem(c.label(), c.matches(ctx.settings()));
            i.putClientProperty("html.disable", Boolean.TRUE); // names come from theme files: plain text only
            i.addActionListener(ev -> setAppearance(c));
            g.add(i);
            menu.add(i);
        }
        menu.add(new javax.swing.JPopupMenu.Separator());
        if (withCommands) menu.add(new JMenuItem(actions.get("toggleAppearance")));
        menu.add(new JMenuItem(actions.get("exportTheme")));
        menu.add(new JMenuItem(actions.get("themesFolder")));
    }

    /** Saves the current colours as a theme file the user can edit, then switches to it. */
    public void exportTheme() {
        Theme.Palette p = Theme.palette();
        String name = Ui.prompt(this, "Export Current Theme", "Theme name", p.label + " Custom");
        if (name == null || name.isBlank()) return;
        try {
            java.nio.file.Path f = Themes.export(p, name.strip());
            Themes.reload();
            setAppearance(Themes.choiceFor(Themes.idOf(f)));
            status("Saved " + AppPaths.display(f) + ". Edit it and the app updates as you save.", "Open Folder",
                    () -> SettingsDialog.reveal(Themes.dir()));
        } catch (java.io.IOException ex) {
            Ui.error(this, "Couldn't export the theme", ex.getMessage());
        }
    }

    /** A theme file changed on disk: re-apply if it's the one in use, and keep the controls in step. */
    private void themesChanged() {
        Settings st = ctx.settings();
        if (st.theme == Settings.ThemeMode.CUSTOM) {
            Theme.Palette now = Themes.find(st.customTheme);
            if (now == null) {
                status(ThemeFile.isId(st.customTheme) ? "The theme \"" + st.customTheme + "\" was removed, so Light is in use."
                        : "The chosen theme isn't available, so Light is in use.", null, null);
            }
            Theme.apply(st.theme, st.customTheme, true);
        }
        syncAppearanceControls();
    }

    private void syncAppearanceControls() {
        boolean dark = Theme.dark();
        themeToggle.setIcon(Icons.get(dark ? "sun" : "moon"));
        String next = "Switch to " + Themes.toggled(Theme.palette()).label();
        String tip = next + " (" + Keys.label(Keys.TOGGLE_APPEARANCE) + ")"
                + (ctx.settings().theme == Settings.ThemeMode.SYSTEM ? " · now matching the system" : "")
                + " · right-click for more";
        themeToggle.setToolTipText(tip);
        themeToggle.getAccessibleContext().setAccessibleName(next);
    }

    public void applyTheme() {
        Motion.crossfade(this, () -> Theme.apply(ctx.settings().theme, ctx.settings().customTheme, true));
        syncAppearanceControls();
        updateEnvButton();
        refreshStatus();
    }

    public void settingsChanged() {
        for (RequestTab t : requestTabs()) t.settingsChanged();
        refreshStatus();
    }

    public void status(String text, String actionLabel, Runnable action) {
        statusBar.show(text, actionLabel, action);
    }

    // ---- command palette -------------------------------------------------------------------

    private void openPalette() {
        CommandPalette.open(this, this::paletteItems);
    }

    private List<CommandPalette.Item> paletteItems() {
        List<CommandPalette.Item> out = new ArrayList<>();
        Map<String, CommandPalette.Item> byNode = new LinkedHashMap<>();
        for (ApiCollection c : ctx.repo.collections()) {
            c.forEachRequest(n -> {
                CommandPalette.Item it = new CommandPalette.Item(CommandPalette.Group.REQUESTS, n.name, c.pathTo(n.id),
                        n.request.method, null, null, () -> openSaved(c, n, false), () -> openSaved(c, n, true));
                out.add(it);
                byNode.put(n.id, it);
            });
        }
        for (String id : recent) {
            CommandPalette.Item it = byNode.get(id);
            if (it != null) {
                out.add(new CommandPalette.Item(CommandPalette.Group.RECENT, it.label(), it.detail(), it.method(), null, null,
                        it.run(), it.runAlt()));
            }
        }
        Environment active = ctx.activeEnvironment();
        for (Environment e : ctx.repo.environments()) {
            if (e == active) continue;
            out.add(new CommandPalette.Item(CommandPalette.Group.ENVIRONMENTS, "Switch to " + e.name, null, null,
                    Ui.dot(() -> Theme.hex(e.color, Theme.accent()), 9), null, () -> switchEnvironment(e), null));
        }
        if (active != null) {
            out.add(new CommandPalette.Item(CommandPalette.Group.ENVIRONMENTS, "No environment", null, null,
                    Ui.dot(() -> null, 9), null, () -> switchEnvironment(null), null));
        }
        out.add(new CommandPalette.Item(CommandPalette.Group.ENVIRONMENTS, "Manage Environments…", null, null,
                Icons.get("environment"), null, () -> showEnvironments(null, null, false), null));
        for (Action a : actions.values()) {
            if (Boolean.TRUE.equals(a.getValue("rc.hideInPalette"))) continue;
            if (!a.isEnabled()) continue; // don't offer commands that can't run right now
            out.add(new CommandPalette.Item(CommandPalette.Group.ACTIONS, (String) a.getValue(Action.NAME), null, null,
                    Icons.get("arrow-right", 12), (KeyStroke) a.getValue(Action.ACCELERATOR_KEY),
                    () -> a.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "palette")), null));
        }
        SimpleDateFormat time = new SimpleDateFormat("d MMM HH:mm");
        int n = 0;
        for (HistoryEntry h : ctx.repo.history()) {
            if (n++ > 50) break;
            String path = h.sentUrl == null ? "" : h.sentUrl.replaceFirst("^[a-zA-Z]+://", "");
            out.add(new CommandPalette.Item(CommandPalette.Group.HISTORY, path,
                    (h.status > 0 ? String.valueOf(h.status) : "failed") + " · " + time.format(new Date(h.timestamp)),
                    h.sentMethod, null, null, () -> showHistoryEntry(h), () -> openFromHistory(h)));
        }
        return out;
    }

    // ---- actions & menus -------------------------------------------------------------------

    private Action action(String id, String name, KeyStroke ks, Runnable r) {
        Action a = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
        if (ks != null) a.putValue(Action.ACCELERATOR_KEY, ks);
        actions.put(id, a);
        return a;
    }

    private void withTab(java.util.function.Consumer<RequestTab> c) {
        RequestTab t = currentRequestTab();
        if (t != null) c.accept(t);
    }

    private void buildActions() {
        action("new", "New Request", Keys.NEW_TAB, this::newTab);
        action("newCollection", "New Collection…", Keys.NEW_COLLECTION, this::newCollection);
        action("import", "Import cURL…", Keys.IMPORT_CURL, () -> showCurlImport(null));
        action("importPostman", "Import from Postman…", null, this::importPostman);
        action("exportPostman", "Export Collection to Postman…", null, () -> exportCollection(null));
        action("save", "Save", Keys.SAVE, () -> withTab(t -> saveTab(t, false)));
        action("saveAs", "Save As…", Keys.SAVE_AS, () -> withTab(t -> saveTab(t, true)));
        action("close", "Close Tab", Keys.CLOSE_TAB, this::closeCurrentTab);
        action("settings", Platform.isMac() ? "Settings…" : "Settings", Keys.SETTINGS, () -> openSettings(null));
        action("send", "Send Request", Keys.SEND, () -> withTab(RequestTab::send));
        action("copyCurl", "Copy as cURL", Keys.COPY_CURL, () -> withTab(t -> t.copyAsCurl(true)));
        action("copyCurlVars", "Copy as cURL (keep {{variables}})", null, () -> withTab(t -> t.copyAsCurl(false)));
        action("focusUrl", "Focus URL", Keys.FOCUS_URL, () -> withTab(RequestTab::focusUrl));
        action("toggleTls", "Toggle TLS Verification for Request", null,
                () -> withTab(t -> t.setInsecure(!ctx.effectiveInsecure(t.draft()))));
        action("switchEnv", "Switch Environment…", Keys.SWITCH_ENV, this::showEnvironmentMenu);
        action("manageEnv", "Manage Environments…", null, () -> showEnvironments(null, null, false));
        action("find", "Find in Response", Keys.FIND, () -> withTab(RequestTab::showFind));
        action("sidebar", "Toggle Sidebar", Keys.TOGGLE_SIDEBAR, this::toggleSidebar);
        action("toggleAppearance", "Toggle Light/Dark", Keys.TOGGLE_APPEARANCE, this::toggleAppearance);
        action("zoomIn", "Zoom In", Keys.ZOOM_IN, () -> zoom(1));
        action("zoomOut", "Zoom Out", Keys.ZOOM_OUT, () -> zoom(-1));
        action("zoomReset", "Actual Size", Keys.ZOOM_RESET, () -> zoom(0));
        bindExtra("zoomIn", Keys.ZOOM_IN_ALT);
        bindExtra("zoomOut", Keys.ZOOM_OUT_ALT);
        bindExtra("zoomReset", Keys.ZOOM_RESET_ALT);
        action("showCollections", "Show Collections", null, () -> showSidebar("collections"));
        action("showHistory", "Show History", null, () -> showSidebar("history"));
        action("palette", "Command Palette", Keys.PALETTE, this::openPalette).putValue("rc.hideInPalette", true);
        action("nextTab", "Next Tab", Keys.NEXT_TAB, () -> cycleTab(1));
        action("duplicateTab", "Duplicate Tab", null, () -> withTab(this::duplicateTab));
        action("reopenTab", "Reopen Closed Tab", Keys.REOPEN_TAB, this::reopenClosedTab);
        action("closeOthers", "Close Other Tabs", null, () -> {
            int keep = tabs.getSelectedIndex();
            closeTabs(i -> i != keep);
        });
        action("closeAll", "Close All Tabs", null, () -> closeTabs(i -> true));
        action("prevTab", "Previous Tab", Keys.PREV_TAB, () -> cycleTab(-1));
        action("exportTheme", "Export Current Theme…", null, this::exportTheme);
        action("themesFolder", "Open Themes Folder", null, () -> {
            Themes.watch();
            SettingsDialog.reveal(Themes.dir());
        });
        action("dataFolder", "Open Data Folder", null, () -> SettingsDialog.reveal(ctx.repo.paths().base()));
        action("certsFolder", "Open Certificates Folder", null, () -> SettingsDialog.reveal(ctx.tls.certsDir()));
        action("certificates", "Certificates…", null, () -> openSettings(SettingsDialog.CERTIFICATES));
        action("clearHistory", "Clear History…", null, this::clearHistory);
        action("shortcuts", "Keyboard Shortcuts", null, this::showShortcuts);
        action("quickStart", "Quick Start", null, this::showQuickStart);
        action("addSample", "Add Sample Collection", null, this::addSampleCollection);
        action("undo", "Undo", Keys.UNDO, () -> VarSupport.undo(focusOwner())).putValue("rc.hideInPalette", true);
        action("redo", "Redo", Keys.REDO, () -> VarSupport.redo(focusOwner())).putValue("rc.hideInPalette", true);
        action("about", "About REST Client", null, this::showAbout);
        action("quit", Platform.isMac() ? "Quit REST Client" : "Exit", null, this::quit)
                .putValue("rc.hideInPalette", true);
    }

    private Component focusOwner() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    }

    private void cycleTab(int delta) {
        int n = tabs.getTabCount();
        if (n == 0) return;
        tabs.setSelectedIndex((tabs.getSelectedIndex() + delta + n) % n);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        add(file, "new", "newCollection", null, "import", "importPostman", "exportPostman", null, "save", "saveAs", null, "close");
        if (!Platform.isMac()) add(file, null, "settings", null, "quit");
        JMenu edit = new JMenu("Edit");
        add(edit, "undo", "redo", null);
        edit.add(textAction(new DefaultEditorKit.CutAction(), "Cut", Keys.mod(java.awt.event.KeyEvent.VK_X)));
        edit.add(textAction(new DefaultEditorKit.CopyAction(), "Copy", Keys.mod(java.awt.event.KeyEvent.VK_C)));
        edit.add(textAction(new DefaultEditorKit.PasteAction(), "Paste", Keys.mod(java.awt.event.KeyEvent.VK_V)));
        edit.add(textAction(new javax.swing.text.TextAction("select-all") {
            @Override
            public void actionPerformed(ActionEvent e) {
                javax.swing.text.JTextComponent c = getFocusedComponent();
                if (c != null) c.selectAll();
            }
        }, "Select All", Keys.mod(java.awt.event.KeyEvent.VK_A)));
        add(edit, null, "find");
        JMenu view = new JMenu("View");
        add(view, "sidebar", "showCollections", "showHistory", null, "zoomIn", "zoomOut", "zoomReset", null);
        JMenu appearance = new JMenu("Appearance");
        // rebuilt each time it opens, so new and edited theme files show up
        fillAppearance(appearance.getPopupMenu(), true);
        appearance.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                fillAppearance(appearance.getPopupMenu(), true);
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });
        view.add(appearance);
        JMenu request = new JMenu("Request");
        add(request, "send", null, "copyCurl", "copyCurlVars", null, "focusUrl", "toggleTls", null, "switchEnv", "manageEnv", null,
                "certificates", "clearHistory");
        JMenu window = new JMenu("Window");
        add(window, "palette", null, "nextTab", "prevTab", null, "duplicateTab", "reopenTab", null, "closeOthers", "closeAll");
        JMenu help = new JMenu("Help");
        add(help, "quickStart", "shortcuts", "addSample", null, "dataFolder", "certsFolder");
        if (!Platform.isMac()) add(help, null, "about");
        mb.add(file);
        mb.add(edit);
        mb.add(view);
        mb.add(request);
        mb.add(window);
        mb.add(help);
        return mb;
    }

    private void add(JMenu menu, String... ids) {
        for (String id : ids) {
            if (id == null) menu.addSeparator();
            else menu.add(new JMenuItem(actions.get(id)));
        }
    }

    private static JMenuItem textAction(Action a, String name, KeyStroke ks) {
        JMenuItem i = new JMenuItem(a);
        i.setText(name);
        i.setAccelerator(ks);
        return i;
    }

    private void installMacHandlers() {
        if (!Desktop.isDesktopSupported()) return;
        Desktop d = Desktop.getDesktop();
        try {
            if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler(e -> showAbout());
            if (d.isSupported(Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler(e -> openSettings(null));
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler((e, response) -> {
                quit();
                response.cancelQuit();
            });
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // not on this platform
        }
    }

    private void showAbout() {
        Ui.message(this, "<html><b>REST Client</b> 1.0<br>A lightweight, standalone REST client.<br><br>"
                        + "Data: " + Ui.escape(dev.restclient.store.AppPaths.display(ctx.repo.paths().base())) + "</html>",
                "About REST Client", JOptionPane.PLAIN_MESSAGE, new javax.swing.ImageIcon(Theme.appIcon(64)));
    }

    /** A one-screen guide to the things people look for first (help and documentation, Nielsen #10). */
    /** Help › Add Sample Collection: a fresh copy of the httpbin tour (and its local environment, if missing). */
    public void addSampleCollection() {
        ApiCollection c = dev.restclient.store.Samples.httpbin();
        c.name = uniqueName(c.name, ctx.repo.collections().stream().map(x -> x.name).toList());
        ctx.repo.addCollection(c);
        if (ctx.repo.environments().stream().noneMatch(e -> e.name.equals(dev.restclient.store.Samples.LOCAL_ENV))) {
            ctx.repo.addEnvironment(dev.restclient.store.Samples.localEnvironment());
            variablesChanged();
        }
        sidebar.collections().expandedIds().add(c.id);
        showSidebar("collections");
        sidebar.collections().rebuild();
        sidebar.collections().select(c.id);
        status("Added “" + c.name + "”. Open a request and press " + Keys.label(Keys.SEND) + ".", "Undo", () -> ctx.repo.deleteCollection(c));
    }

    private void showQuickStart() {
        String[][] tips = {
                {"Send a request", "Type or paste a URL, pick the method, press " + Keys.label(Keys.SEND) + "."},
                {"Paste a cURL command", "Paste it into any URL field; it's turned into a request with headers and body."},
                {"Use variables", "Write {{name}} anywhere. Hover a variable to see its value and where it comes from; "
                        + "define values in Environments (" + Keys.label(Keys.SWITCH_ENV) + " to switch)."},
                {"Save and organise", Keys.label(Keys.SAVE) + " saves into a collection. Right-click collections, folders and "
                        + "tabs for more."},
                {"Find anything", Keys.label(Keys.PALETTE) + " searches requests, environments, history and commands."},
                {"Certificates", "Self-signed or internal CA? Trust the certificate from the error screen, or put it in the "
                        + "certs folder. You can also skip verification per request."},
                {"Explore the sample", "“" + dev.restclient.store.Samples.NAME + "” in the sidebar walks through every feature "
                        + "against httpbin.org, a free service that echoes your requests back. Help › Add Sample Collection "
                        + "brings it back if you delete it."},
                {"Undo mistakes", "Deleted something? Use Undo in the status bar. Closed a tab? " + Keys.label(Keys.REOPEN_TAB)
                        + " reopens it."},
        };
        StringBuilder html = new StringBuilder("<html><div style='width:" + Ui.css(440) + "px'>");
        for (String[] t : tips) {
            html.append("<p style='margin-bottom:" + Ui.css(8) + "px'><b>").append(Ui.escape(t[0])).append("</b><br>")
                    .append(Ui.escape(t[1])).append("</p>");
        }
        html.append("</div></html>");
        Ui.message(this, new JLabel(html.toString()), "Quick Start", JOptionPane.PLAIN_MESSAGE, null);
    }

    private void showShortcuts() {
        List<Object[]> rows = new ArrayList<>();
        for (Action a : actions.values()) {
            KeyStroke ks = (KeyStroke) a.getValue(Action.ACCELERATOR_KEY);
            if (ks != null) rows.add(new Object[]{a.getValue(Action.NAME), Keys.label(ks)});
        }
        rows.add(new Object[]{"Jump to a variable's definition", Platform.isMac() ? "⌘-click" : "Ctrl+click"});
        rows.add(new Object[]{"Cancel request / close dialog", Platform.isMac() ? "esc" : "Esc"});
        JTable t = new JTable(rows.toArray(new Object[0][]), new Object[]{"Action", "Shortcut"});
        t.setEnabled(false);
        t.setRowHeight(Theme.scaled(28));
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        Theme.bg(t, Theme::panelBg);
        Theme.bg(t.getTableHeader(), Theme::panelBg);
        t.getColumnModel().getColumn(1).setMaxWidth(Theme.scaled(150));
        t.getColumnModel().getColumn(1).setPreferredWidth(Theme.scaled(140));
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        Theme.bg(sp.getViewport(), Theme::panelBg);
        sp.setPreferredSize(new Dimension(Theme.scaled(460), Theme.scaled(420)));
        Ui.message(this, sp, "Keyboard Shortcuts", JOptionPane.PLAIN_MESSAGE, null);
    }

    // ---- session ---------------------------------------------------------------------------

    private void restoreSession() {
        Session s = ctx.repo.loadSession();
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int w = Math.min(Math.max(900, s.width), screen.width);
        int h = Math.min(Math.max(600, s.height), screen.height);
        setSize(w, h);
        if (s.x >= 0 && s.y >= 0 && s.x < screen.x + screen.width - 100 && s.y < screen.y + screen.height - 100) {
            setLocation(s.x, s.y);
        } else {
            setLocationRelativeTo(null);
        }
        if (s.maximized) setExtendedState(MAXIMIZED_BOTH);
        sidebarWidth = Math.max(200, Math.min(480, s.sidebarWidth));
        split.setDividerLocation(sidebarWidth);
        if (!s.sidebarVisible) toggleSidebar();
        sidebar.showTab(s.sidebarTab);
        List<String> expanded = new ArrayList<>(s.expandedNodes);
        boolean firstLaunch = expanded.isEmpty() && !java.nio.file.Files.exists(ctx.repo.paths().session());
        if (firstLaunch) {
            // First launch: show each collection; the sample opens only its first folder, so the list stays short.
            for (ApiCollection c : ctx.repo.collections()) {
                expanded.add(c.id);
                if (c.name.equals(dev.restclient.store.Samples.NAME)) {
                    if (!c.items.isEmpty() && c.items.get(0).folder) expanded.add(c.items.get(0).id);
                } else {
                    collectFolders(c.items, expanded);
                }
            }
        }
        sidebar.collections().setExpandedIds(expanded);

        if (ctx.settings().restoreTabs) {
            for (Session.Tab t : s.tabs) {
                if (t.draft == null) continue;
                CollectionNode node = ctx.repo.node(t.collectionId, t.nodeId);
                RequestDef saved = null;
                String nodeId = null;
                if (node != null && node.request != null) {
                    saved = node.request.copy();
                    saved.name = node.name;
                    nodeId = node.id;
                }
                String colId = ctx.repo.collection(t.collectionId) != null ? t.collectionId : null;
                RequestTab tab = addTab(t.draft, colId, nodeId, saved, false);
                tab.setDividerRatio(s.responseDivider);
            }
            if (s.selectedTab >= 0 && s.selectedTab < tabs.getTabCount()) tabs.setSelectedIndex(s.selectedTab);
        }
        if (firstLaunch && tabs.getTabCount() == 0) {
            // ...and its first request is open, ready to send
            for (ApiCollection c : ctx.repo.collections()) {
                if (c.name.equals(dev.restclient.store.Samples.NAME) && !c.items.isEmpty() && !c.items.get(0).children.isEmpty()) {
                    openSaved(c, c.items.get(0).children.get(0), false);
                    break;
                }
            }
        }
    }

    private void saveSession() {
        Session s = new Session();
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) != 0;
        s.maximized = max;
        if (!max) {
            s.x = getX();
            s.y = getY();
            s.width = getWidth();
            s.height = getHeight();
        }
        s.sidebarVisible = sidebarVisible;
        s.sidebarWidth = sidebarVisible ? split.getDividerLocation() : sidebarWidth;
        s.sidebarTab = sidebar.currentTab();
        s.expandedNodes = new ArrayList<>(sidebar.collections().expandedIds());
        int selected = -1;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof RequestTab t) {
                Session.Tab st = new Session.Tab();
                st.draft = t.draft().copy();
                st.collectionId = t.collectionId();
                st.nodeId = t.nodeId();
                if (i == tabs.getSelectedIndex()) {
                    selected = s.tabs.size();
                    s.responseDivider = t.dividerRatio();
                }
                s.tabs.add(st);
            }
        }
        s.selectedTab = selected;
        ctx.repo.saveSession(s);
    }

    private void quit() {
        if (!ctx.settings().restoreTabs) {
            long dirty = requestTabs().stream().filter(RequestTab::isDirty).count();
            if (dirty > 0 && !Ui.confirm(this, "Quit REST Client",
                    dirty + " tab" + (dirty == 1 ? " has" : "s have") + " unsaved changes that will be lost.", "Quit Anyway")) {
                return;
            }
        }
        for (RequestTab t : requestTabs()) t.cancel();
        saveSession();
        ctx.repo.flush();
        dispose();
        System.exit(0);
    }
}
