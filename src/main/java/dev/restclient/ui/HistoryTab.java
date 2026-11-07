package dev.restclient.ui;

import dev.restclient.curl.CurlExporter;
import dev.restclient.http.ResponseData;
import dev.restclient.model.HistoryEntry;
import dev.restclient.model.KeyValue;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Read-only snapshot of a history entry: exactly what was sent and what came back. */
public class HistoryTab extends JPanel {
    private final HistoryEntry entry;

    public HistoryTab(MainFrame frame, HistoryEntry e) {
        super(new BorderLayout());
        this.entry = e;

        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setBorder(Ui.pad(14, 16, 10, 16));
        JPanel left = Ui.transparent(null);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JPanel line = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel m = new JLabel(e.sentMethod == null ? "" : e.sentMethod.name()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(Theme.methodColor(entry.sentMethod));
            }
        };
        m.setFont(Theme.mono(13f).deriveFont(Font.BOLD));
        m.setForeground(Theme.methodColor(e.sentMethod));
        JLabel url = new JLabel(e.sentUrl);
        url.setFont(Theme.mono(13f));
        line.add(m);
        line.add(url);
        line.setAlignmentX(0f);
        left.add(line);
        left.add(Ui.vgap(6));
        JPanel metaRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        if (e.status > 0) {
            metaRow.add(Ui.statusPill(e.status, (e.status + " " + ResponseData.reasonPhrase(e.status)).trim()));
        } else {
            metaRow.add(new Ui.Pill(e.errorKind == null ? "Failed" : "Failed · " + e.errorKind.toLowerCase().replace('_', ' '),
                    Theme::errBg, Theme::errFg));
        }
        metaRow.add(Ui.secondary(Formatters.duration(e.elapsedMs)));
        if (e.status > 0) metaRow.add(Ui.secondary(Formatters.size(e.sizeBytes)));
        if (e.environmentName != null) metaRow.add(Ui.secondary(e.environmentName));
        metaRow.add(Ui.secondary(new SimpleDateFormat("EEE d MMM, HH:mm").format(new Date(e.timestamp))));
        if (e.sourceName != null) metaRow.add(Ui.secondary("from “" + e.sourceName + "”"));
        metaRow.setAlignmentX(0f);
        left.add(metaRow);
        head.add(left, BorderLayout.CENTER);

        JPanel actions = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(Ui.accent("Open in New Tab", ev -> frame.openFromHistory(entry)));
        actions.add(Ui.button("Save to Collection…", ev -> frame.saveHistoryToCollection(entry)));
        actions.add(Ui.toolbarButton(Icons.get("copy"), "Copy as cURL", ev -> {
            String cmd = CurlExporter.export(entry.request, null, Boolean.TRUE.equals(entry.request.insecure));
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
            frame.status("Copied as cURL", null, null);
        }));
        head.add(actions, BorderLayout.EAST);

        Ui.RoundedPanel banner = new Ui.RoundedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0), Theme::subtleBg, null, 10);
        banner.setBorder(Ui.pad(7, 8, 7, 8));
        banner.add(new JLabel(Icons.get("history", 14)));
        banner.add(Ui.secondary("Read-only snapshot of what was sent. Variables are shown as they resolved; secrets are masked."));
        JPanel bannerWrap = new JPanel(new BorderLayout());
        bannerWrap.setBorder(Ui.pad(0, 16, 10, 16));
        bannerWrap.add(banner);

        JPanel top = new JPanel(new BorderLayout());
        top.add(head, BorderLayout.NORTH);
        top.add(bannerWrap, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        // request side
        StringBuilder req = new StringBuilder();
        for (KeyValue kv : e.sentHeaders) req.append(kv.key).append(": ").append(kv.value).append('\n');
        if (e.sentBody != null && !e.sentBody.isEmpty()) req.append('\n').append(e.sentBody);
        CodeArea reqArea = readOnly(req.toString().isEmpty() ? "(no headers or body)" : req.toString());
        JPanel reqPanel = section("Request", CodeArea.scroll(reqArea, false));

        // response side
        ResponsePanel resp = new ResponsePanel();
        if (e.status > 0) {
            byte[] body = e.responseBody == null ? new byte[0] : e.responseBody.getBytes(StandardCharsets.UTF_8);
            List<String> notes = e.responseTruncated ? List.of("History keeps the first 32 KB of each response.") : List.of();
            List<KeyValue> headers = e.responseHeaders;
            if (e.responseBody != null && e.responseBody.startsWith("(binary content")) {
                headers = new java.util.ArrayList<>(headers);
                headers.removeIf(h -> h.key.equalsIgnoreCase("content-type"));
                headers.add(new KeyValue("Content-Type", "text/plain"));
            }
            resp.showResponse(ResponseData.ok(e.status, headers, body, e.elapsedMs, null), notes, "response");
        } else {
            resp.showMessage("No response", e.errorMessage == null ? "The request failed before a response arrived." : e.errorMessage);
        }

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqPanel, resp);
        split.setResizeWeight(0.4);
        Ui.initialDivider(split, 0.38);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(7);
        add(split, BorderLayout.CENTER);
    }

    public HistoryEntry entry() {
        return entry;
    }

    public String title() {
        String path = entry.sentUrl == null ? "" : entry.sentUrl.replaceFirst("^[a-zA-Z]+://[^/]+", "");
        return path.isEmpty() ? "/" : path;
    }

    private static CodeArea readOnly(String text) {
        CodeArea a = new CodeArea();
        a.setEditable(false);
        a.setText(text);
        a.setCaretPosition(0);
        return a;
    }

    private static JPanel section(String title, java.awt.Component c) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel l = Ui.heading(title, 13f);
        l.setBorder(BorderFactory.createCompoundBorder(Ui.line(1, 0, 1, 0), Ui.pad(8, 14, 8, 14)));
        p.add(l, BorderLayout.NORTH);
        if (c instanceof javax.swing.JComponent jc) jc.setBorder(BorderFactory.createEmptyBorder());
        p.add(c);
        return p;
    }
}
