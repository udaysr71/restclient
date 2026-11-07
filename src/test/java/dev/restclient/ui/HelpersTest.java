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
}
