package dev.restclient.ui;

import dev.restclient.store.AppPaths;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Bottom bar: TLS state on the left, transient messages (with optional Undo) on the right. */
public class StatusBar extends JPanel {
    private final AppContext ctx;
    private final JLabel tls = new JLabel();
    private final JLabel message = Ui.small("");
    private final JButton action = Ui.linkButton("", null);
    private final Timer clear;
    private Runnable actionRunnable;

    public StatusBar(AppContext ctx, Runnable openCertificates) {
        super(new BorderLayout(16, 0));
        this.ctx = ctx;
        setBorder(Ui.pad(4, 14, 4, 14));
        tls.setFont(tls.getFont().deriveFont(tls.getFont().getSize2D() - 1.5f));
        tls.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tls.setToolTipText("Open Settings › Certificates");
        tls.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openCertificates.run();
            }
        });
        add(tls, BorderLayout.WEST);
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        action.setFont(action.getFont().deriveFont(action.getFont().getSize2D() - 1.5f));
        action.setVisible(false);
        action.addActionListener(e -> {
            Runnable r = actionRunnable;
            show(null, null, null);
            if (r != null) r.run();
        });
        right.add(message);
        right.add(action);
        add(right, BorderLayout.EAST);
        clear = new Timer(9000, e -> show(null, null, null));
        clear.setRepeats(false);
        refresh();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.toolbarBg());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Theme.separator());
        g.fillRect(0, 0, getWidth(), 1);
    }

    public void refresh() {
        boolean insecure = ctx.settings().insecureByDefault;
        int added = ctx.tls.addedTrustedCount();
        long failed = ctx.tls.failedCount();
        String certs = added == 0 ? "system trust store" : added + " extra certificate" + (added == 1 ? "" : "s") + " from "
                + AppPaths.display(ctx.tls.certsDir());
        String text = (insecure ? "TLS verification off by default" : "TLS verification on") + " · " + certs;
        if (failed > 0) text += " · " + failed + " file" + (failed == 1 ? "" : "s") + " couldn't load";
        tls.setText(text);
        tls.setIcon(Icons.get(insecure || failed > 0 ? "lock-open" : "lock", 12));
        tls.setForeground(failed > 0 || insecure ? Theme.warnFg() : Theme.secondary());
    }

    /** Shows a message for a few seconds, optionally with an action link such as "Undo". */
    public void show(String text, String actionLabel, Runnable actionRun) {
        message.setText(text == null ? "" : text);
        actionRunnable = actionRun;
        action.setText(actionLabel == null ? "" : actionLabel);
        action.setVisible(actionLabel != null && actionRun != null);
        if (text != null) clear.restart();
        else clear.stop();
        revalidate();
        repaint();
    }
}
