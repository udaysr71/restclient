package dev.restclient.ui;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.RequestDef;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Sidebar tree of collections, folders and saved requests. */
public class CollectionsPanel extends JPanel {
    private static final DataFlavor NODE_FLAVOR = new DataFlavor(String.class, "Collection item");

    private final AppContext ctx;
    private final MainFrame frame;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JTextField filter = Ui.searchField(Ui.placeholder(new JTextField(), "Filter"));
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Set<String> expanded = new HashSet<>();
    private boolean rebuilding;

    public CollectionsPanel(AppContext ctx, MainFrame frame) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        this.frame = frame;
        setOpaque(false);

        filter.getAccessibleContext().setAccessibleName("Filter requests");
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
        add(filter, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setOpaque(false);
        tree.setRowHeight(Theme.scaled(28));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.setToggleClickCount(0);
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                if (!rebuilding && filter.getText().isBlank()) expanded.add(idOf(e.getPath().getLastPathComponent()));
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                if (!rebuilding && filter.getText().isBlank()) expanded.remove(idOf(e.getPath().getLastPathComponent()));
            }
        });
        tree.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("shift F10"), "rc.menu");
        tree.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_CONTEXT_MENU, 0), "rc.menu");
        tree.getActionMap().put("rc.menu", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                keyboardMenu();
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.isPopupTrigger() || e.getButton() != MouseEvent.BUTTON1) return;
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p == null) return;
                Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
                if (o instanceof CollectionNode n && !n.folder) {
                    frame.openSaved(collectionFor(p), n, (e.getModifiersEx() & Keys.MOD) != 0);
                } else if (e.getClickCount() == 1) {
                    if (tree.isExpanded(p)) tree.collapsePath(p);
                    else tree.expandPath(p);
                }
            }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open", this::openSelected);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelected);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, Keys.MOD), "delete2", this::deleteSelected);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", this::renameSelected);

        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new MoveHandler());

        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        content.setOpaque(false);
        content.add(sp, "tree");
        content.add(emptyState(), "empty");
        JPanel noMatch = Ui.transparent(new GridBagLayout());
        noMatch.add(Ui.secondary("No matching requests"));
        content.add(noMatch, "nomatch");
        add(content, BorderLayout.CENTER);

        JPanel footer = Ui.transparent(new GridLayout(1, 2, 8, 0));
        JButton newBtn = Ui.button("New", Icons.get("plus", 13), null);
        newBtn.addActionListener(e -> Ui.showBelow(newMenu(), newBtn));
        footer.add(newBtn);
        footer.add(Ui.button("Import cURL", Icons.get("import", 13), e -> frame.showCurlImport(null)));
        add(footer, BorderLayout.SOUTH);
        rebuild();
    }

    private void bind(KeyStroke ks, String name, Runnable r) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(ks, "rc-" + name);
        tree.getActionMap().put("rc-" + name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private JPanel emptyState() {
        JPanel p = Ui.transparent(new GridBagLayout());
        JPanel box = Ui.transparent(null);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JLabel icon = new JLabel(Icons.get("folder", 34));
        icon.setAlignmentX(0.5f);
        JLabel t = new JLabel("No collections yet");
        t.setFont(t.getFont().deriveFont(Font.BOLD));
        t.setAlignmentX(0.5f);
        JLabel d = Ui.secondary("<html><div style='text-align:center;width:128px'>Collections keep related requests together and share variables.</div></html>");
        d.setHorizontalAlignment(SwingConstants.CENTER);
        d.setAlignmentX(0.5f);
        JButton b = Ui.button("New Collection", e -> frame.newCollection());
        b.setAlignmentX(0.5f);
        box.add(icon);
        box.add(Ui.vgap(10));
        box.add(t);
        box.add(Ui.vgap(6));
        box.add(d);
        box.add(Ui.vgap(12));
        box.add(b);
        p.add(box);
        return p;
    }

    private JPopupMenu newMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem req = new JMenuItem("New Request");
        req.setAccelerator(Keys.NEW_TAB);
        req.addActionListener(e -> frame.newTab());
        JMenuItem col = new JMenuItem("New Collection");
        col.setAccelerator(Keys.NEW_COLLECTION);
        col.addActionListener(e -> frame.newCollection());
        m.add(req);
        m.add(col);
        ApiCollection target = selectedCollection();
        if (target != null) {
            JMenuItem folder = new JMenuItem("New Folder in “" + target.name + "”");
            folder.addActionListener(e -> newFolder(target, selectedFolderList()));
            m.add(folder);
        }
        return m;
    }

    // ---- tree building ---------------------------------------------------------------------

    public void rebuild() {
        rebuilding = true;
        try {
            String selectedId = selectedId();
            String q = filter.getText().trim().toLowerCase(Locale.ROOT);
            root.removeAllChildren();
            for (ApiCollection c : ctx.repo.collections()) {
                DefaultMutableTreeNode cn = new DefaultMutableTreeNode(c);
                boolean all = q.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(q);
                addChildren(cn, c.items, all ? "" : q);
                if (all || cn.getChildCount() > 0) root.add(cn);
            }
            model.reload();
            if (ctx.repo.collections().isEmpty()) {
                cards.show(content, "empty");
            } else if (root.getChildCount() == 0) {
                cards.show(content, "nomatch");
            } else {
                cards.show(content, "tree");
            }
            expand(root, !q.isEmpty());
            if (selectedId != null) select(selectedId);
        } finally {
            rebuilding = false;
        }
    }

    private static void addChildren(DefaultMutableTreeNode parent, List<CollectionNode> items, String q) {
        for (CollectionNode n : items) {
            DefaultMutableTreeNode tn = new DefaultMutableTreeNode(n);
            if (n.folder) {
                boolean all = q.isEmpty() || n.name.toLowerCase(Locale.ROOT).contains(q);
                addChildren(tn, n.children, all ? "" : q);
                if (all || tn.getChildCount() > 0) parent.add(tn);
            } else if (q.isEmpty() || matches(n, q)) {
                parent.add(tn);
            }
        }
    }

    private static boolean matches(CollectionNode n, String q) {
        RequestDef r = n.request;
        return n.name.toLowerCase(Locale.ROOT).contains(q)
                || (r != null && (r.url.toLowerCase(Locale.ROOT).contains(q) || r.method.name().toLowerCase(Locale.ROOT).equals(q)));
    }

    private void expand(DefaultMutableTreeNode node, boolean all) {
        Enumeration<?> e = node.children();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) e.nextElement();
            if (child.getChildCount() > 0 && (all || expanded.contains(idOf(child)))) {
                tree.expandPath(new TreePath(child.getPath()));
                expand(child, all);
            }
        }
    }

    static String idOf(Object treeNode) {
        Object o = treeNode instanceof DefaultMutableTreeNode d ? d.getUserObject() : treeNode;
        if (o instanceof ApiCollection c) return c.id;
        if (o instanceof CollectionNode n) return n.id;
        return "";
    }

    public Set<String> expandedIds() {
        return expanded;
    }

    public void setExpandedIds(List<String> ids) {
        expanded.clear();
        expanded.addAll(ids);
        rebuild();
    }

    /** Selects and reveals a collection, folder or request by id. */
    public void select(String id) {
        DefaultMutableTreeNode n = find(root, id);
        if (n == null) return;
        TreePath p = new TreePath(n.getPath());
        tree.expandPath(p.getParentPath());
        tree.setSelectionPath(p);
        tree.scrollPathToVisible(p);
    }

    private static DefaultMutableTreeNode find(DefaultMutableTreeNode node, String id) {
        Enumeration<?> e = node.breadthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (id.equals(idOf(n))) return n;
        }
        return null;
    }

    public void focusFilter() {
        filter.requestFocusInWindow();
    }

    private String selectedId() {
        TreePath p = tree.getSelectionPath();
        return p == null ? null : idOf(p.getLastPathComponent());
    }

    private static ApiCollection collectionFor(TreePath p) {
        if (p == null || p.getPathCount() < 2) return null;
        Object o = ((DefaultMutableTreeNode) p.getPathComponent(1)).getUserObject();
        return o instanceof ApiCollection c ? c : null;
    }

    /** Collection of the current selection (or the only collection). */
    public ApiCollection selectedCollection() {
        ApiCollection c = collectionFor(tree.getSelectionPath());
        if (c == null && ctx.repo.collections().size() == 1) c = ctx.repo.collections().get(0);
        return c;
    }

    private List<CollectionNode> selectedFolderList() {
        TreePath p = tree.getSelectionPath();
        if (p == null) return null;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        if (o instanceof CollectionNode n && n.folder) return n.children;
        return null;
    }

    // ---- actions ---------------------------------------------------------------------------

    private void openSelected() {
        TreePath p = tree.getSelectionPath();
        if (p == null) return;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        if (o instanceof CollectionNode n && !n.folder) frame.openSaved(collectionFor(p), n, false);
        else if (tree.isExpanded(p)) tree.collapsePath(p);
        else tree.expandPath(p);
    }

    private void deleteSelected() {
        TreePath p = tree.getSelectionPath();
        if (p == null) return;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        if (o instanceof ApiCollection c) frame.deleteCollection(c);
        else if (o instanceof CollectionNode n) frame.deleteNode(collectionFor(p), n);
    }

    private void renameSelected() {
        TreePath p = tree.getSelectionPath();
        if (p == null) return;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        if (o instanceof ApiCollection c) frame.renameCollection(c);
        else if (o instanceof CollectionNode n) frame.renameNode(collectionFor(p), n);
    }

    private void newFolder(ApiCollection c, List<CollectionNode> into) {
        String name = Ui.prompt(this, "New Folder", "Folder name", "New Folder");
        if (name == null) return;
        CollectionNode f = CollectionNode.folder(name);
        (into != null ? into : c.items).add(f);
        ctx.repo.saveCollection(c);
        expanded.add(c.id);
        rebuild();
        select(f.id);
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        if (p == null) return;
        tree.setSelectionPath(p);
        showMenu(p, e.getX(), e.getY());
    }

    /** Context menu from the keyboard (Shift+F10 or the Menu key), under the selected row. */
    private void keyboardMenu() {
        TreePath p = tree.getSelectionPath();
        if (p == null) return;
        java.awt.Rectangle r = tree.getPathBounds(p);
        if (r != null) showMenu(p, r.x + 16, r.y + r.height);
    }

    private void showMenu(TreePath p, int x, int y) {
        ApiCollection col = collectionFor(p);
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        JPopupMenu m = new JPopupMenu();
        if (o instanceof ApiCollection c) {
            item(m, "New Request", () -> frame.newRequestIn(c, c.items));
            item(m, "New Folder", () -> newFolder(c, null));
            m.addSeparator();
            item(m, "Variables…", () -> frame.showEnvironments(c.id, null, false));
            item(m, "Rename…", () -> frame.renameCollection(c));
            item(m, "Duplicate", () -> frame.duplicateCollection(c));
            m.addSeparator();
            item(m, "Delete…", () -> frame.deleteCollection(c));
        } else if (o instanceof CollectionNode n && n.folder) {
            item(m, "New Request", () -> frame.newRequestIn(col, n.children));
            item(m, "New Folder", () -> newFolder(col, n.children));
            m.addSeparator();
            item(m, "Rename…", () -> frame.renameNode(col, n));
            item(m, "Duplicate", () -> frame.duplicateNode(col, n));
            m.addSeparator();
            item(m, "Delete…", () -> frame.deleteNode(col, n));
        } else if (o instanceof CollectionNode n) {
            item(m, "Open", () -> frame.openSaved(col, n, false));
            item(m, "Open in New Tab", () -> frame.openSaved(col, n, true));
            m.addSeparator();
            item(m, "Copy as cURL", () -> frame.copyNodeAsCurl(col, n));
            item(m, "Rename…", () -> frame.renameNode(col, n));
            item(m, "Duplicate", () -> frame.duplicateNode(col, n));
            m.addSeparator();
            item(m, "Delete…", () -> frame.deleteNode(col, n));
        }
        m.show(tree, x, y);
    }

    private static void item(JPopupMenu m, String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        m.add(i);
    }

    // ---- rendering -------------------------------------------------------------------------

    private static final class Renderer extends JPanel implements TreeCellRenderer {
        private final JLabel icon = new JLabel();
        private final JLabel method = new JLabel();
        private final JLabel name = new JLabel();

        Renderer() {
            super(new BorderLayout(6, 0));
            setOpaque(false);
            JPanel left = Ui.transparent(new BorderLayout(6, 0));
            left.add(icon, BorderLayout.WEST);
            left.add(method, BorderLayout.CENTER);
            add(left, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
            setBorder(Ui.pad(0, 2, 0, 6));
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            name.setForeground(selected && tree.hasFocus() ? javax.swing.UIManager.getColor("Tree.selectionForeground") : Theme.text());
            name.setFont(tree.getFont());
            if (o instanceof ApiCollection c) {
                icon.setIcon(Icons.get("collection"));
                icon.setVisible(true);
                method.setVisible(false);
                name.setText(c.name);
                name.setFont(tree.getFont().deriveFont(Font.BOLD));
            } else if (o instanceof CollectionNode n && n.folder) {
                icon.setIcon(Icons.get("folder"));
                icon.setVisible(true);
                method.setVisible(false);
                name.setText(n.name);
            } else if (o instanceof CollectionNode n) {
                icon.setVisible(false);
                method.setVisible(true);
                method.setText(n.request == null ? "" : n.request.method.shortLabel());
                method.setFont(Theme.mono(11f).deriveFont(Font.BOLD));
                method.setForeground(selected && tree.hasFocus() ? name.getForeground() : Theme.methodColor(n.request == null ? null : n.request.method));
                method.setPreferredSize(new java.awt.Dimension(Theme.scaled(40), Theme.scaled(16)));
                name.setText(n.name);
            } else {
                name.setText(String.valueOf(o));
            }
            return this;
        }
    }

    // ---- drag and drop ---------------------------------------------------------------------

    private final class MoveHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            TreePath p = tree.getSelectionPath();
            if (p == null) return null;
            Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (!(o instanceof CollectionNode n)) return null;
            String id = n.id;
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{NODE_FLAVOR};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return NODE_FLAVOR.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                    return id;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport s) {
            if (!s.isDrop() || !s.isDataFlavorSupported(NODE_FLAVOR)) return false;
            JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
            TreePath p = dl.getPath();
            if (p == null) return false;
            Object target = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (target instanceof CollectionNode n && !n.folder) return false;
            TreePath dragged = tree.getSelectionPath();
            return dragged == null || !dragged.isDescendant(p);
        }

        @Override
        public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            String id;
            try {
                id = (String) s.getTransferable().getTransferData(NODE_FLAVOR);
            } catch (Exception e) {
                return false;
            }
            ApiCollection src = ctx.repo.collectionOf(id);
            if (src == null) return false;
            CollectionNode node = src.find(id);
            List<CollectionNode> srcList = src.parentList(id);
            JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
            TreePath p = dl.getPath();
            Object target = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            ApiCollection dest = collectionFor(p);
            List<CollectionNode> destList = target instanceof ApiCollection c ? c.items : ((CollectionNode) target).children;
            int index = dl.getChildIndex();
            int srcIndex = srcList.indexOf(node);
            srcList.remove(node);
            if (index < 0 || index > destList.size()) index = destList.size();
            if (srcList == destList && srcIndex >= 0 && srcIndex < index) index--;
            destList.add(Math.max(0, Math.min(index, destList.size())), node);
            ctx.repo.saveCollection(src);
            if (dest != null && dest != src) ctx.repo.saveCollection(dest);
            expanded.add(idOf(p.getLastPathComponent()));
            rebuild();
            select(id);
            frame.collectionsChanged();
            return true;
        }
    }
}
