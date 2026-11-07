package dev.restclient.ui;

import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.ActionListener;

/** Shown when no tabs are open: three clear starting points. */
public class EmptyStatePanel extends JPanel {
    public EmptyStatePanel(MainFrame frame) {
        super(new GridBagLayout());
        JPanel col = Ui.transparent(null);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel icon = new JLabel(new ImageIcon(Theme.appIcon(64)));
        icon.setAlignmentX(0.5f);
        col.add(icon);
        col.add(Ui.vgap(16));
        JLabel title = Ui.heading("Send your first request", 24f);
        title.setAlignmentX(0.5f);
        col.add(title);
        col.add(Ui.vgap(8));
        JLabel sub = Ui.secondary("<html><div style='text-align:center;width:315px'>Start from scratch, paste a cURL command, "
                + "or set up a collection for an API you work with often.</div></html>");
        sub.setHorizontalAlignment(SwingConstants.CENTER);
        sub.setAlignmentX(0.5f);
        col.add(sub);
        col.add(Ui.vgap(26));

        JPanel cards = Ui.transparent(new GridLayout(1, 3, 12, 0));
        cards.add(card("plus", "New Request", Keys.NEW_TAB, true, e -> frame.newTab()));
        cards.add(card("import", "Import cURL", Keys.IMPORT_CURL, false, e -> frame.showCurlImport(null)));
        cards.add(card("collection", "New Collection", Keys.NEW_COLLECTION, false, e -> frame.newCollection()));
        cards.setMaximumSize(new Dimension(640, 120));
        cards.setAlignmentX(0.5f);
        col.add(cards);
        col.add(Ui.vgap(24));

        JPanel tip = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 8, 0));
        tip.add(new Ui.Pill("Tip", Theme::subtleBg, Theme::secondary));
        tip.add(Ui.secondary("Paste a curl command into any URL field and it's imported automatically."));
        tip.setAlignmentX(0.5f);
        col.add(tip);
        add(col);
    }

    /** Large tile button; the primary one gets an accent border that follows the theme. */
    private static final class CardButton extends JButton {
        private boolean primary;

        void setPrimary(boolean p) {
            primary = p;
            restyle();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            restyle();
        }

        private void restyle() {
            putClientProperty("rc.arc", 14);
            putClientProperty("rc.borderColor", primary ? Theme.accent() : null);
            putClientProperty("rc.borderWidth", primary ? 2 : 1);
        }
    }

    private static JButton card(String icon, String title, KeyStroke ks, boolean primary, ActionListener a) {
        CardButton b = new CardButton();
        b.setPrimary(primary);
        b.setLayout(new BorderLayout(0, 6));
        JLabel i = new JLabel(Icons.get(icon, 22));
        i.setHorizontalAlignment(SwingConstants.LEFT);
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() + 1f));
        JLabel k = Ui.small(Keys.label(ks));
        JPanel texts = Ui.transparent(new BorderLayout(0, 2));
        texts.add(t, BorderLayout.NORTH);
        texts.add(k, BorderLayout.SOUTH);
        b.add(i, BorderLayout.NORTH);
        b.add(texts, BorderLayout.CENTER);
        b.setMargin(new java.awt.Insets(14, 14, 14, 14));
        b.getAccessibleContext().setAccessibleName(title);
        b.setPreferredSize(new Dimension(Theme.scaled(200), Theme.scaled(110)));
        b.addActionListener(a);
        return b;
    }
}
