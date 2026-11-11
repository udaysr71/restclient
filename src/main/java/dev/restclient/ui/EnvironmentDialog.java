package dev.restclient.ui;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.Environment;
import dev.restclient.model.Variable;
import dev.restclient.vars.VariableResolver;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Manage globals, collection variables and environments. Edits save automatically; ⌘Z undoes. */
public final class EnvironmentDialog {
    private static final int C_ON = 0;
    private static final int C_KEY = 1;
    private static final int C_VALUE = 2;
    private static final int C_EYE = 3;
    private static final int C_SECRET = 4;
    private static final int C_USED = 5;
    private static final int C_DEL = 6;

    /** One entry in the left list. */
    private record Scope(String id, String name, Environment env, ApiCollection collection) {
        List<Variable> vars() {
            return env != null ? env.variables : collection.variables;
        }

        boolean isEnvironment() {
            return env != null && !env.isGlobals();
        }
    }

    private final AppContext ctx;
    private final MainFrame frame;
    private final JDialog dialog;
    private final DefaultListModel<Object> scopes = new DefaultListModel<>();
    private final JList<Object> scopeList = new JList<>(scopes);
    private final VarModel varModel = new VarModel();
    private final JTable table = new JTable(varModel);
    private int hoverRow = -1;
    private final TableRowSorter<VarModel> sorter = new TableRowSorter<>(varModel);
    private final JTextField filter = Ui.searchField(Ui.placeholder(new JTextField(16), "Filter variables"));
    private final JPanel header = Ui.transparent(new BorderLayout(10, 0));
    private final JLabel hint = new JLabel();
    private final Set<Variable> revealed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Deque<List<Variable>>> undo = new HashMap<>();
    private final Map<String, Integer> usage = new HashMap<>();
    private final Timer saveTimer;
    private Scope current;
    private String hintVar;

    private EnvironmentDialog(MainFrame frame, AppContext ctx) {
        this.frame = frame;
        this.ctx = ctx;
        dialog = Ui.sheet(frame, "Environments");
        dialog.setName("rc.environments");
        saveTimer = new Timer(300, e -> saveNow());
        saveTimer.setRepeats(false);
        build();
    }

    /**
     * @param scopeId   environment/collection/globals id to select, or null for the active environment
     * @param varName   variable to focus, or null
     * @param define    when true and the variable is missing, adds it to the active environment (or globals)
     */
    public static void open(MainFrame frame, AppContext ctx, String scopeId, String varName, boolean define) {
        EnvironmentDialog d = new EnvironmentDialog(frame, ctx);
        d.reloadScopes();
        Scope target = null;
        if (varName != null && !define) {
            var res = frame.currentResolver().lookup(varName);
            if (res.isPresent()) target = d.findScopeByName(res.get());
        }
        if (target == null && scopeId != null) target = d.scope(scopeId);
        if (target == null) {
            Environment active = ctx.activeEnvironment();
            target = d.scope(active != null ? active.id : Environment.GLOBALS_ID);
        }
        d.select(target);
        if (varName != null) {
            d.hintVar = define ? varName : null;
            d.focusVariable(varName, define);
        }
        d.updateHint();
        d.dialog.setSize(Theme.scaled(980), Theme.scaled(620));
        d.dialog.setLocationRelativeTo(frame);
        d.dialog.setVisible(true);
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBorder(BorderFactory.createCompoundBorder(Ui.line(0, 0, 1, 0), Ui.pad(14, 18, 12, 18)));
        top.add(Ui.heading("Environments", 15f), BorderLayout.WEST);
        top.add(filter, BorderLayout.EAST);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        root.add(top, BorderLayout.NORTH);

        // left
        JPanel left = new JPanel(new BorderLayout(0, 8)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                g.setColor(Theme.sidebarBg());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        left.setBorder(Ui.pad(10, 8, 10, 8));
        scopeList.setCellRenderer(new ScopeRenderer());
        scopeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scopeList.setOpaque(false);
        scopeList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Object v = scopeList.getSelectedValue();
            if (v instanceof Scope s) {
                if (s != current) select(s);
            } else if (v instanceof String) {
                int i = scopeList.getSelectedIndex();
                if (i + 1 < scopes.size()) scopeList.setSelectedIndex(i + 1);
            }
        });
        JScrollPane ls = new JScrollPane(scopeList);
        ls.setBorder(BorderFactory.createEmptyBorder());
        ls.setOpaque(false);
        ls.getViewport().setOpaque(false);
        left.add(ls, BorderLayout.CENTER);
        JButton newEnv = Ui.linkButton("New Environment", e -> newEnvironment());
        newEnv.setIcon(Icons.get("plus", 13));
        newEnv.setHorizontalAlignment(SwingConstants.LEFT);
        left.add(newEnv, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(Theme.scaled(240), Theme.scaled(400)));

        // right
        JPanel right = new JPanel(new BorderLayout(0, 12));
        right.setBorder(Ui.pad(14, 18, 12, 18));
        right.add(header, BorderLayout.NORTH);
        setupTable();
        JScrollPane ts = new JScrollPane(table);
        // Rows are grouped by the header rule and dividers; no extra box (Practical UI, "Group related elements").
        ts.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        Theme.bg(table, Theme::panelBg);
        Theme.bg(table.getTableHeader(), Theme::panelBg);
        Theme.bg(ts.getViewport(), Theme::panelBg);
        right.add(ts, BorderLayout.CENTER);
        Ui.RoundedPanel hintBox = new Ui.RoundedPanel(new BorderLayout(10, 0), Theme::infoBg, null, 10);
        hintBox.setBorder(Ui.pad(9, 12, 9, 12));
        hintBox.add(new JLabel(Icons.get("info", 15)), BorderLayout.WEST);
        hintBox.add(hint, BorderLayout.CENTER);
        right.add(hintBox, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(240);
        split.setDividerSize(1);
        split.setBorder(BorderFactory.createEmptyBorder());
        root.add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(1, 0, 0, 0), Ui.pad(10, 18, 10, 18)));
        footer.add(Ui.small("Changes save automatically · " + Keys.label(Keys.UNDO) + " to undo"), BorderLayout.WEST);
        Ui.AccentButton done = Ui.accent("Done", e -> close());
        footer.add(Ui.buttonBar(done), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(done);

        dialog.setContentPane(root);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                saveNow();
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> undo(), Keys.UNDO, JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setMinimumSize(new Dimension(720, 460));
    }

    private void setupTable() {
        table.setRowSorter(sorter);
        for (int i = 0; i < varModel.getColumnCount(); i++) sorter.setSortable(i, false);
        table.setRowHeight(Theme.scaled(32));
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getTableHeader().setReorderingAllowed(false);
        fixed(C_ON, 36);
        fixed(C_EYE, 34);
        fixed(C_SECRET, 64);
        fixed(C_USED, 104);
        fixed(C_DEL, 30);
        table.getColumnModel().getColumn(C_KEY).setPreferredWidth(200);
        table.getColumnModel().getColumn(C_VALUE).setPreferredWidth(340);

        DefaultTableCellRenderer text = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, false, row, col);
                int m = t.convertRowIndexToModel(row);
                boolean placeholder = m == current.vars().size();
                Variable var = placeholder ? null : current.vars().get(m);
                setFont(Theme.mono(12.5f));
                setBorder(Ui.pad(0, 8, 0, 8));
                if (placeholder) {
                    setText(col == C_KEY ? "Add variable" : col == C_VALUE ? "Value" : "");
                    setForeground(Theme.secondary());
                } else {
                    if (col == C_VALUE && var.secret && !revealed.contains(var)) setText(VariableResolver.MASK);
                    if (col == C_KEY && var.key.equals(hintVar)) setFont(getFont().deriveFont(Font.BOLD));
                    if (!var.enabled && !sel) setForeground(Theme.secondary());
                }
                return this;
            }
        };
        table.getColumnModel().getColumn(C_KEY).setCellRenderer(text);
        table.getColumnModel().getColumn(C_VALUE).setCellRenderer(text);

        table.getColumnModel().getColumn(C_USED).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, false, row, col);
                setBorder(Ui.pad(0, 8, 0, 8));
                String s = v == null ? "" : v.toString();
                if (!sel) setForeground(s.equals("Unused") ? Theme.secondary() : Theme.text());
                return this;
            }
        });
        table.getColumnModel().getColumn(C_EYE).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, "", sel, false, row, col);
                int m = t.convertRowIndexToModel(row);
                boolean show = m < current.vars().size() && current.vars().get(m).secret;
                setIcon(show ? Icons.get(revealed.contains(current.vars().get(m)) ? "eye-off" : "eye", 14) : null);
                setToolTipText(show ? "Reveal or hide value" : null);
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        });
        table.getColumnModel().getColumn(C_DEL).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, "", sel, false, row, col);
                int m = t.convertRowIndexToModel(row);
                // Delete appears on the row you point at (or a selected row), like the request tables.
                setIcon(m < current.vars().size() && (row == hoverRow || sel) ? Icons.get("trash", 13) : null);
                setToolTipText(m < current.vars().size() ? "Delete variable" : null);
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        });

        JTextField keyEditor = new JTextField();
        keyEditor.setFont(Theme.mono(12.5f));
        VarSupport.undoable(keyEditor);
        JTextField valueEditor = new JTextField();
        valueEditor.setFont(Theme.mono(12.5f));
        VarSupport.undoable(valueEditor);
        DefaultCellEditor ke = new DefaultCellEditor(keyEditor);
        ke.setClickCountToStart(1);
        DefaultCellEditor ve = new DefaultCellEditor(valueEditor);
        ve.setClickCountToStart(1);
        table.getColumnModel().getColumn(C_KEY).setCellEditor(ke);
        table.getColumnModel().getColumn(C_VALUE).setCellEditor(ve);

        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                if (r != hoverRow) {
                    hoverRow = r;
                    table.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverRow = -1;
                table.repaint();
            }
        };
        table.addMouseMotionListener(hover);
        table.addMouseListener(hover);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0) return;
                int m = table.convertRowIndexToModel(row);
                if (m >= current.vars().size()) return;
                Variable v = current.vars().get(m);
                if (col == C_EYE && v.secret) {
                    if (!revealed.remove(v)) revealed.add(v);
                    table.repaint();
                } else if (col == C_DEL) {
                    deleteRow(m);
                }
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "rc-del");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "rc-del");
        table.getActionMap().put("rc-del", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int r = table.getSelectedRow();
                if (r >= 0 && !table.isEditing()) deleteRow(table.convertRowIndexToModel(r));
            }
        });
    }

    private void fixed(int col, int w) {
        TableColumn c = table.getColumnModel().getColumn(col);
        c.setMinWidth(w);
        c.setMaxWidth(w);
    }

    // ---- scopes ----------------------------------------------------------------------------

    private void reloadScopes() {
        scopes.clear();
        scopes.addElement("Shared");
        Environment g = ctx.repo.globals();
        scopes.addElement(new Scope(g.id, "Globals", g, null));
        for (ApiCollection c : ctx.repo.collections()) scopes.addElement(new Scope(c.id, c.name + " (collection)", null, c));
        scopes.addElement("Environments");
        for (Environment e : ctx.repo.environments()) scopes.addElement(new Scope(e.id, e.name, e, null));
    }

    private Scope scope(String id) {
        for (int i = 0; i < scopes.size(); i++) if (scopes.get(i) instanceof Scope s && s.id.equals(id)) return s;
        return null;
    }

    private Scope findScopeByName(VariableResolver.Resolution r) {
        return switch (r.scope()) {
            case REQUEST -> null; // edited in the request itself
            case ENVIRONMENT -> {
                Environment a = ctx.activeEnvironment();
                yield a == null ? null : scope(a.id);
            }
            case COLLECTION -> {
                for (int i = 0; i < scopes.size(); i++) {
                    if (scopes.get(i) instanceof Scope s && s.collection != null && s.collection.name.equals(r.sourceName())) yield s;
                }
                yield null;
            }
            case GLOBAL -> scope(Environment.GLOBALS_ID);
        };
    }

    private void select(Scope s) {
        if (s == null) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        current = s;
        scopeList.setSelectedValue(s, true);
        usage.clear();
        varModel.fireTableDataChanged();
        rebuildHeader();
        updateHint();
    }

    private void rebuildHeader() {
        header.removeAll();
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        if (current.isEnvironment()) {
            Environment e = current.env;
            left.add(new JLabel(Ui.dot(() -> Theme.hex(e.color, Theme.accent()), 10)));
        } else {
            left.add(new JLabel(Icons.get(current.collection != null ? "collection" : "globe")));
        }
        left.add(Ui.heading(current.collection != null ? current.collection.name : current.name, 17f));
        if (current.isEnvironment() && ctx.activeEnvironment() == current.env) {
            left.add(new Ui.Pill("Active", Theme::okBg, Theme::okFg));
        }
        if (current.collection != null) left.add(Ui.secondary("Collection variables"));
        header.add(left, BorderLayout.WEST);

        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        if (current.isEnvironment()) {
            Environment e = current.env;
            if (ctx.activeEnvironment() != e) {
                buttons.add(Ui.button("Set Active", a -> {
                    ctx.repo.setActiveEnvironment(e);
                    frame.variablesChanged();
                    scopeList.repaint();
                    rebuildHeader();
                }));
            }
            buttons.add(Ui.button("Duplicate", a -> {
                Environment copy = e.duplicate(e.name + " copy");
                ctx.repo.addEnvironment(copy);
                reloadScopes();
                select(scope(copy.id));
            }));
            JButton more = Ui.toolbarButton(Icons.get("more"), "More environment actions", null);
            more.addActionListener(a -> Ui.showBelow(envMenu(e), more));
            buttons.add(more);
        }
        if (!current.isEnvironment() && current.collection == null) {
            // Globals: a Postman globals file is imported with File › Import from Postman…
            buttons.add(Ui.button("Export to Postman…", a -> frame.exportEnvironment(ctx.repo.globals(), dialog)));
        }
        header.add(buttons, BorderLayout.EAST);
        header.revalidate();
        header.repaint();
    }

    private JPopupMenu envMenu(Environment e) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem rename = new JMenuItem("Rename…");
        rename.addActionListener(a -> {
            String n = Ui.prompt(dialog, "Rename Environment", "Name", e.name);
            if (n == null) return;
            e.name = n;
            ctx.repo.saveEnvironment(e);
            reloadScopes();
            select(scope(e.id));
            frame.variablesChanged();
        });
        JMenu color = new JMenu("Color");
        for (String c : Environment.COLORS) {
            JMenuItem ci = new JMenuItem(c.equals(e.color) ? "✓" : " ", Ui.dot(() -> Theme.hex(c, Color.GRAY), 10));
            ci.addActionListener(a -> {
                e.color = c;
                ctx.repo.saveEnvironment(e);
                rebuildHeader();
                scopeList.repaint();
                frame.variablesChanged();
            });
            color.add(ci);
        }
        JMenuItem delete = new JMenuItem("Delete…");
        delete.addActionListener(a -> {
            if (!Ui.confirm(dialog, "Delete Environment", "Delete “" + e.name + "” and its " + e.variables.size()
                    + " variables? This can't be undone.", "Delete")) return;
            ctx.repo.deleteEnvironment(e);
            reloadScopes();
            select(scope(Environment.GLOBALS_ID));
            frame.variablesChanged();
        });
        JMenuItem export = new JMenuItem("Export to Postman…");
        export.addActionListener(a -> frame.exportEnvironment(e, dialog));
        m.add(rename);
        m.add(color);
        m.add(export);
        m.addSeparator();
        m.add(delete);
        return m;
    }

    private void newEnvironment() {
        String n = Ui.prompt(dialog, "New Environment", "Name (e.g. Local, Staging, Production)", "");
        if (n == null) return;
        Environment e = new Environment();
        e.name = n;
        e.color = Environment.COLORS[ctx.repo.environments().size() % (Environment.COLORS.length - 1)];
        ctx.repo.addEnvironment(e);
        if (ctx.activeEnvironment() == null) ctx.repo.setActiveEnvironment(e);
        reloadScopes();
        select(scope(e.id));
        frame.variablesChanged();
    }

    // ---- variables -------------------------------------------------------------------------

    private void focusVariable(String name, boolean define) {
        List<Variable> vars = current.vars();
        int index = -1;
        for (int i = 0; i < vars.size(); i++) if (name.equals(vars.get(i).key)) index = i;
        if (index < 0 && define) {
            pushUndo();
            vars.add(new Variable(name, ""));
            index = vars.size() - 1;
            varModel.fireTableDataChanged();
            changed();
        }
        if (index < 0) return;
        int viewRow = table.convertRowIndexToView(index);
        int finalIndex = viewRow;
        SwingUtilities.invokeLater(() -> {
            table.changeSelection(finalIndex, C_VALUE, false, false);
            table.editCellAt(finalIndex, C_VALUE);
            Component ed = table.getEditorComponent();
            if (ed != null) ed.requestFocusInWindow();
        });
    }

    private void deleteRow(int m) {
        if (m < 0 || m >= current.vars().size()) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        pushUndo();
        current.vars().remove(m);
        varModel.fireTableDataChanged();
        changed();
    }

    private void applyFilter() {
        String q = filter.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends VarModel, ? extends Integer> e) {
                int i = e.getIdentifier();
                if (i >= current.vars().size()) return true;
                Variable v = current.vars().get(i);
                return v.key.toLowerCase(Locale.ROOT).contains(q) || (!v.secret && v.value.toLowerCase(Locale.ROOT).contains(q));
            }
        });
    }

    private void pushUndo() {
        Deque<List<Variable>> stack = undo.computeIfAbsent(current.id, k -> new ArrayDeque<>());
        List<Variable> snap = new ArrayList<>();
        for (Variable v : current.vars()) snap.add(v.copy());
        stack.push(snap);
        while (stack.size() > 50) stack.removeLast();
    }

    private void undo() {
        Deque<List<Variable>> stack = undo.get(current.id);
        if (stack == null || stack.isEmpty()) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        List<Variable> snap = stack.pop();
        current.vars().clear();
        current.vars().addAll(snap);
        revealed.clear();
        varModel.fireTableDataChanged();
        changed();
    }

    private void changed() {
        usage.clear();
        saveTimer.restart();
        scopeList.repaint();
        updateHint();
    }

    private void saveNow() {
        saveTimer.stop();
        if (current == null) return;
        // Save every scope; cheap, and covers undo across scopes.
        ctx.repo.saveEnvironment(ctx.repo.globals());
        for (Environment e : ctx.repo.environments()) ctx.repo.saveEnvironment(e);
        for (ApiCollection c : ctx.repo.collections()) ctx.repo.saveCollection(c);
        frame.variablesChanged();
    }

    private void updateHint() {
        if (current == null) return;
        String order = "Variables are looked up in order: <b>active environment</b> › <b>collection</b> › <b>Globals</b>.";
        String text = order;
        if (hintVar != null) {
            boolean definedHere = false;
            for (Variable v : current.vars()) if (hintVar.equals(v.key) && v.enabled && !v.value.isEmpty()) definedHere = true;
            text = (definedHere ? "<b>" + Ui.escape(hintVar) + "</b> is now defined. " :
                    "Give <b>" + Ui.escape(hintVar) + "</b> a value to fix the red variable in your requests. ") + order;
        }
        hint.setText("<html>" + text + "</html>");
    }

    private void close() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        saveNow();
        dialog.dispose();
    }

    private final class VarModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return current == null ? 0 : current.vars().size() + 1;
        }

        @Override
        public int getColumnCount() {
            return 7;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case C_KEY -> "Variable";
                case C_VALUE -> "Value";
                case C_SECRET -> "Secret";
                case C_USED -> "Used in";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == C_ON || c == C_SECRET ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            boolean placeholder = r == current.vars().size();
            if (placeholder) return c == C_KEY;
            return c == C_ON || c == C_KEY || c == C_VALUE || c == C_SECRET;
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (r == current.vars().size()) {
                return c == C_ON || c == C_SECRET ? null : "";
            }
            Variable v = current.vars().get(r);
            return switch (c) {
                case C_ON -> v.enabled;
                case C_KEY -> v.key;
                case C_VALUE -> v.value;
                case C_SECRET -> v.secret;
                case C_USED -> {
                    if (v.key.isBlank()) yield "";
                    int n = usage.computeIfAbsent(v.key, ctx::usageCount);
                    yield n == 0 ? "Unused" : n + (n == 1 ? " request" : " requests");
                }
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            List<Variable> vars = current.vars();
            if (r == vars.size()) {
                String key = value == null ? "" : value.toString().trim();
                if (key.isEmpty()) return;
                pushUndo();
                vars.add(new Variable(key, ""));
                fireTableDataChanged();
                changed();
                int view = table.convertRowIndexToView(vars.size() - 1);
                SwingUtilities.invokeLater(() -> table.editCellAt(view, C_VALUE));
                return;
            }
            Variable v = vars.get(r);
            Object old = getValueAt(r, c);
            if (old != null && old.equals(value)) return;
            pushUndo();
            switch (c) {
                case C_ON -> v.enabled = Boolean.TRUE.equals(value);
                case C_KEY -> v.key = value == null ? "" : value.toString().trim();
                case C_VALUE -> v.value = value == null ? "" : value.toString();
                case C_SECRET -> {
                    v.secret = Boolean.TRUE.equals(value);
                    revealed.remove(v);
                }
                default -> {
                    return;
                }
            }
            fireTableRowsUpdated(r, r);
            changed();
        }
    }

    private final class ScopeRenderer implements ListCellRenderer<Object> {
        private final JLabel caption = Ui.caption("");
        private final JPanel row = new JPanel(new BorderLayout(8, 0));
        private final JLabel name = new JLabel();
        private final JLabel trailing = Ui.small("");

        ScopeRenderer() {
            caption.setBorder(Ui.pad(10, 8, 4, 8));
            row.setBorder(Ui.pad(5, 8, 5, 8));
            row.add(name, BorderLayout.CENTER);
            row.add(trailing, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
            if (value instanceof String s) {
                caption.setText(s);
                return caption;
            }
            Scope s = (Scope) value;
            row.setOpaque(sel);
            row.setBackground(Theme.selectionBg());
            name.setText(s.collection != null ? s.collection.name : s.name);
            name.setForeground(Theme.text());
            name.setFont(list.getFont().deriveFont(sel ? Font.BOLD : Font.PLAIN));
            if (s.isEnvironment()) {
                name.setIcon(Ui.dot(() -> Theme.hex(s.env.color, Theme.accent()), 9));
            } else {
                name.setIcon(Icons.get(s.collection != null ? "collection" : "globe", 14));
            }
            boolean active = s.isEnvironment() && ctx.activeEnvironment() == s.env;
            trailing.setIcon(active ? Icons.get("check", 14) : null);
            trailing.setText(active ? "" : String.valueOf(s.vars().size()));
            return row;
        }
    }
}
