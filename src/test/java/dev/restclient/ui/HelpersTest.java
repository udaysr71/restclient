package dev.restclient.ui;

import dev.restclient.model.KeyValue;
import dev.restclient.testkit.Test;

import java.util.List;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertNull;
import static dev.restclient.testkit.Assertions.assertTrue;

/** Non-visual helpers behind the UI: query sync, beautify, formatting. Runs headless. */
class HelpersTest {

    @Test
    void queryParamsAreParsedRawSoVariablesSurvive() {
        List<KeyValue> p = RequestTab.parseQuery("{{baseUrl}}/v2/orders?status=open&limit={{pageSize}}&flag#top");
        assertEquals(3, p.size());
        assertEquals(new KeyValue("limit", "{{pageSize}}"), p.get(1));
        assertEquals(new KeyValue("flag", ""), p.get(2));
        assertTrue(RequestTab.parseQuery("https://x.test/no-query").isEmpty());
    }

    @Test
    void urlIsRebuiltFromParamsKeepingFragment() {
        String url = RequestTab.withQuery("{{baseUrl}}/v2?old=1#frag",
                List.of(new KeyValue("status", "open"), new KeyValue("limit", "{{pageSize}}"), new KeyValue("", "skip")));
        assertEquals("{{baseUrl}}/v2?status=open&limit={{pageSize}}#frag", url);
        assertEquals("https://x.test/a", RequestTab.withQuery("https://x.test/a?b=1", List.of()));
    }

    @Test
    void beautifyKeepsVariablesInsideAndOutsideStrings() {
        String out = RequestTab.beautifyJsonWithVariables("{\"a\":{{amount}},\"b\":\"x{{id}}y\",\"c\":[{{list}}]}");
        assertEquals("{\n  \"a\": {{amount}},\n  \"b\": \"x{{id}}y\",\n  \"c\": [\n    {{list}}\n  ]\n}", out);
        assertNull(RequestTab.beautifyJsonWithVariables("{not json"));
    }

    @Test
    void prettyJsonKeepsNumberPrecision() {
        assertEquals("{\n  \"total\": 129.50\n}", Formatters.prettyJson("{\"total\":129.50}"));
    }

    @Test
    void prettyXmlIndentsAndKeepsDeclarationOnItsOwnLine() {
        String out = Formatters.prettyXml("<?xml version=\"1.0\"?><a><b>t</b></a>");
        assertTrue(out.startsWith("<?xml"), out);
        assertTrue(out.contains("\n<a>\n  <b>t</b>\n</a>"), out);
        String noDecl = Formatters.prettyXml("<a><b/></a>");
        assertTrue(noDecl.startsWith("<a>"), noDecl);
    }

    @Test
    void sizesAndDurations() {
        assertEquals("512 B", Formatters.size(512));
        assertEquals("2.4 KB", Formatters.size(2458));
        assertEquals("142 ms", Formatters.duration(142));
        assertEquals("1.8 s", Formatters.duration(1800));
    }

    /**
     * The app never uses Swing's JFileChooser: on top of the Basic look and feel it has no file list, so it opened
     * as an empty box. File dialogs go through FileChoosers, which uses the operating system's own.
     */
    @Test
    void noSwingFileChooserAnywhereInTheApp() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java");
        if (!java.nio.file.Files.isDirectory(src)) return;
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(src)) {
            for (java.nio.file.Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                for (String line : java.nio.file.Files.readAllLines(f)) {
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) continue;
                    if (code.contains("JFileChooser")) offenders.add(f.getFileName() + ": " + code);
                }
            }
        }
        dev.restclient.testkit.Assertions.assertTrue(offenders.isEmpty(), offenders.toString());
    }

    /** Unnamed tabs show where they point; history rows say which host. */
    @Test
    void tabAndHistoryLabels() {
        assertEquals("/v2/orders", RequestTab.pathLabel("{{baseUrl}}/v2/orders?status=open#x"));
        assertEquals("/get", RequestTab.pathLabel("https://httpbin.org/get"));
        assertEquals("httpbin.org", RequestTab.pathLabel("https://httpbin.org/"));
        assertEquals("{{baseUrl}}", RequestTab.pathLabel("{{baseUrl}}"));
        assertEquals("localhost:8080", RequestTab.pathLabel("localhost:8080"));
        assertEquals("/users/{{userId}}", RequestTab.pathLabel("{{baseUrl}}/users/{{userId}}/"));
        assertEquals("", RequestTab.pathLabel("  "));
        assertEquals("", RequestTab.pathLabel("{{"));
        assertEquals("", RequestTab.pathLabel("http://"));
        assertEquals("", RequestTab.pathLabel("curl https://x/y"));
        String longPath = RequestTab.pathLabel("https://h/" + "segment/".repeat(10) + "last");
        dev.restclient.testkit.Assertions.assertTrue(longPath.length() == 28 && longPath.startsWith("…") && longPath.endsWith("/last"), longPath);

        assertEquals("api.example.com:8443", HistoryPanel.host("https://api.example.com:8443/v1?x=1"));
        assertEquals("127.0.0.1:9", HistoryPanel.host("http://127.0.0.1:9"));
        assertEquals("example.com", HistoryPanel.host("https://user:pw@example.com/x"));
        assertEquals("", HistoryPanel.host("not a url"));
        assertEquals("", HistoryPanel.host(null));
    }
}
