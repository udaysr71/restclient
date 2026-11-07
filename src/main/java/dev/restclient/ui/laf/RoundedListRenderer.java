package dev.restclient.ui.laf;

import dev.restclient.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.function.Supplier;

/**
 * Wraps a list renderer so rows in popups (dropdowns, autocomplete) look like menu rows: a rounded, inset,
 * tinted highlight instead of a full-width bar, and the row's own colours kept (a red DELETE stays red when
 * highlighted, so the highlight never hides what it marks).
 */
public final class RoundedListRenderer implements ListCellRenderer<Object> {
    private final Supplier<ListCellRenderer<Object>> delegate;
    private final Row row = new Row();
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ListCellRenderer<Object> fallback = (ListCellRenderer) new javax.swing.DefaultListCellRenderer();

    public RoundedListRenderer(Supplier<ListCellRenderer<Object>> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
        @SuppressWarnings("unchecked")
        JList<Object> l = (JList<Object>) list;
        ListCellRenderer<Object> d = delegate.get();
        if (d == null) d = fallback; // e.g. a combo box whose renderer isn't installed yet
        Component c = d.getListCellRendererComponent(l, value, index, false, false);
        if (c instanceof JComponent jc) jc.setOpaque(false);
        row.removeAll();
        row.add(c);
        row.selected = selected;
        row.setBackground(list.getBackground());
        return row;
    }

    private static final class Row extends JPanel {
        boolean selected;

        Row() {
            super(new BorderLayout());
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (selected) {
                Graphics2D g2 = Paint.smooth(g);
                Paint.fill(g2, 4, 1, getWidth() - 8, getHeight() - 2, 7, Theme.selectionBg());
                g2.dispose();
            }
        }
    }
}
