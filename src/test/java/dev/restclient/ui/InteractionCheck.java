package dev.restclient.ui;

import com.sun.net.httpserver.HttpServer;
import dev.restclient.App;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.Settings;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Drives the real app with real keyboard and mouse events and checks that every interactive control responds.
 * Not a unit test (it needs a display): run with {@code java Build.java uicheck}, or under Xvfb on CI.
 */
public final class InteractionCheck {
    private static Robot robot;
    private static MainFrame frame;
    private static final List<String> failures = new ArrayList<>();
    private static final List<Throwable> edtErrors = new ArrayList<>();
    private static int passed;
    private static java.nio.file.Path homeDir;
    private static final boolean MAC = Platform.isMac();
    private static final int MOD = MAC ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;

    public static void main(String[] args) throws Exception {
        java.nio.file.Path home = Files.createTempDirectory("rc-uicheck");
        System.setProperty("restclient.home", home.toString());
        homeDir = home;
        // a user theme file, present before launch
        Files.createDirectories(home.resolve("themes"));
        Files.writeString(home.resolve("themes/ocean.css"), """
                @theme { name: "Ocean"; base: dark; }
                :root { --panelBg: #0B2233; --accent: #2F6FB0; }
                """);
        dev.restclient.store.Repository seed = new dev.restclient.store.Repository(new dev.restclient.store.AppPaths(home));
        seed.load();
        seed.globals().variables.add(new dev.restclient.model.Variable("baseUrl", "http://127.0.0.1"));
        seed.saveEnvironment(seed.globals());
        seed.flush();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            synchronized (edtErrors) {
                edtErrors.add(e);
            }
            e.printStackTrace();
        });
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            byte[] b = "{\"ok\":true,\"items\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        http.start();
        String base = "http://127.0.0.1:" + http.getAddress().getPort();

        App.main(new String[0]);
        robot = new Robot();
        robot.setAutoDelay(15);
        waitFor("main window", () -> {
            for (Frame f : Frame.getFrames()) if (f instanceof MainFrame m && m.isShowing()) frame = m;
            return frame != null;
        });
        robot.delay(800);
        try {
            run(base);
        } catch (Throwable t) {
            // report and exit; otherwise the open windows would keep the JVM (and the build) waiting forever
            t.printStackTrace();
            failures.add("check run stopped early: " + t);
        } finally {
            http.stop(0);
        }
        System.out.println();
        System.out.println(passed + " checks passed, " + failures.size() + " failed, " + edtErrors.size() + " uncaught exceptions");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        System.exit(failures.isEmpty() && edtErrors.isEmpty() ? 0 : 1);
    }

    private static void run(String base) throws Exception {
        edt(() -> frame.newTab());
        robot.delay(400);
        RequestTab tab = currentTab();
        VarTextField url = field(tab, "urlField");

        // ---- URL field editing
        check("URL field has focus in a new tab", () -> focusOwner() == url);
        type("abc");
        key(KeyEvent.VK_BACK_SPACE);
        check("Backspace deletes in URL", () -> "ab".equals(text(url)));
        key(KeyEvent.VK_LEFT);
        type("x");
        check("Left arrow moves caret", () -> "axb".equals(text(url)));
        key(KeyEvent.VK_HOME);
        type("y");
        key(KeyEvent.VK_END);
        type("z");
        check("Home/End", () -> "yaxbz".equals(text(url)));
        key(MOD, KeyEvent.VK_A);
        type("q");
        check("Select all + type replaces", () -> "q".equals(text(url)));
        key(MOD, KeyEvent.VK_Z);
        check("Undo restores previous text", () -> !"q".equals(text(url)));
        key(MOD, KeyEvent.VK_A);
        key(KeyEvent.VK_DELETE);
        check("Delete clears selection", () -> text(url).isEmpty());

        // ---- autocomplete
        type("{{");
        robot.delay(300);
        check("Typing {{ opens autocomplete", () -> visibleList(l -> l.getModel().getSize() > 0) != null);
        key(KeyEvent.VK_ENTER);
        check("Enter accepts the suggestion", () -> text(url).equals("{{baseUrl}}"));
        key(MOD, KeyEvent.VK_A);
        key(KeyEvent.VK_DELETE);

        // ---- params sync
        paste(base + "/orders?limit=5&q=x");
        robot.delay(300);
        KeyValueTable params = field(tab, "paramsTable");
        check("Query string syncs to Params table", () -> enabledCount(params.getRows()) == 2);

        // ---- send
        key(MOD, KeyEvent.VK_ENTER);
        check("Send shows a 200 response", () -> anyLabel(tab, s -> s.contains("200")), 5000);
        key(MOD, KeyEvent.VK_F);
        robot.delay(200);
        check("Find shortcut focuses the response find field", () -> focusOwner() instanceof JTextComponent tc
                && SwingUtilities.isDescendingFrom(tc, field(tab, "response")));
        type("ok");
        check("Find highlights matches", () -> anyLabel(field(tab, "response"), s -> s.contains("match")));
        key(KeyEvent.VK_ESCAPE);
        click(url);

        // ---- method combo by keyboard
        @SuppressWarnings("unchecked")
        JComboBox<HttpMethod> method = (JComboBox<HttpMethod>) field(tab, "methodBox");
        click(method);
        robot.delay(250);
        key(KeyEvent.VK_DOWN);
        key(KeyEvent.VK_ENTER);
        check("Method combo changes with arrow + Enter", () -> tab.method() == HttpMethod.POST);
        System.out.println("       method now " + call(tab::method) + ", popup " + call(method::isPopupVisible));

        // ---- headers table editing
        UnderlineTabs editorTabs = field(tab, "editorTabs");
        edt(() -> selectTab(editorTabs, "Headers"));
        robot.delay(250);
        KeyValueTable headers = field(tab, "headersTable");
        JTable ht = find(headers, JTable.class);
        int before = call(() -> headers.getRows().size());
        clickCell(ht, ht.getRowCount() - 1, 1);
        robot.delay(150);
        type("X-Check");
        key(KeyEvent.VK_TAB);
        type("yes");
        key(KeyEvent.VK_ENTER);
        robot.delay(200);
        check("Typing in the header table adds a row",
                () -> headers.getRows().stream().anyMatch(k -> "X-Check".equals(k.key) && "yes".equals(k.value)));
        check("Header table grew", () -> headers.getRows().size() > before);
        int row = call(() -> {
            for (int i = 0; i < headers.getRows().size(); i++) if ("X-Check".equals(headers.getRows().get(i).key)) return i;
            return -1;
        });
        if (row >= 0) {
            clickCell(ht, row, 0);
            check("Header checkbox toggles", () -> !headers.getRows().get(row).enabled);
        }

        // ---- body editor
        edt(() -> selectTab(editorTabs, "Body"));
        robot.delay(200);
        Ui.Segmented bodyType = field(tab, "bodyType");
        AbstractButton json = find(bodyType, AbstractButton.class, b -> "JSON".equals(b.getText()));
        click(json);
        CodeArea body = field(tab, "bodyEditor");
        click(body);
        key(MOD, KeyEvent.VK_A);
        key(KeyEvent.VK_DELETE);
        type("{\"a\":1,");
        key(KeyEvent.VK_ENTER);
        type("\"b\":[2]}");
        check("Body editor accepts typing and Enter", () -> text(body).contains("\n") && text(body).startsWith("{\"a\":1,"));
        key(KeyEvent.VK_BACK_SPACE);
        check("Backspace in body editor", () -> text(body).endsWith("[2]"));
        type("}");
        click(field(tab, "beautify"));
        check("Beautify reformats JSON", () -> text(body).contains("\n  \"a\": 1"));

        // ---- tabs
        int tabs = call(() -> tabStrip().getTabCount());
        key(MOD, KeyEvent.VK_T);
        check("New tab shortcut", () -> tabStrip().getTabCount() == tabs + 1);
        key(MOD, KeyEvent.VK_W);
        check("Close tab shortcut", () -> tabStrip().getTabCount() == tabs);

        // ---- palette
        key(MOD, KeyEvent.VK_K);
        check("Palette opens", () -> visibleDialog("rc.palette") != null);
        JList<?> plist = visibleList(l -> "rc.palette".equals(SwingUtilities.getWindowAncestor(l).getName()));
        int sel0 = plist == null ? -1 : call(plist::getSelectedIndex);
        key(KeyEvent.VK_DOWN);
        key(KeyEvent.VK_DOWN);
        check("Palette moves with arrow keys", () -> plist != null && plist.getSelectedIndex() > sel0);
        int sel2 = plist == null ? -1 : call(plist::getSelectedIndex);
        key(KeyEvent.VK_UP);
        check("Palette moves up", () -> plist != null && plist.getSelectedIndex() < sel2);
        type("new request");
        robot.delay(200);
        key(KeyEvent.VK_ENTER);
        check("Palette Enter runs the item", () -> tabStrip().getTabCount() == tabs + 1 && visibleDialog("rc.palette") == null);
        key(MOD, KeyEvent.VK_W);
        robot.delay(200);
        // a dirty tab close may ask to save; dismiss any dialog
        dismissDialogs();

        RequestTab t2 = currentTab();

        // ---- cURL paste
        click(field(t2, "urlField"));
        key(MOD, KeyEvent.VK_A);
        paste("curl -X PUT '" + base + "/items/7' -H 'X-Pasted: 1'");
        robot.delay(400);
        dismissDialogs();
        check("Pasting cURL into the URL imports it", () -> currentTab().method() == HttpMethod.PUT
                && currentTab().draft().headers.stream().anyMatch(k -> "X-Pasted".equals(k.key)));

        // ---- environment switch menu
        key(MOD, KeyEvent.VK_E);
        robot.delay(300);
        check("Environment menu opens", () -> {
            for (Window w : Window.getWindows()) if (w.isShowing() && w != frame && w.getClass().getName().contains("Popup")) return true;
            return javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
        });
        key(KeyEvent.VK_ESCAPE);

        // ---- sidebar tree keyboard
        JTree tree = find(frame.getContentPane(), JTree.class);
        check("Sidebar tree exists", () -> tree != null);

        // ---- settings
        SwingUtilities.invokeLater(() -> frame.openSettings(null));
        robot.delay(500);
        JDialog settings = visibleDialog("rc.settings");
        check("Settings opens", () -> settings != null);
        if (settings != null) {
            JCheckBox cb = find(settings, JCheckBox.class, c -> c.isShowing() && c.isEnabled());
            if (cb != null) {
                boolean was = call(cb::isSelected);
                click(cb);
                check("Settings check box toggles", () -> cb.isSelected() != was);
                click(cb);
            }
            JSpinner sp = find(settings, JSpinner.class, s -> s.isShowing());
            if (sp != null) {
                Object v = call(sp::getValue);
                JTextComponent ed = find(sp, JTextComponent.class);
                click(ed);
                key(KeyEvent.VK_UP);
                check("Spinner up arrow changes value", () -> !sp.getValue().equals(v));
            }
            JComboBox<?> themeBox = find(settings, JComboBox.class, c -> "Theme".equals(c.getAccessibleContext().getAccessibleName()));
            check("Settings theme picker lists five built-in themes and the user's", () -> themeBox != null && themeBox.getItemCount() == 6
                    && "Ocean".equals(((Themes.Choice) themeBox.getItemAt(5)).label()));
            JComboBox<?> uiFontBox = find(settings, JComboBox.class, c -> "Interface font".equals(c.getAccessibleContext().getAccessibleName()));
            JComboBox<?> codeFontBox = find(settings, JComboBox.class, c -> "Code font".equals(c.getAccessibleContext().getAccessibleName()));
            check("Settings has interface and code font pickers starting at Default", () -> uiFontBox != null && codeFontBox != null
                    && "".equals(uiFontBox.getSelectedItem()) && "".equals(codeFontBox.getSelectedItem()));
            if (themeBox != null) {
                edt(() -> themeBox.setSelectedIndex(4));
                check("Choosing Solarized Dark in Settings applies it", () -> Theme.palette() == Theme.Palette.SOLARIZED_DARK);
                edt(() -> themeBox.setSelectedIndex(5));
                check("Choosing a theme file in Settings applies it", () -> "ocean".equals(Theme.palette().id)
                        && Theme.panelBg().getRGB() == 0xFF0B2233);
                edt(() -> themeBox.setSelectedIndex(1));
                check("Choosing Light in Settings applies it", () -> Theme.palette() == Theme.Palette.LIGHT);
            }
            click(find(settings, JSpinner.class, s -> s.isShowing()) == null ? settings.getContentPane() : find(find(settings, JSpinner.class, s -> s.isShowing()), JTextComponent.class));
            key(KeyEvent.VK_ESCAPE);
            robot.delay(300);
            check("Escape closes settings", () -> !settings.isShowing());
            if (settings.isShowing()) edt(settings::dispose);
        }

        // ---- environment dialog
        SwingUtilities.invokeLater(() -> frame.showEnvironments(null, null, false));
        robot.delay(500);
        JDialog env = visibleDialog("rc.environments");
        check("Environments dialog opens", () -> env != null);
        if (env != null) {
            KeyValueTable vars = find(env, KeyValueTable.class, k -> k.isShowing());
            if (vars != null) {
                JTable vt = find(vars, JTable.class);
                clickCell(vt, vt.getRowCount() - 1, 1);
                type("checkVar");
                key(KeyEvent.VK_TAB);
                type("v1");
                key(KeyEvent.VK_ENTER);
                check("Variable row added", () -> vars.getRows().stream().anyMatch(k -> "checkVar".equals(k.key)));
            }
            edt(env::dispose);
        }

        // ---- zoom
        edt(() -> frame.toFront());
        robot.delay(300);
        click(field(currentTab(), "urlField"));
        float fontBefore = call(() -> field(currentTab(), "urlField") instanceof JTextComponent u ? u.getFont().getSize2D() : 0f);
        key(MOD, KeyEvent.VK_EQUALS);
        check("Zoom In shortcut", () -> Theme.zoom() > 1f);
        check("Zoom In enlarges text", () -> ((JTextComponent) field(currentTab(), "urlField")).getFont().getSize2D() > fontBefore);
        key(MOD, KeyEvent.VK_MINUS);
        key(MOD, KeyEvent.VK_MINUS);
        check("Zoom Out shortcut", () -> Theme.zoom() < 1f);
        key(MOD, KeyEvent.VK_0);
        check("Actual Size shortcut", () -> Theme.zoom() == 1f
                && Math.abs(((JTextComponent) field(currentTab(), "urlField")).getFont().getSize2D() - fontBefore) < 0.6f);

        // ---- appearance toggle
        boolean darkBefore = Theme.dark();
        click(field(frame, "themeToggle"));
        check("Appearance toggle switches light/dark", () -> Theme.dark() != darkBefore);
        key(MOD, KeyEvent.VK_SHIFT, KeyEvent.VK_L);
        check("Appearance shortcut switches back", () -> Theme.dark() == darkBefore);

        // ---- sidebar slide
        toggleSidebarKeys();
        robot.delay(400);
        check("Sidebar hides", () -> !((java.awt.Component) field(frame, "sidebar")).isVisible());
        toggleSidebarKeys();
        robot.delay(400);
        check("Sidebar shows again", () -> ((java.awt.Component) field(frame, "sidebar")).isVisible()
                && ((javax.swing.JSplitPane) field(frame, "split")).getDividerLocation() > 100);

        // ---- variables resolve when sending
        edt(() -> frame.newTab());
        robot.delay(300);
        RequestTab vt = currentTab();
        VarTextField vurl = field(vt, "urlField");
        paste("{{baseUrl}}:" + base.substring(base.lastIndexOf(':') + 1) + "/vars");
        key(MOD, KeyEvent.VK_ENTER);
        check("Request with {{baseUrl}} resolves and returns 200", () -> anyLabel(vt, s2 -> s2.contains("200")), 5000);
        check("Sent request appears in history", () -> !ctxOf().repo.history().isEmpty());

        // ---- params table edits flow back into the URL
        UnderlineTabs vtTabs = field(vt, "editorTabs");
        edt(() -> selectTab(vtTabs, "Params"));
        robot.delay(200);
        KeyValueTable vparams = field(vt, "paramsTable");
        JTable pt = find(vparams, JTable.class);
        clickCell(pt, pt.getRowCount() - 1, 1);
        type("page");
        key(KeyEvent.VK_TAB);
        type("2");
        key(KeyEvent.VK_ENTER);
        check("Adding a param updates the URL", () -> text(vurl).endsWith("/vars?page=2"));

        // ---- response views
        ResponsePanel resp = field(vt, "response");
        Ui.Segmented views = field(resp, "views");
        click(find(views, AbstractButton.class, b -> "Raw".equals(b.getText())));
        check("Raw response view", () -> views.selectedIndex() == 1);
        click(find(views, AbstractButton.class, b -> b.getText() != null && b.getText().startsWith("Headers")));
        check("Headers response view", () -> views.selectedIndex() == 2);
        click(find(views, AbstractButton.class, b -> "Pretty".equals(b.getText())));

        // ---- auth
        edt(() -> selectTab(vtTabs, "Auth"));
        robot.delay(200);
        @SuppressWarnings("unchecked")
        JComboBox<Object> authType = (JComboBox<Object>) field(vt, "authType");
        edt(() -> {
            for (int i = 0; i < authType.getItemCount(); i++) {
                if (authType.getItemAt(i).toString().toLowerCase(java.util.Locale.ROOT).contains("bearer")) authType.setSelectedIndex(i);
            }
        });
        robot.delay(200);
        click(field(vt, "authToken"));
        type("abc123");
        check("Bearer token is stored on the request", () -> "abc123".equals(vt.draft().auth.token));

        // ---- copy as cURL
        edt(() -> vt.copyAsCurl(true));
        check("Copy as cURL puts a curl command on the clipboard", () -> {
            Object c = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return c.toString().startsWith("curl") && c.toString().contains("Authorization: Bearer abc123");
        });

        // ---- save to a collection (first save offers "My Requests")
        click(vurl);
        key(MOD, KeyEvent.VK_S);
        robot.delay(500);
        JDialog saveDialog = call(() -> {
            for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof JDialog d && d.getTitle().startsWith("Save")) return d;
            return null;
        });
        check("Save opens the Save dialog", () -> saveDialog != null);
        if (saveDialog != null) {
            key(MOD, KeyEvent.VK_A);
            type("Vars check");
            key(KeyEvent.VK_ENTER);
            robot.delay(300);
            check("Saving creates the collection and the request", () -> vt.isSaved() && !vt.isDirty()
                    && ctxOf().repo.collections().stream().anyMatch(c -> "My Requests".equals(c.name)));
        }

        // ---- tab context menu
        edt(() -> {
            frame.newTab();
            frame.newTab();
        });
        robot.delay(300);
        int n0 = call(() -> tabStrip().getTabCount());
        rightClickTab(0);
        check("Right-click on a tab opens its menu", () -> visibleMenuItem("Close Tabs to the Right") != null);
        clickMenuItem("Duplicate Tab");
        check("Duplicate Tab adds a tab", () -> tabStrip().getTabCount() == n0 + 1);
        int last = call(() -> tabStrip().getTabCount() - 1);
        rightClickTab(last);
        check("Close Tabs to the Right is disabled on the last tab", () -> !visibleMenuItem("Close Tabs to the Right").isEnabled());
        key(KeyEvent.VK_ESCAPE);
        robot.delay(150);
        rightClickTab(1);
        clickMenuItem("Close Tabs to the Right");
        robot.delay(300);
        dismissDialogs();
        check("Close Tabs to the Right keeps tabs 0-1", () -> tabStrip().getTabCount() == 2);
        // make tab 1 dirty, then Close Other Tabs from tab 0: one combined prompt
        edt(() -> tabStrip().setSelectedIndex(1));
        click(field(currentTab(), "urlField"));
        type("dirty");
        rightClickTab(0);
        clickMenuItem("Close Other Tabs");
        robot.delay(400);
        String discard = Platform.isLinux() ? "Discard" : "Don't Save";
        check("Closing a dirty tab asks first", () -> visibleButton(discard) != null);
        if (visibleButton(discard) != null) click(visibleButton(discard));
        robot.delay(300);
        check("Close Other Tabs leaves one tab", () -> tabStrip().getTabCount() == 1);

        // ---- drag to reorder
        edt(() -> {
            frame.newTab();
            frame.newTab();
        });
        robot.delay(300);
        java.awt.Component firstContent = call(() -> tabStrip().getComponentAt(0));
        dragTab(0, 2);
        check("Dragging a tab reorders it", () -> tabStrip().indexOfComponent(firstContent) > 0);

        // ---- new collection via prompt
        SwingUtilities.invokeLater(() -> frame.newCollection());
        robot.delay(500);
        type("Payments");
        key(KeyEvent.VK_ENTER);
        check("New Collection prompt creates it", () -> ctxOf().repo.collections().stream().anyMatch(c -> "Payments".equals(c.name)));

        // ---- import cURL dialog
        SwingUtilities.invokeLater(() -> frame.showCurlImport(null));
        robot.delay(500);
        JDialog imp = call(() -> {
            for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof JDialog d && "Import cURL".equals(d.getTitle())) return d;
            return null;
        });
        check("Import cURL dialog opens", () -> imp != null);
        if (imp != null) {
            JTextComponent area = find(imp, JTextComponent.class, c -> c.isShowing() && c.isEditable());
            click(area);
            paste("curl -X DELETE " + base + "/things/9");
            robot.delay(300);
            AbstractButton go = find(imp, AbstractButton.class, b -> "Import".equals(b.getText()));
            click(go);
            robot.delay(400);
            check("Import cURL opens a DELETE request", () -> currentTab() != null && currentTab().method() == HttpMethod.DELETE);
        }

        // ---- Save is only offered when there's something to save
        edt(() -> frame.openSaved(ctxOf().repo.collections().get(0), firstRequest(), false));
        robot.delay(300);
        check("Save is disabled for an unchanged saved request", () -> !actionOf("save").isEnabled());
        click(field(currentTab(), "urlField"));
        key(KeyEvent.VK_END);
        type("x");
        check("Save is enabled after an edit", () -> actionOf("save").isEnabled());
        key(KeyEvent.VK_BACK_SPACE);
        check("Undoing the edit disables Save again", () -> !actionOf("save").isEnabled());

        // ---- error prevention: an empty URL can't be sent, and the field says so
        edt(() -> frame.newTab());
        robot.delay(300);
        key(MOD, KeyEvent.VK_ENTER);
        robot.delay(300);
        check("Sending an empty URL outlines the URL field", () -> "error".equals(
                ((javax.swing.JComponent) field(currentTab(), "requestGroup")).getClientProperty("JComponent.outline")));
        check("...and puts the cursor in it", () -> focusOwner() == field(currentTab(), "urlField"));
        type("x");
        check("Typing clears the error outline", () -> ((javax.swing.JComponent) field(currentTab(), "requestGroup"))
                .getClientProperty("JComponent.outline") == null);

        // ---- keyboard: Tab reaches Send and the icon buttons
        click(field(currentTab(), "urlField"));
        boolean reachedSend = false;
        for (int i = 0; i < 6 && !reachedSend; i++) {
            key(KeyEvent.VK_TAB);
            reachedSend = focusOwner() == field(currentTab(), "sendButton");
        }
        boolean sendReached = reachedSend;
        check("Tab from the URL reaches Send", () -> sendReached);
        key(KeyEvent.VK_TAB);
        check("Tab after Send reaches the More button", () -> focusOwner() instanceof AbstractButton b
                && "More actions".equals(b.getToolTipText()));

        // ---- reopen closed tab
        int before2 = call(() -> tabStrip().getTabCount());
        String closedTitle = call(() -> currentTab().title());
        key(MOD, KeyEvent.VK_W);
        robot.delay(300);
        dismissDialogs();
        robot.delay(300);
        // (Xvfb has no window manager to hand focus back after a dialog closes; a click stands in for it.)
        click(field(currentTab(), "urlField"));
        key(MOD, KeyEvent.VK_SHIFT, KeyEvent.VK_T);
        robot.delay(300);
        check("Reopen Closed Tab brings it back", () -> tabStrip().getTabCount() == before2
                && closedTitle.equals(currentTab().title()));

        // ---- keyboard context menu in the sidebar tree
        edt(() -> frame.showSidebar("collections"));
        JTree tree2 = find(frame.getContentPane(), JTree.class, t -> t.isShowing());
        Point rowPt = call(() -> {
            java.awt.Rectangle r = tree2.getRowBounds(1);
            Point p0 = tree2.getLocationOnScreen();
            return new Point(p0.x + r.x + 30, p0.y + r.y + r.height / 2);
        });
        clickAt(rowPt);
        robot.delay(200);
        key(KeyEvent.VK_SHIFT, KeyEvent.VK_F10);
        check("Shift+F10 opens the tree's context menu", () -> visibleMenuItem("Rename…") != null);
        key(KeyEvent.VK_ESCAPE);

        // ---- delete a saved request, then Undo
        dev.restclient.model.ApiCollection col0 = call(() -> ctxOf().repo.collections().get(0));
        dev.restclient.model.CollectionNode victim = firstRequest();
        SwingUtilities.invokeLater(() -> frame.deleteNode(col0, victim));
        robot.delay(500);
        AbstractButton del = visibleButton("Delete");
        if (del != null) click(del);
        robot.delay(300);
        check("Delete removes the request", () -> col0.find(victim.id) == null);
        AbstractButton undo = visibleButton("Undo");
        check("Delete offers Undo", () -> undo != null);
        if (undo != null) click(undo);
        check("Undo restores the request", () -> col0.find(victim.id) != null);

        // ---- accessibility: every icon-only button and text field has a name a screen reader can say
        List<String> unnamed = call(() -> {
            List<String> out = new ArrayList<>();
            audit(frame.getContentPane(), out);
            return out;
        });
        if (!unnamed.isEmpty()) System.out.println("       unnamed: " + unnamed);
        check("Icon buttons and fields have accessible names", unnamed::isEmpty);

        // ---- theme switch
        edt(() -> {
            Theme.apply(Settings.ThemeMode.DARK, true);
            frame.applyTheme();
        });
        robot.delay(300);
        edt(() -> {
            Theme.apply(Settings.ThemeMode.LIGHT, true);
            frame.applyTheme();
        });
        robot.delay(300);
        check("Theme switch keeps the window usable", () -> frame.isShowing() && currentTab() != null);

        // ---- Solarized themes: applied from the menu choice, and the toggle stays within the family
        edt(() -> frame.setAppearance(Settings.ThemeMode.SEPIA));
        check("Solarized Sepia applies", () -> Theme.palette() == Theme.Palette.SEPIA
                && frame.getContentPane().isShowing() && Theme.panelBg().getRGB() == 0xFFFDF6E3);
        click(field(frame, "themeToggle"));
        check("Toggle goes Sepia → Solarized Dark", () -> Theme.palette() == Theme.Palette.SOLARIZED_DARK && Theme.dark());
        key(MOD, KeyEvent.VK_SHIFT, KeyEvent.VK_L);
        check("Toggle goes back to Sepia", () -> Theme.palette() == Theme.Palette.SEPIA);
        edt(() -> frame.setAppearance(Settings.ThemeMode.LIGHT));
        check("Back to Light", () -> Theme.palette() == Theme.Palette.LIGHT);

        // ---- theme files: live reload, problems, removal
        edt(() -> frame.setAppearance(Themes.choiceFor("ocean")));
        check("Theme file applies from the menu choice", () -> Theme.palette().id.equals("ocean") && Theme.dark());
        Files.writeString(homeDir.resolve("themes/ocean.css"), """
                @theme { name: "Ocean"; base: dark; }
                :root { --panelBg: #102A3D; --accent: #2F6FB0; --acent: #fff; }
                """);
        check("Saving the theme file updates the app", () -> Theme.panelBg().getRGB() == 0xFF102A3D, 6000);
        check("Problems in the file are reported", () -> Theme.palette().warnings.stream().anyMatch(w -> w.contains("--accent?")));
        Files.writeString(homeDir.resolve("themes/forest.css"), "@theme { name: \"Forest\"; base: light; toggle: ocean; } :root { --accent: #2E6B3A; }");
        check("A new theme file shows up", () -> Themes.find("forest") != null, 6000);
        edt(() -> frame.setAppearance(Themes.choiceFor("forest")));
        click(field(frame, "themeToggle"));
        check("Toggle follows the file's toggle: Forest → Ocean", () -> Theme.palette().id.equals("ocean"));
        Files.delete(homeDir.resolve("themes/ocean.css"));
        check("Deleting the theme in use falls back to Light", () -> Theme.palette() == Theme.Palette.LIGHT, 6000);
        javax.swing.SwingUtilities.invokeLater(frame::exportTheme); // opens a modal prompt
        robot.delay(400);
        key(KeyEvent.VK_ENTER);
        check("Export writes a complete theme file and switches to it", () -> Theme.palette().file != null
                && Files.readString(Theme.palette().file).contains("--method-DELETE") && Theme.palette().warnings.isEmpty(), 4000);
        edt(() -> frame.setAppearance(Settings.ThemeMode.LIGHT));

        // ---- font families: switching live changes the UI and code fonts, keeping sizes
        JTextComponent urlBox = (JTextComponent) field(currentTab(), "urlField");
        float urlSize = call(() -> urlBox.getFont().getSize2D());
        List<String> codeFonts = call(FontChoices::codeFamilies);
        List<String> uiFonts = call(FontChoices::uiFamilies);
        String pickCode = codeFonts.stream().skip(1).filter(f -> !f.equals(Theme.monoFamily())).findFirst().orElse(null);
        String pickUi = uiFonts.stream().skip(1).filter(f -> !f.equals(Theme.uiFont().getName())).findFirst().orElse(null);
        check("Code font list only has monospaced fonts", () -> codeFonts.stream().skip(1).allMatch(FontChoices::monospaced));
        if (pickCode != null && pickUi != null) {
            edt(() -> Theme.setFonts(pickUi, pickCode, true));
            check("Code font applies to the URL field", () -> urlBox.getFont().getFamily().equals(new java.awt.Font(pickCode, 0, 12).getFamily())
                    && Math.abs(urlBox.getFont().getSize2D() - urlSize) < 0.6f);
            check("Interface font applies to menus", () -> frame.getJMenuBar().getMenu(0).getFont().getName().equals(pickUi));
            edt(() -> Theme.setFonts(null, null, true));
            check("Default fonts restore", () -> urlBox.getFont().getFamily().equals(new java.awt.Font(Theme.defaultMonoFamily(), 0, 12).getFamily()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String name, Callable<Boolean> cond) {
        check(name, cond, 1500);
    }

    private static void check(String name, Callable<Boolean> cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        boolean ok = false;
        while (true) {
            try {
                ok = Boolean.TRUE.equals(call(cond));
            } catch (Exception e) {
                ok = false;
            }
            if (ok || System.currentTimeMillis() > end) break;
            robot.delay(50);
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "   [focus: " + describe(focusOwner()) + "]"));
        if (ok) passed++;
        else failures.add(name);
    }

    private static void waitFor(String what, Callable<Boolean> cond) throws Exception {
        long end = System.currentTimeMillis() + 15000;
        while (!Boolean.TRUE.equals(call(cond))) {
            if (System.currentTimeMillis() > end) throw new IllegalStateException("timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    private static <T> T call(Callable<T> c) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Object[] out = new Object[1];
        RuntimeException[] err = new RuntimeException[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    out[0] = c.call();
                } catch (Exception e) {
                    err[0] = new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (err[0] != null) throw err[0];
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    private static void edt(Runnable r) {
        call(() -> {
            r.run();
            return null;
        });
    }

    private static void key(int... codes) {
        for (int c : codes) robot.keyPress(c);
        for (int i = codes.length - 1; i >= 0; i--) robot.keyRelease(codes[i]);
        robot.waitForIdle();
        robot.delay(40);
    }

    private static void type(String s) {
        for (char ch : s.toCharArray()) {
            int code = KeyEvent.getExtendedKeyCodeForChar(ch);
            boolean shift = Character.isUpperCase(ch) || "{}\":?_+|<>~!@#$%^&*()".indexOf(ch) >= 0;
            switch (ch) {
                case '{' -> code = KeyEvent.VK_OPEN_BRACKET;
                case '}' -> code = KeyEvent.VK_CLOSE_BRACKET;
                case '"' -> code = KeyEvent.VK_QUOTE;
                case ':' -> code = KeyEvent.VK_SEMICOLON;
                case '?' -> code = KeyEvent.VK_SLASH;
                case '_' -> code = KeyEvent.VK_MINUS;
                default -> {
                }
            }
            if (shift) key(KeyEvent.VK_SHIFT, code);
            else key(code);
        }
    }

    private static javax.swing.Action actionOf(String id) {
        java.util.Map<String, javax.swing.Action> m = field(frame, "actions");
        return m.get(id);
    }

    private static dev.restclient.model.CollectionNode firstRequest() {
        dev.restclient.model.CollectionNode[] r = {null};
        ctxOf().repo.collections().get(0).forEachRequest(n -> {
            if (r[0] == null) r[0] = n;
        });
        return r[0];
    }

    private static void audit(java.awt.Container root, List<String> out) {
        for (java.awt.Component c : root.getComponents()) {
            if (!c.isShowing()) continue;
            if (c instanceof AbstractButton b && (b.getText() == null || b.getText().isBlank())
                    && !(b.getParent() instanceof JComboBox) && !"ComboBox.arrowButton".equals(b.getName())) {
                String n = b.getAccessibleContext().getAccessibleName();
                if (n == null || n.isBlank()) out.add(b.getClass().getSimpleName() + " tip=" + b.getToolTipText());
            }
            if (c instanceof JTextComponent t && t.isEditable() && !(SwingUtilities.getAncestorOfClass(JTable.class, t) != null)) {
                String n = t.getAccessibleContext().getAccessibleName();
                if (n == null || n.isBlank()) out.add(t.getClass().getSimpleName() + " (no name)");
            }
            if (c instanceof java.awt.Container k) audit(k, out);
        }
    }

    private static AppContext ctxOf() {
        return field(frame, "ctx");
    }

    private static void rightClickTab(int index) {
        Point p = call(() -> {
            java.awt.Component label = tabStrip().getTabComponentAt(index);
            java.awt.Component head = label.getParent();
            Point s = head.getLocationOnScreen();
            return new Point(s.x + 24, s.y + head.getHeight() / 2);
        });
        robot.mouseMove(p.x, p.y);
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        robot.waitForIdle();
        robot.delay(250);
    }

    private static void dragTab(int from, int to) {
        Point[] pts = call(() -> {
            java.awt.Component a = tabStrip().getTabComponentAt(from).getParent();
            java.awt.Component b = tabStrip().getTabComponentAt(to).getParent();
            Point sa = a.getLocationOnScreen();
            Point sb = b.getLocationOnScreen();
            return new Point[]{new Point(sa.x + 20, sa.y + a.getHeight() / 2), new Point(sb.x + b.getWidth() - 10, sb.y + b.getHeight() / 2)};
        });
        robot.mouseMove(pts[0].x, pts[0].y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int i = 1; i <= 12; i++) {
            robot.mouseMove(pts[0].x + (pts[1].x - pts[0].x) * i / 12, pts[0].y);
            robot.delay(20);
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        robot.delay(200);
    }

    private static javax.swing.JMenuItem visibleMenuItem(String text) {
        return call(() -> {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                javax.swing.JMenuItem m = find(w, javax.swing.JMenuItem.class, i -> i.isShowing() && text.equals(i.getText()));
                if (m != null) return m;
            }
            return null;
        });
    }

    private static void clickMenuItem(String text) {
        javax.swing.JMenuItem m = visibleMenuItem(text);
        if (m == null) {
            System.out.println("       (menu item not found: " + text + ")");
            return;
        }
        click(m);
        robot.delay(250);
    }

    private static AbstractButton visibleButton(String text) {
        return call(() -> {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                AbstractButton b = find(w, AbstractButton.class, x -> x.isShowing() && text.equals(x.getText()));
                if (b != null) return b;
            }
            return null;
        });
    }

    private static void toggleSidebarKeys() {
        if (MAC) key(KeyEvent.VK_META, KeyEvent.VK_CONTROL, KeyEvent.VK_S);
        else key(KeyEvent.VK_CONTROL, KeyEvent.VK_B);
    }

    private static void paste(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        robot.delay(100);
        key(MOD, KeyEvent.VK_V);
    }

    private static void click(Component c) {
        Point p = call(() -> {
            Point s = c.getLocationOnScreen();
            return new Point(s.x + c.getWidth() / 2, s.y + c.getHeight() / 2);
        });
        clickAt(p);
    }

    private static void clickCell(JTable t, int row, int col) {
        Point p = call(() -> {
            Rectangle r = t.getCellRect(row, col, true);
            t.scrollRectToVisible(r);
            Point s = t.getLocationOnScreen();
            return new Point(s.x + r.x + Math.min(r.width / 2, 40), s.y + r.y + r.height / 2);
        });
        clickAt(p);
    }

    private static void clickAt(Point p) {
        robot.mouseMove(p.x, p.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        robot.delay(80);
    }

    private static String describe(Component c) {
        if (c == null) return "none";
        String s = c.getClass().getSimpleName();
        if (c instanceof JTextComponent t) s += " \"" + t.getText().replace("\n", "\\n") + "\"";
        return s;
    }

    private static String text(JTextComponent c) {
        return call(c::getText);
    }

    private static Component focusOwner() {
        return call(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    private static RequestTab currentTab() {
        return call(() -> find(frame.getContentPane(), RequestTab.class, Component::isShowing));
    }

    private static TabStrip tabStrip() {
        return find(frame.getContentPane(), TabStrip.class);
    }

    private static long enabledCount(List<KeyValue> items) {
        return items.stream().filter(k -> k.enabled && k.key != null && !k.key.isEmpty()).count();
    }

    private static boolean anyLabel(Container root, Predicate<String> p) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel l && l.isShowing() && l.getText() != null && p.test(l.getText())) return true;
            if (c instanceof Container cc && anyLabel(cc, p)) return true;
        }
        return false;
    }

    private static void selectTab(UnderlineTabs tabs, String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getTitleAt(i).startsWith(title)) tabs.setSelectedIndex(i);
    }

    private static JDialog visibleDialog(String name) {
        return call(() -> {
            for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof JDialog d && name.equals(w.getName())) return d;
            return null;
        });
    }

    private static JList<?> visibleList(Predicate<JList<?>> p) {
        return call(() -> {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                JList<?> l = find(w, JList.class, x -> x.isShowing() && p.test(x));
                if (l != null) return l;
            }
            return null;
        });
    }

    private static void dismissDialogs() {
        edt(() -> {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w instanceof JDialog d && w.getOwner() == frame) {
                    AbstractButton dont = find(d, AbstractButton.class, b -> b.getText() != null
                            && (b.getText().startsWith("Don") || b.getText().startsWith("Discard")));
                    if (dont != null) dont.doClick();
                }
            }
        });
        robot.delay(200);
    }

    private static <T> T find(Container root, Class<T> type) {
        return find(root, type, x -> true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T find(Container root, Class<T> type, Predicate<? super T> p) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && ((Predicate) p).test(c)) return (T) c;
            if (c instanceof Container cc) {
                T r = find(cc, type, p);
                if (r != null) return r;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private InteractionCheck() {
    }
}
