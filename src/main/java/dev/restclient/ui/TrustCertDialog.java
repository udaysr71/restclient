package dev.restclient.ui;

import dev.restclient.http.TlsManager;
import dev.restclient.store.AppPaths;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Shows a server's certificate chain and saves the chosen certificate into the certs folder. */
public final class TrustCertDialog {
    private final MainFrame frame;
    private final AppContext ctx;
    private final X509Certificate[] chain;
    private final JDialog dialog;
    private final JPanel details = Ui.transparent(new GridBagLayout());
    private final JTextField saveAs = new JTextField(36);
    private final JCheckBox resend;
    private final JLabel title = Ui.heading("", 16f);
    private X509Certificate selected;
    private boolean saved;

    private TrustCertDialog(MainFrame frame, AppContext ctx, X509Certificate[] chain, String resendLabel) {
        this.frame = frame;
        this.ctx = ctx;
        this.chain = chain;
        this.dialog = Ui.sheet(frame, "Trust Certificate");
        this.resend = new JCheckBox(resendLabel == null ? "Re-send the request after saving" : "Re-send “" + resendLabel + "” after saving", true);
        build();
    }

    /** Returns true when a certificate was saved and the user wants the request re-sent. */
    public static boolean open(MainFrame frame, AppContext ctx, X509Certificate[] chain, String requestLabel) {
        TrustCertDialog d = new TrustCertDialog(frame, ctx, chain, requestLabel);
        d.dialog.pack();
        d.dialog.setSize(new Dimension(Math.max(680, d.dialog.getWidth()), d.dialog.getHeight()));
        d.dialog.setLocationRelativeTo(frame);
        d.dialog.setVisible(true);
        return d.saved && d.resend.isSelected();
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout());
        JPanel head = Ui.transparent(new BorderLayout(14, 0));
        head.setBorder(Ui.pad(20, 22, 14, 22));
        JLabel icon = new JLabel(Icons.get("shield-check", 32));
        icon.setVerticalAlignment(JLabel.TOP);
        head.add(icon, BorderLayout.WEST);
        JPanel ht = Ui.transparent(new BorderLayout(0, 4));
        ht.add(title, BorderLayout.NORTH);
        ht.add(Ui.wrappingSecondary("Only do this if you recognise the certificate. It's saved to your certs folder and used "
                + "for every server it signed.", 500), BorderLayout.CENTER);
        head.add(ht, BorderLayout.CENTER);
        root.add(head, BorderLayout.NORTH);

        JPanel body = Ui.transparent(null);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(Ui.pad(0, 22, 18, 22));

        JLabel pick = Ui.caption("Certificate to trust");
        pick.setAlignmentX(0f);
        body.add(pick);
        body.add(Ui.vgap(6));
        ButtonGroup group = new ButtonGroup();
        // Highest certificate the server sent first: trusting a CA covers all its servers.
        for (int i = chain.length - 1; i >= 0; i--) {
            X509Certificate c = chain[i];
            String role = i == 0 ? "Server certificate · trusts this host only"
                    : TlsManager.isSelfSigned(c) ? "Root CA · trusts all servers it signed"
                    : "Intermediate CA · trusts servers it signed";
            boolean recommended = i == chain.length - 1 && chain.length > 1;
            JRadioButton rb = new JRadioButton("<html><b>" + Ui.escape(TlsManager.commonName(c)) + "</b><br><span style='color:"
                    + hex(Theme.secondary()) + "'>" + role + (recommended ? " · Recommended" : "") + "</span></html>");
            rb.setBorder(Ui.pad(6, 10 + (chain.length - 1 - i) * 18, 6, 10));
            rb.addActionListener(e -> select(c));
            group.add(rb);
            rb.setAlignmentX(0f);
            body.add(rb);
            if (i == chain.length - 1) {
                rb.setSelected(true);
                selected = c;
            }
        }
        body.add(Ui.vgap(12));

        Ui.RoundedPanel box = new Ui.RoundedPanel(new BorderLayout(), Theme::subtleBg, null, 10);
        box.setBorder(Ui.pad(10, 14, 10, 14));
        box.add(details);
        box.setAlignmentX(0f);
        body.add(box);
        body.add(Ui.vgap(14));

        JLabel saveLabel = Ui.caption("Save as");
        saveLabel.setLabelFor(saveAs);
        saveLabel.setAlignmentX(0f);
        body.add(saveLabel);
        body.add(Ui.vgap(6));
        JPanel saveRow = Ui.transparent(new BorderLayout(8, 0));
        saveAs.setFont(Theme.mono(12.5f));
        saveRow.add(saveAs, BorderLayout.CENTER);
        saveRow.add(Ui.button("Choose…", e -> choose()), BorderLayout.EAST);
        saveRow.setAlignmentX(0f);
        saveRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        body.add(saveRow);
        body.add(Ui.vgap(4));
        JLabel fmt = Ui.small("PEM format. Remove it any time in Settings › Certificates.");
        fmt.setAlignmentX(0f);
        body.add(fmt);
        body.add(Ui.vgap(10));
        resend.setAlignmentX(0f);
        body.add(resend);
        root.add(body, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(1, 0, 0, 0), Ui.pad(10, 22, 10, 22)));
        Ui.AccentButton trust = Ui.accent("Trust & Save", e -> save());
        footer.add(Ui.buttonBar(trust, Ui.button("Cancel", e -> dialog.dispose())), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(trust);
        select(selected);
    }

    private void select(X509Certificate c) {
        selected = c;
        title.setText("Trust “" + TlsManager.commonName(c) + "”?");
        details.removeAll();
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH);
        int y = 0;
        y = detail(y, "Subject", c.getSubjectX500Principal().getName());
        y = detail(y, "Issuer", TlsManager.isSelfSigned(c) ? "Self-signed" : c.getIssuerX500Principal().getName());
        y = detail(y, "Valid", df.format(c.getNotBefore()) + " – " + df.format(c.getNotAfter()));
        y = detail(y, "Key", keyDescription(c) + " · " + c.getSigAlgName());
        String fp = TlsManager.sha256(c);
        JPanel fpRow = Ui.transparent(new BorderLayout(6, 0));
        JLabel fpl = new JLabel("<html><div style='width:315px'>" + fp + "</div></html>");
        fpl.setFont(Theme.mono(11.5f));
        fpRow.add(fpl, BorderLayout.CENTER);
        fpRow.add(Ui.toolbarButton(Icons.get("copy", 14), "Copy fingerprint",
                e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(fp), null)), BorderLayout.EAST);
        addRow(y, "SHA-256", fpRow);
        saveAs.setText(AppPaths.display(TlsManager.uniquePath(ctx.tls.certsDir(), TlsManager.commonName(c))));
        details.revalidate();
        details.repaint();
        dialog.pack();
    }

    private int detail(int y, String label, String value) {
        JLabel v = new JLabel("<html><div style='width:330px'>" + Ui.escape(value) + "</div></html>");
        addRow(y, label, v);
        return y + 1;
    }

    private void addRow(int y, String label, java.awt.Component value) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = y;
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.insets = new Insets(3, 0, 3, 14);
        JLabel l = Ui.secondary(label);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, l.getFont().getSize2D() - 0.5f));
        details.add(l, gc);
        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(3, 0, 3, 0);
        details.add(value, gc);
    }

    private static String keyDescription(X509Certificate c) {
        if (c.getPublicKey() instanceof RSAPublicKey rsa) return "RSA " + rsa.getModulus().bitLength();
        if (c.getPublicKey() instanceof ECPublicKey ec) return "EC " + ec.getParams().getCurve().getField().getFieldSize();
        return c.getPublicKey().getAlgorithm();
    }

    private Path target() {
        String t = saveAs.getText().trim();
        if (t.startsWith("~")) t = System.getProperty("user.home") + t.substring(1);
        Path p = Path.of(t);
        if (!p.isAbsolute()) p = ctx.tls.certsDir().resolve(p);
        return p;
    }

    private void choose() {
        Path current = target();
        File chosen;
        if (Platform.isMac()) {
            FileDialog fd = new FileDialog(frame, "Save Certificate", FileDialog.SAVE);
            fd.setDirectory(ctx.tls.certsDir().toString());
            fd.setFile(current.getFileName().toString());
            fd.setVisible(true);
            chosen = fd.getFile() == null ? null : new File(fd.getDirectory(), fd.getFile());
        } else {
            JFileChooser fc = new JFileChooser(ctx.tls.certsDir().toFile());
            fc.setSelectedFile(current.toFile());
            chosen = fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
        }
        if (chosen != null) {
            String p = chosen.getPath();
            if (!p.toLowerCase(Locale.ROOT).matches(".*\\.(pem|crt|cer)$")) p += ".pem";
            saveAs.setText(AppPaths.display(Path.of(p)));
        }
    }

    private void save() {
        Path p = target();
        try {
            ctx.tls.saveCertificate(selected, p);
        } catch (FileAlreadyExistsException e) {
            Ui.error(dialog, "File exists", p.getFileName() + " already exists. Choose another name.");
            return;
        } catch (Exception e) {
            Ui.error(dialog, "Couldn't save the certificate", String.valueOf(e.getMessage()));
            return;
        }
        if (!p.toAbsolutePath().getParent().equals(ctx.tls.certsDir().toAbsolutePath())) {
            frame.status("Saved outside the certs folder — it won't be trusted until it's in " + AppPaths.display(ctx.tls.certsDir()), null, null);
        }
        String err = ctx.reloadTls();
        if (err != null) {
            Ui.error(dialog, "Saved, but reloading failed", err);
            return;
        }
        saved = true;
        frame.status("Trusted “" + TlsManager.commonName(selected) + "” · saved to " + AppPaths.display(p), null, null);
        dialog.dispose();
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
