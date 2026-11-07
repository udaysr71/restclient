package dev.restclient.ui;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;

/** A row of text tabs with an accent underline on the selected one, e.g. Params · Headers · Body. */
public class UnderlineTabs extends JPanel {
    private final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0)) {
        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Theme.panelBg());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Theme.separator());
            g.fillRect(0, getHeight() - 1, getWidth(), 1);
            java.awt.Rectangle target = targetIndicator();
            if (target == null) return;
            double x = indicatorAnim != null ? indicatorX : target.x;
            double w = indicatorAnim != null ? indicatorW : target.width;
            ((java.awt.Graphics2D) g).setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.accent());
            ((java.awt.Graphics2D) g).fill(new java.awt.geom.RoundRectangle2D.Double(x, getHeight() - 2.5, w, 2.5, 2.5, 2.5));
        }
    };
    /** The underline slides between tabs; these hold its position while it moves. */
    private double indicatorX;
    private double indicatorW;
    private Motion.Handle indicatorAnim;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final List<JButton> buttons = new ArrayList<>();
    private int selected = -1;

    public UnderlineTabs() {
        super(new BorderLayout());
        bar.setBorder(Ui.pad(0, 0, 0, 0));
        add(bar, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    public void addTab(String title, Component c) {
        int index = buttons.size();
        JButton b = new JButton(title);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setMargin(new java.awt.Insets(9, 0, 9, 0));
        b.setRolloverEnabled(false);
        b.addActionListener(e -> setSelectedIndex(index));
        buttons.add(b);
        bar.add(b);
        content.add(c, String.valueOf(index));
        if (selected < 0) setSelectedIndex(0);
    }

    public int getTabCount() {
        return buttons.size();
    }

    public String getTitleAt(int i) {
        return buttons.get(i).getText();
    }

    public void setTitleAt(int i, String title) {
        JButton b = buttons.get(i);
        if (!title.equals(b.getText())) {
            b.setText(title);
            bar.revalidate();
        }
    }

    public int getSelectedIndex() {
        return selected;
    }

    private java.awt.Rectangle targetIndicator() {
        if (selected < 0 || selected >= buttons.size()) return null;
        JButton b = buttons.get(selected);
        return b.getWidth() == 0 ? null : new java.awt.Rectangle(b.getX(), 0, b.getWidth(), 0);
    }

    public void setSelectedIndex(int i) {
        if (i < 0 || i >= buttons.size()) return;
        java.awt.Rectangle from = targetIndicator();
        int prev = selected;
        selected = i;
        if (Motion.enabled() && from != null && prev != i && bar.isShowing()) {
            if (indicatorAnim != null) {
                indicatorAnim.stop();
                from = new java.awt.Rectangle((int) indicatorX, 0, (int) indicatorW, 0);
            }
            java.awt.Rectangle start = from;
            indicatorAnim = Motion.animate(220, t -> {
                java.awt.Rectangle to = targetIndicator();
                if (to == null) return;
                indicatorX = Motion.lerp(start.x, to.x, t);
                indicatorW = Motion.lerp(start.width, to.width, t);
                bar.repaint();
            }, () -> {
                indicatorAnim = null;
                bar.repaint();
            });
        }
        cards.show(content, String.valueOf(i));
        for (int k = 0; k < buttons.size(); k++) {
            JButton b = buttons.get(k);
            b.setFont(Theme.uiFont().deriveFont(k == i ? Font.BOLD : Font.PLAIN));
            b.setForeground(k == i ? Theme.text() : Theme.secondary());
            b.repaint();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (buttons != null && selected >= 0) setSelectedIndex(selected);
    }
}
