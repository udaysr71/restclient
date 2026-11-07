package dev.restclient.ui;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Graphics;

/** Left column: Collections / History switcher. */
public class Sidebar extends JPanel {
    private final Ui.Segmented switcher = new Ui.Segmented("Collections", "History");
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final CollectionsPanel collections;
    private final HistoryPanel history;

    public Sidebar(AppContext ctx, MainFrame frame) {
        super(new BorderLayout(0, 10));
        setBorder(Ui.pad(12, 10, 10, 10));
        collections = new CollectionsPanel(ctx, frame);
        history = new HistoryPanel(ctx, frame);
        content.setOpaque(false);
        content.add(collections, "collections");
        content.add(history, "history");
        switcher.onChange(i -> cards.show(content, i == 0 ? "collections" : "history"));
        add(switcher, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.sidebarBg());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    public CollectionsPanel collections() {
        return collections;
    }

    public HistoryPanel history() {
        return history;
    }

    public void showTab(String name) {
        boolean h = "history".equals(name);
        switcher.setSelectedIndex(h ? 1 : 0);
        cards.show(content, h ? "history" : "collections");
    }

    public String currentTab() {
        return switcher.selectedIndex() == 1 ? "history" : "collections";
    }
}
