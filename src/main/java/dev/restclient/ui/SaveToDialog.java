package dev.restclient.ui;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.CollectionNode;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

/** "Save As…": a name plus a collection/folder to save into. */
public final class SaveToDialog {
    /** Where to save: the collection and the list (collection root or folder children) to add to. */
    public record Target(ApiCollection collection, List<CollectionNode> into, String name) {
    }

    private final AppContext ctx;
    private final JDialog dialog;
    private final JTextField name = new JTextField(30);
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final JTree tree = new JTree(new DefaultTreeModel(root));
    private final Ui.AccentButton save = Ui.accent("Save", e -> finish());
    private Target result;
    /**
     * With no collections yet, a "My Requests" collection is offered (and preselected) so the first save is
     * name + Enter instead of a detour through New Collection. It's only created if you save into it.
     */
    private ApiCollection pendingDefault;

    private SaveToDialog(MainFrame frame, AppContext ctx, String initialName, String title) {
        this.ctx = ctx;
        dialog = Ui.sheet(frame, title);
        name.setText(initialName);
        build(frame);
    }

    public static Target open(MainFrame frame, AppContext ctx, String initialName, String title, String preselectCollectionId) {
        SaveToDialog d = new SaveToDialog(frame, ctx, initialName, title);
        d.preselect(preselectCollectionId);
        d.dialog.setSize(Theme.scaled(520), Theme.scaled(480));
        d.dialog.setLocationRelativeTo(frame);
        d.name.selectAll();
        d.dialog.setVisible(true);
        return d.result;
    }

    private void build(MainFrame frame) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(Ui.pad(4, 20, 12, 20));
        JPanel top = Ui.transparent(new BorderLayout(0, 6));
        JLabel nl = Ui.caption("Name");
        nl.setLabelFor(name);
        top.add(nl, BorderLayout.NORTH);
        top.add(name, BorderLayout.CENTER);
        // The sheet has no title bar text, so it says what it does at the top.
        JPanel head = Ui.transparent(new BorderLayout(0, 14));
        head.add(Ui.heading(dialog.getTitle(), 16f), BorderLayout.NORTH);
        head.add(top, BorderLayout.CENTER);
        Ui.placeholder(name, "Request name");
        p.add(head, BorderLayout.NORTH);

        JPanel mid = Ui.transparent(new BorderLayout(0, 6));
        mid.add(Ui.caption("Save to"), BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
                Object o = ((DefaultMutableTreeNode) v).getUserObject();
                if (o instanceof ApiCollection c) {
                    setText(c == pendingDefault ? c.name + "  (new collection)" : c.name);
                    setIcon(Icons.get("collection"));
                } else if (o instanceof CollectionNode n) {
                    setText(n.name);
                    setIcon(Icons.get("folder"));
                }
                return this;
            }
        });
        tree.addTreeSelectionListener(e -> save.setEnabled(tree.getSelectionPath() != null));
        rebuild();
        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(Ui.line(1, 1, 1, 1));
        sp.setPreferredSize(new Dimension(Theme.scaled(400), Theme.scaled(240)));
        mid.add(sp, BorderLayout.CENTER);
        mid.add(Ui.row(0, Ui.linkButton("New Collection…", e -> newCollection())), BorderLayout.SOUTH);
        p.add(mid, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(1, 0, 0, 0), Ui.pad(10, 20, 10, 20)));
        footer.add(Ui.buttonBar(save, Ui.button("Cancel", e -> dialog.dispose())), BorderLayout.EAST);
        JPanel all = new JPanel(new BorderLayout());
        all.add(p, BorderLayout.CENTER);
        all.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(all);
        dialog.getRootPane().setDefaultButton(save);
        save.setEnabled(false);
    }

    private void rebuild() {
        root.removeAllChildren();
        List<ApiCollection> all = new java.util.ArrayList<>(ctx.repo.collections());
        if (all.isEmpty()) {
            if (pendingDefault == null) {
                pendingDefault = new ApiCollection();
                pendingDefault.name = "My Requests";
            }
            all.add(pendingDefault);
        } else {
            pendingDefault = null;
        }
        for (ApiCollection c : all) {
            DefaultMutableTreeNode cn = new DefaultMutableTreeNode(c);
            addFolders(cn, c.items);
            root.add(cn);
        }
        ((DefaultTreeModel) tree.getModel()).reload();
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    private static void addFolders(DefaultMutableTreeNode parent, List<CollectionNode> items) {
        for (CollectionNode n : items) {
            if (!n.folder) continue;
            DefaultMutableTreeNode fn = new DefaultMutableTreeNode(n);
            addFolders(fn, n.children);
            parent.add(fn);
        }
    }

    private void preselect(String collectionId) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
            ApiCollection c = (ApiCollection) n.getUserObject();
            if (collectionId == null ? i == 0 : c.id.equals(collectionId)) {
                tree.setSelectionPath(new TreePath(n.getPath()));
                return;
            }
        }
    }

    private void newCollection() {
        String n = Ui.prompt(dialog, "New Collection", "Collection name", "");
        if (n == null) return;
        ApiCollection c = new ApiCollection();
        c.name = n;
        ctx.repo.addCollection(c);
        rebuild();
        preselect(c.id);
    }

    private void finish() {
        TreePath p = tree.getSelectionPath();
        String n = name.getText().trim();
        if (p == null || n.isEmpty()) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) p.getLastPathComponent();
        ApiCollection c = (ApiCollection) ((DefaultMutableTreeNode) p.getPathComponent(1)).getUserObject();
        Object o = node.getUserObject();
        if (c == pendingDefault) {
            ctx.repo.addCollection(c);
            pendingDefault = null;
        }
        List<CollectionNode> into = o instanceof CollectionNode f ? f.children : c.items;
        result = new Target(c, into, n);
        dialog.dispose();
    }
}
