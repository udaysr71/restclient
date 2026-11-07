package dev.restclient.ui;

import dev.restclient.model.KeyValue;
import dev.restclient.vars.VariableResolver;

import javax.swing.AbstractAction;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Editable key/value table (headers, params, form fields) with a per-row enable checkbox,
 * an always-present empty row for adding, variable highlighting and a delete control.
 */
public class KeyValueTable extends JPanel {
    private static final int COL_ON = 0;
    private static final int COL_KEY = 1;
    private static final int COL_VALUE = 2;
    private static final int COL_DELETE = 3;

    private final List<KeyValue> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table;
    private final Supplier<VariableResolver> resolver;
    private final String keyPlaceholder;
    private final String valuePlaceholder;
    private Runnable onChange = () -> { };
    private boolean firing;
    /** Row under the pointer: gets a subtle highlight and shows its remove button. */
    private int hoverRow = -1;

    public KeyValueTable(Supplier<VariableResolver> resolver, VarSupport.Actions actions, String keyPlaceholder,
                         String valuePlaceholder, Function<String, List<String>> keySuggest,
                         BiFunction<String, String, List<String>> valueSuggest) {
        super(new BorderLayout());
        this.resolver = resolver;
        this.keyPlaceholder = keyPlaceholder;
        this.valuePlaceholder = valuePlaceholder;
        rows.add(new KeyValue());

        table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent e) {
                Point p = e.getPoint();
                int r = rowAtPoint(p);
                int c = columnAtPoint(p);
                if (r < 0 || (c != COL_KEY && c != COL_VALUE)) return null;
                Object v = getValueAt(r, c);
                return variablesTooltip(v == null ? "" : v.toString());
            }
        };
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowVerticalLines(false);
        table.setRowHeight(Theme.scaled(30));
        table.setSurrendersFocusOnKeystroke(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.putClientProperty("JTable.autoStartsEdit", Boolean.TRUE);
        table.getTableHeader().setReorderingAllowed(false);

        TableColumn on = table.getColumnModel().getColumn(COL_ON);
        on.setMaxWidth(36);
        on.setMinWidth(36);
        on.setCellRenderer(new EnabledRenderer());
        TableColumn del = table.getColumnModel().getColumn(COL_DELETE);
        del.setMaxWidth(30);
        del.setMinWidth(30);
        del.setCellRenderer(new DeleteRenderer());
        table.getColumnModel().getColumn(COL_KEY).setPreferredWidth(220);
        table.getColumnModel().getColumn(COL_VALUE).setPreferredWidth(380);

        table.getColumnModel().getColumn(COL_KEY).setCellRenderer(new VarRenderer(true));
        table.getColumnModel().getColumn(COL_VALUE).setCellRenderer(new VarRenderer(false));

        VarTextField keyEditor = new VarTextField(resolver, actions, true);
        keyEditor.setBorder(Ui.pad(0, 6, 0, 6));
        if (keySuggest != null) keyEditor.setSuggestions(keySuggest);
        VarTextField valueEditor = new VarTextField(resolver, actions, true);
        valueEditor.setBorder(Ui.pad(0, 6, 0, 6));
        if (valueSuggest != null) {
            valueEditor.setSuggestions(text -> {
                int r = table.getEditingRow();
                String key = r >= 0 && r < rows.size() ? rows.get(r).key : "";
                return valueSuggest.apply(key, text);
            });
        }
        DefaultCellEditor ke = new DefaultCellEditor(keyEditor);
        ke.setClickCountToStart(1);
        DefaultCellEditor ve = new DefaultCellEditor(valueEditor);
        ve.setClickCountToStart(1);
        table.getColumnModel().getColumn(COL_KEY).setCellEditor(ke);
        table.getColumnModel().getColumn(COL_VALUE).setCellEditor(ve);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                int c = table.columnAtPoint(e.getPoint());
                if (c == COL_DELETE && r >= 0 && r < rows.size() - 1) removeRows(new int[]{r});
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setHover(table.rowAtPoint(e.getPoint()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHover(-1);
            }
        };
        table.addMouseListener(mouse);
        table.addMouseMotionListener(mouse);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new java.awt.Dimension(0, 1));
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "rc-delete");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "rc-delete");
        table.getActionMap().put("rc-delete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!table.isEditing()) removeRows(table.getSelectedRows());
            }
        });

        // No box around the table: the header rule, row dividers and alignment already group the rows
        // (Practical UI, "Group related elements"), and the extra frame only added clutter.
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        // The table sits on the page background, so it reads as part of the page rather than a box.
        Theme.bg(sp.getViewport(), Theme::panelBg);
        Theme.bg(table, Theme::panelBg);
        Theme.bg(table.getTableHeader(), Theme::panelBg);
        add(sp);
    }

    public JTable table() {
        return table;
    }

    public void onChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    /** Replaces the rows (copies them). Does not fire onChange. */
    public void setRows(List<KeyValue> list) {
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        rows.clear();
        for (KeyValue kv : list) rows.add(kv.copy());
        rows.add(new KeyValue());
        firing = true;
        model.fireTableDataChanged();
        firing = false;
    }

    /** Non-empty rows, copied. */
    public List<KeyValue> getRows() {
        List<KeyValue> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            KeyValue kv = rows.get(i);
            if (i == rows.size() - 1 && kv.isBlank()) continue;
            if (kv.isBlank()) continue;
            out.add(kv.copy());
        }
        return out;
    }

    public int enabledCount() {
        int n = 0;
        for (KeyValue kv : getRows()) if (kv.enabled) n++;
        return n;
    }

    public void stopEditing() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
    }

    public void refreshVariables() {
        table.repaint();
    }

    private void setHover(int r) {
        if (r == hoverRow) return;
        hoverRow = r;
        table.repaint();
    }

    private Color rowBackground(JTable t, int row, boolean selected) {
        boolean placeholderRow = row == rows.size() - 1;
        if (selected && !placeholderRow) return t.getSelectionBackground();
        if (row == hoverRow) return Theme.mix(t.getBackground(), Theme.text(), 0.035f);
        return t.getBackground();
    }

    private void removeRows(int[] indexes) {
        if (indexes.length == 0) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        for (int i = indexes.length - 1; i >= 0; i--) {
            int r = indexes[i];
            if (r >= 0 && r < rows.size() - 1) rows.remove(r);
        }
        model.fireTableDataChanged();
        onChange.run();
    }

    private String variablesTooltip(String text) {
        List<VariableResolver.Token> tokens = VariableResolver.tokens(text);
        if (tokens.isEmpty()) return null;
        VariableResolver r = resolver.get();
        StringBuilder sb = new StringBuilder("<html>");
        for (VariableResolver.Token t : tokens) {
            var res = r.lookup(t.name());
            sb.append("<b>").append(Ui.escape(t.name())).append("</b> ");
            if (res.isEmpty()) {
                sb.append("— not defined");
            } else {
                String v = res.get().secret() ? VariableResolver.MASK : res.get().value();
                sb.append("= ").append(Ui.escape(v)).append(" <span style='color:gray'>(")
                        .append(Ui.escape(res.get().sourceName())).append(")</span>");
            }
            sb.append("<br>");
        }
        return sb.append("</html>").toString();
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case COL_KEY -> "Key";
                case COL_VALUE -> "Value";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == COL_ON ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            if (c == COL_DELETE) return false;
            if (c == COL_ON) return r < rows.size() - 1;
            return true;
        }

        @Override
        public Object getValueAt(int r, int c) {
            KeyValue kv = rows.get(r);
            return switch (c) {
                case COL_ON -> kv.enabled;
                case COL_KEY -> kv.key;
                case COL_VALUE -> kv.value;
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (r >= rows.size()) return;
            KeyValue kv = rows.get(r);
            switch (c) {
                case COL_ON -> kv.enabled = Boolean.TRUE.equals(v);
                case COL_KEY -> kv.key = v == null ? "" : v.toString();
                case COL_VALUE -> kv.value = v == null ? "" : v.toString();
                default -> {
                    return;
                }
            }
            fireTableRowsUpdated(r, r);
            if (r == rows.size() - 1 && !kv.isBlank()) {
                rows.add(new KeyValue());
                fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            }
            if (!firing) onChange.run();
        }
    }

    /** Renders key/value cells as a text field so variable chips paint exactly like in editors. */
    private final class VarRenderer implements TableCellRenderer {
        private final JTextField field = new JTextField();
        private final boolean isKey;

        VarRenderer(boolean isKey) {
            this.isKey = isKey;
            field.setBorder(Ui.pad(0, 7, 0, 7));
            field.setEditable(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            boolean placeholderRow = row == rows.size() - 1;
            boolean enabled = row < rows.size() && rows.get(row).enabled;
            field.setFont(Theme.mono(12.5f));
            field.setText(value == null ? "" : value.toString());
            Ui.placeholder(field, placeholderRow ? (isKey ? keyPlaceholder : valuePlaceholder) : null);
            field.setForeground(enabled || placeholderRow ? t.getForeground() : Theme.secondary());
            field.setBackground(rowBackground(t, row, selected));
            if (selected && !placeholderRow) field.setForeground(t.getSelectionForeground());
            VarSupport.highlightOnly(field, resolver.get());
            return field;
        }
    }

    private final class EnabledRenderer implements TableCellRenderer {
        private final JCheckBox box = new JCheckBox();
        private final JLabel empty = new JLabel();

        EnabledRenderer() {
            box.setHorizontalAlignment(SwingConstants.CENTER);
            box.setOpaque(true);
            empty.setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            Color bg = rowBackground(t, row, selected);
            if (row == rows.size() - 1) {
                empty.setBackground(bg);
                return empty;
            }
            box.setSelected(Boolean.TRUE.equals(value));
            box.setBackground(bg);
            box.getAccessibleContext().setAccessibleName("Enable row " + (row + 1));
            return box;
        }
    }

    private final class DeleteRenderer extends DefaultTableCellRenderer {
        DeleteRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, "", selected, false, row, col);
            setBackground(rowBackground(t, row, selected));
            // The remove button only appears on the row you're pointing at (or selected rows), so the table
            // isn't a column of x's.
            boolean show = row != rows.size() - 1 && (row == hoverRow || selected);
            setIcon(show ? Icons.get("trash", 13) : null);
            setToolTipText(row == rows.size() - 1 ? null : "Remove row");
            return this;
        }
    }
}
