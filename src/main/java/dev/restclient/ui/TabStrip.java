package dev.restclient.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Document tabs: a scrollable row of card-style tabs with close buttons and a trailing "+" area,
 * above the selected tab's content. Middle-click closes. API mirrors the bits of JTabbedPane the app used.
 */
public class TabStrip extends JPanel {
    private final List<Tab> tabs = new ArrayList<>();
    private final JPanel row = new JPanel();
    private final JPanel trailing = Ui.transparent(new BorderLayout());
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final List<ChangeListener> listeners = new ArrayList<>();
    private final JScrollPane rowScroll;
    private IntConsumer closeHandler = i -> removeTabAt(i);
    private java.util.function.IntFunction<javax.swing.JPopupMenu> menuProvider;
    private int selected = -1;
    private int counter;

    public TabStrip() {
        super(new BorderLayout());
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        rowScroll = new JScrollPane(row, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rowScroll.setBorder(BorderFactory.createEmptyBorder());
        rowScroll.setOpaque(false);
        rowScroll.getViewport().setOpaque(false);
        rowScroll.addMouseWheelListener(e -> {
            java.awt.Point p = rowScroll.getViewport().getViewPosition();
            int max = Math.max(0, row.getWidth() - rowScroll.getViewport().getWidth());
            p.x = Math.max(0, Math.min(max, p.x + e.getWheelRotation() * 40));
            rowScroll.getViewport().setViewPosition(p);
        });
        JPanel header = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.toolbarBg());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.separator());
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        header.setBorder(Ui.pad(5, 6, 0, 6));
        header.add(rowScroll, BorderLayout.CENTER);
        trailing.setBorder(Ui.pad(0, 4, 3, 0));
        // When tabs don't fit, a list button shows every tab, so hidden ones are one click away.
        overflow.setVisible(false);
        trailing.add(overflow, BorderLayout.EAST);
        header.add(trailing, BorderLayout.EAST);
        rowScroll.getViewport().addChangeListener(e -> updateOverflow());
        row.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateOverflow();
            }
        });
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    public void setTrailingComponent(Component c) {
        trailing.removeAll();
        trailing.add(c, BorderLayout.WEST);
        trailing.add(overflow, BorderLayout.EAST);
        trailing.revalidate();
    }

    private final javax.swing.JButton overflow = Ui.toolbarButton(Icons.get("chevron-down", 14), "Show all tabs", e -> showAllTabs());

    private void updateOverflow() {
        boolean over = row.getPreferredSize().width > rowScroll.getViewport().getWidth() + 1;
        if (over != overflow.isVisible()) {
            overflow.setVisible(over);
            trailing.revalidate();
        }
    }

    private void showAllTabs() {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        for (int i = 0; i < tabs.size(); i++) {
            int index = i;
            Component label = tabs.get(i).label;
            String text = label instanceof javax.swing.JLabel l ? l.getText() : tabs.get(i).head.getToolTipText();
            if (text == null && label instanceof java.awt.Container k) {
                StringBuilder sb = new StringBuilder();
                for (Component part : k.getComponents()) {
                    if (part instanceof javax.swing.JLabel pl && pl.getText() != null && !pl.getText().isEmpty() && pl.isVisible()) {
                        if (sb.length() > 0) sb.append("  ");
                        sb.append(pl.getText());
                    }
                }
                text = sb.toString();
            }
            javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(text, i == selected);
            item.addActionListener(e -> setSelectedIndex(index));
            m.add(item);
        }
        Ui.showBelow(m, overflow);
    }

    /** Called when a tab's close button (or middle-click) asks to close it. */
    public void setCloseHandler(IntConsumer handler) {
        this.closeHandler = handler;
    }

    /** Supplies the right-click menu for the tab at an index. */
    public void setTabMenuProvider(java.util.function.IntFunction<javax.swing.JPopupMenu> provider) {
        this.menuProvider = provider;
    }

    /** Moves a tab to another position (drag to reorder), keeping it selected if it was. */
    public void moveTab(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= tabs.size() || to >= tabs.size()) return;
        Component sel = getSelectedComponent();
        Tab t = tabs.remove(from);
        tabs.add(to, t);
        row.remove(t.head);
        row.add(t.head, to);
        row.revalidate();
        row.repaint();
        selected = sel == null ? -1 : indexOfComponent(sel);
        fire();
    }

    public void addChangeListener(ChangeListener l) {
        listeners.add(l);
    }

    private void fire() {
        ChangeEvent e = new ChangeEvent(this);
        for (ChangeListener l : listeners) l.stateChanged(e);
    }

    // ---- JTabbedPane-like API --------------------------------------------------------------

    public void addTab(String title, Component c) {
        javax.swing.SwingUtilities.invokeLater(this::updateOverflow);
        Tab t = new Tab(c, "tab" + (counter++));
        tabs.add(t);
        content.add(c, t.key);
        row.add(t.head);
        t.setLabel(new javax.swing.JLabel(title));
        row.revalidate();
        if (selected < 0) setSelectedIndex(0);
    }

    public int getTabCount() {
        return tabs.size();
    }

    public Component getComponentAt(int i) {
        return tabs.get(i).content;
    }

    public int indexOfComponent(Component c) {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).content == c) return i;
        return -1;
    }

    public int getSelectedIndex() {
        return selected;
    }

    public Component getSelectedComponent() {
        return selected >= 0 && selected < tabs.size() ? tabs.get(selected).content : null;
    }

    public void setSelectedIndex(int i) {
        if (i < 0 || i >= tabs.size()) return;
        boolean changed = i != selected;
        selected = i;
        Tab t = tabs.get(i);
        cards.show(content, t.key);
        for (Tab x : tabs) x.head.repaint();
        SwingUtilities.invokeLater(() -> row.scrollRectToVisible(t.head.getBounds()));
        if (changed) fire();
    }

    public void removeTabAt(int i) {
        javax.swing.SwingUtilities.invokeLater(this::updateOverflow);
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.remove(i);
        content.remove(t.content);
        row.remove(t.head);
        row.revalidate();
        row.repaint();
        if (tabs.isEmpty()) {
            selected = -1;
            fire();
        } else {
            int next = i < selected ? selected - 1 : i == selected ? Math.min(i, tabs.size() - 1) : selected;
            selected = -1;
            setSelectedIndex(next);
        }
    }

    public void setTabComponentAt(int i, JComponent c) {
        tabs.get(i).setLabel(c);
    }

    public Component getTabComponentAt(int i) {
        return tabs.get(i).label;
    }

    public void setToolTipTextAt(int i, String tip) {
        tabs.get(i).head.setToolTipText(tip);
    }

    // ---- one tab ---------------------------------------------------------------------------

    private final class Tab {
        final Component content;
        final String key;
        final Head head = new Head();
        Component label;

        Tab(Component content, String key) {
            this.content = content;
            this.key = key;
        }

        void setLabel(Component c) {
            if (label != null) head.remove(label);
            label = c;
            head.add(c, BorderLayout.CENTER);
            head.revalidate();
            head.repaint();
        }

        private int index() {
            return tabs.indexOf(this);
        }

        final class Head extends JPanel {
            private final JButton close = Ui.toolbarButton(Icons.get("close", 10), "Close tab (" + Keys.label(Keys.CLOSE_TAB) + ")",
                    e -> closeHandler.accept(index()));
            private boolean hover;

            Head() {
                super(new BorderLayout(4, 0));
                setOpaque(false);
                setBorder(Ui.pad(0, 12, 0, 4));
                close.setPreferredSize(new Dimension(Theme.scaled(20), Theme.scaled(20)));
                close.setMargin(new java.awt.Insets(0, 0, 0, 0));
                JPanel cw = Ui.transparent(new java.awt.GridBagLayout());
                cw.add(close);
                add(cw, BorderLayout.EAST);
                MouseAdapter m = new MouseAdapter() {
                    private int pressX = -1;
                    private boolean dragging;

                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            setSelectedIndex(index());
                            pressX = e.getXOnScreen();
                            dragging = false;
                        }
                        if (e.isPopupTrigger()) showMenu(e);
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        if (e.isPopupTrigger()) showMenu(e);
                        if (dragging) setCursor(java.awt.Cursor.getDefaultCursor());
                        pressX = -1;
                        dragging = false;
                    }

                    @Override
                    public void mouseDragged(MouseEvent e) {
                        if (pressX < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
                        if (!dragging && Math.abs(e.getXOnScreen() - pressX) < 6) return;
                        dragging = true;
                        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
                        // Drop position: the tab whose middle the pointer has passed.
                        java.awt.Point p = SwingUtilities.convertPoint(Head.this, e.getPoint(), row);
                        int from = index();
                        int to = from;
                        for (int i = 0; i < tabs.size(); i++) {
                            java.awt.Rectangle b = tabs.get(i).head.getBounds();
                            if (p.x >= b.x && p.x < b.x + b.width) {
                                boolean pastMiddle = i > from ? p.x > b.x + b.width / 2 : p.x < b.x + b.width / 2;
                                if (pastMiddle) to = i;
                            }
                        }
                        moveTab(from, to);
                    }

                    private void showMenu(MouseEvent e) {
                        if (menuProvider == null) return;
                        javax.swing.JPopupMenu menu = menuProvider.apply(index());
                        if (menu != null) menu.show(Head.this, e.getX(), e.getY());
                    }

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isMiddleMouseButton(e)) closeHandler.accept(index());
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = getMousePosition(true) != null;
                        repaint();
                    }
                };
                addMouseListener(m);
                addMouseMotionListener(m);
                close.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }
                });
            }

            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                d.height = Theme.scaled(34);
                d.width = Math.max(Theme.scaled(110), Math.min(Theme.scaled(260), d.width));
                return d;
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }

            @Override
            protected void paintComponent(Graphics g) {
                boolean sel = index() == selected;
                close.setVisible(sel || hover);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (sel) {
                    RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h + 8, 10, 10);
                    g2.setColor(Theme.editorBg());
                    g2.fill(r);
                    g2.setColor(Theme.separator());
                    g2.draw(r);
                } else if (hover) {
                    g2.setColor(Theme.hoverSubtle());
                    g2.fill(new RoundRectangle2D.Float(2, 3, w - 4, h - 6, 8, 8));
                } else {
                    g2.setColor(Theme.separator());
                    g2.fillRect(w - 1, 10, 1, h - 20);
                }
                g2.dispose();
            }
        }
    }
}
