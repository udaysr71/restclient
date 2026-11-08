package dev.restclient.ui;

import dev.restclient.model.KeyValue;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The response's headers: a two-column table styled like the request's Params and Headers tables (padded
 * rows, code font, hover, no box), with columns you can resize by dragging the divider in the header
 * (double-click it to fit the names), and copying:
 * <ul>
 *   <li>Ctrl/Cmd+C copies the selected rows as {@code Name: Value} lines;</li>
 *   <li>right-click offers Copy Value, Copy Name, Copy Row(s) and Copy All Headers;</li>
 *   <li>{@link #copyAll()} is what the response toolbar's Copy button does while this view is showing.</li>
 * </ul>
 */
final class HeadersView extends JPanel {
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private int hoverRow = -1;
    private boolean fitted;

    HeadersView() {
        super(new BorderLayout());
        // the same inset as the request editors, so the two halves of the window line up
        setBorder(Ui.pad(8, 14, 10, 14));
        Theme.bg(this, Theme::panelBg);

        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getAccessibleContext().setAccessibleName("Response headers");
        Renderer r = new Renderer();
        table.setDefaultRenderer(Object.class, r);
        rowHeight();
        Theme.onChange(this::rowHeight);

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setResizingAllowed(true);
        header.setDefaultRenderer(new HeaderRenderer());
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // double-click the divider: fit the Name column to the longest name
                if (e.getClickCount() == 2 && nearDivider(e.getX())) fitNames(true);
            }
        });
        header.setToolTipText("Drag the divider to resize; double-click it to fit the names");

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row != hoverRow) {
                    hoverRow = row;
                    table.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverRow = -1;
                table.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                menu(row, table.columnAtPoint(e.getPoint())).show(table, e.getX(), e.getY());
            }
        };
        table.addMouseListener(mouse);
        table.addMouseMotionListener(mouse);

        // Ctrl/Cmd+C copies "Name: Value" lines (the default copied tab-separated cells)
        table.getInputMap(JComponent.WHEN_FOCUSED).put(Keys.mod(java.awt.event.KeyEvent.VK_C), "rc-copy");
        table.getActionMap().put("rc-copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelected();
            }
        });
        // Shift+F10 / the context-menu key, for keyboard users
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F10, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "rc-menu");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_CONTEXT_MENU, 0), "rc-menu");
        table.getActionMap().put("rc-menu", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int row = Math.max(0, table.getSelectedRow());
                if (model.getRowCount() == 0) return;
                if (table.getSelectedRow() < 0) table.setRowSelectionInterval(0, 0);
                java.awt.Rectangle cell = table.getCellRect(row, 1, true);
                menu(row, 1).show(table, cell.x + 12, cell.y + cell.height);
            }
        });

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        Theme.bg(sp.getViewport(), Theme::panelBg);
        Theme.bg(table, Theme::panelBg);
        Theme.bg(header, Theme::panelBg);
        add(sp, BorderLayout.CENTER);
    }

    void setHeaders(List<KeyValue> headers) {
        model.rows = new ArrayList<>(headers);
        model.fireTableDataChanged();
        fitted = false;
        if (isShowing()) fitNames(false);
    }

    int count() {
        return model.getRowCount();
    }

    JTable table() {
        return table;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> fitNames(false));
    }

    @Override
    public void doLayout() {
        super.doLayout();
        if (!fitted && getWidth() > 0) fitNames(false);
    }

    /** All headers as {@code Name: Value} lines, the way they'd appear on the wire. */
    String allText() {
        return lines(rowsOf(0, model.getRowCount()));
    }

    void copyAll() {
        put(allText());
    }

    void copySelected() {
        int[] sel = table.getSelectedRows();
        if (sel.length == 0) {
            copyAll();
            return;
        }
        List<KeyValue> rows = new ArrayList<>();
        for (int i : sel) rows.add(model.rows.get(table.convertRowIndexToModel(i)));
        put(lines(rows));
    }

    private List<KeyValue> rowsOf(int from, int to) {
        return new ArrayList<>(model.rows.subList(from, to));
    }

    private static String lines(List<KeyValue> rows) {
        StringBuilder b = new StringBuilder();
        for (KeyValue kv : rows) {
            if (!b.isEmpty()) b.append('\n');
            b.append(kv.key).append(": ").append(kv.value);
        }
        return b.toString();
    }

    private static void put(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private JPopupMenu menu(int row, int col) {
        JPopupMenu m = new JPopupMenu();
        int selected = table.getSelectedRowCount();
        KeyValue kv = row >= 0 ? model.rows.get(table.convertRowIndexToModel(row)) : null;
        if (kv != null) {
            m.add(item("Copy Value", () -> put(kv.value)));
            m.add(item("Copy Name", () -> put(kv.key)));
            JMenuItem rows = item(selected > 1 ? "Copy " + selected + " Rows" : "Copy Row", this::copySelected);
            rows.setAccelerator(Keys.mod(java.awt.event.KeyEvent.VK_C));
            m.add(rows);
            m.addSeparator();
        }
        m.add(item("Copy All Headers", this::copyAll));
        return m;
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        return i;
    }

    private void rowHeight() {
        table.setRowHeight(Theme.scaled(30));
        fitted = false;
    }

    /** Is x within a few pixels of the divider between Name and Value? */
    private boolean nearDivider(int x) {
        int edge = table.getColumnModel().getColumn(0).getWidth();
        return Math.abs(x - edge) <= 4;
    }

    /**
     * Sizes the Name column to the longest name (plus padding), between 120 px and 45% of the width. The user's
     * own drag is kept until new headers arrive, unless {@code force}.
     */
    private void fitNames(boolean force) {
        if (table.getWidth() <= 0 && getWidth() <= 0) return;
        if (fitted && !force) return;
        FontMetrics fm = table.getFontMetrics(Theme.mono(12.5f));
        int longest = fm.stringWidth("Name");
        for (KeyValue kv : model.rows) longest = Math.max(longest, fm.stringWidth(kv.key));
        int total = Math.max(table.getWidth(), getWidth() - 28);
        int want = Math.max(Theme.scaled(120), Math.min((int) (total * 0.45), longest + Theme.scaled(36)));
        TableColumn name = table.getColumnModel().getColumn(0);
        TableColumn value = table.getColumnModel().getColumn(1);
        name.setPreferredWidth(want);
        name.setWidth(want);
        value.setPreferredWidth(Math.max(Theme.scaled(120), total - want));
        table.doLayout();
        fitted = true;
    }

    // ---- model and renderers ----------------------------------------------------------------

    private static final class Model extends AbstractTableModel {
        List<KeyValue> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Name" : "Value";
        }

        @Override
        public Object getValueAt(int r, int c) {
            KeyValue kv = rows.get(r);
            return c == 0 ? kv.key : kv.value;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    }

    private final class Renderer extends JLabel implements TableCellRenderer {
        Renderer() {
            setOpaque(true);
            setBorder(Ui.pad(0, 10, 0, 10));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            String s = value == null ? "" : value.toString();
            setText(s);
            // the font is looked up each time, so it follows the code font and zoom settings
            setFont(Theme.mono(12.5f));
            setForeground(col == 0 ? Theme.secondary() : Theme.text());
            Color bg = selected ? Theme.selectionBg() : row == hoverRow ? over(Theme.hoverSubtle(), Theme.panelBg()) : Theme.panelBg();
            setBackground(bg);
            // a long value shows in full on hover
            FontMetrics fm = getFontMetrics(getFont());
            int avail = t.getColumnModel().getColumn(col).getWidth() - 20;
            setToolTipText(fm.stringWidth(s) > avail ? s : null);
            return this;
        }
    }

    /** A translucent colour laid over an opaque one. */
    private static Color over(Color top, Color under) {
        float a = top.getAlpha() / 255f;
        return new Color(Math.round(top.getRed() * a + under.getRed() * (1 - a)),
                Math.round(top.getGreen() * a + under.getGreen() * (1 - a)),
                Math.round(top.getBlue() * a + under.getBlue() * (1 - a)));
    }

    /** Column titles with a visible divider, so it's clear the columns can be resized. */
    private static final class HeaderRenderer extends JLabel implements TableCellRenderer {
        private boolean last;

        HeaderRenderer() {
            setOpaque(true);
            setBorder(Ui.pad(6, 10, 6, 10));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            setText(value == null ? "" : value.toString());
            setFont(t.getTableHeader().getFont());
            setForeground(Theme.secondary());
            setBackground(Theme.panelBg());
            last = col == t.getColumnCount() - 1;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Theme.separator());
            g.fillRect(0, getHeight() - 1, getWidth(), 1);
            if (!last) {
                // the drag handle: a short vertical rule centred on the column edge
                int h = getHeight();
                g.setColor(Theme.fieldBorder());
                g.fillRect(getWidth() - 1, h / 4, 1, h / 2);
            }
        }
    }
}
