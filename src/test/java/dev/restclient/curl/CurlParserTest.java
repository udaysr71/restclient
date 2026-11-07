package dev.restclient.curl;

import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.testkit.Test;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertThrows;
import static dev.restclient.testkit.Assertions.assertTrue;

class CurlParserTest {

    private static CurlParser.Result parse(String s) throws CurlParseException {
        return CurlParser.parse(s);
    }

    @Test
    void simpleGet() throws Exception {
        CurlParser.Result r = parse("curl https://api.example.com/v1/items");
        assertEquals(HttpMethod.GET, r.request().method);
        assertEquals("https://api.example.com/v1/items", r.request().url);
        assertFalse(r.insecure());
        assertEquals(BodyType.NONE, r.request().bodyType);
    }

    @Test
    void postWithHeadersAndJsonBodyAcrossLines() throws Exception {
        String cmd = "curl -k -X POST 'https://billing.internal.example:8443/v1/invoices' \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -H \"Authorization: Bearer abc.def\" \\\n"
                + "  --compressed \\\n"
                + "  --data-raw '{\"customerId\":\"c_4812\",\"amount\":129.5}'";
        CurlParser.Result r = parse(cmd);
        RequestDef q = r.request();
        assertTrue(r.insecure());
        assertEquals(HttpMethod.POST, q.method);
        assertEquals("https://billing.internal.example:8443/v1/invoices", q.url);
        assertEquals("application/json", q.header("Content-Type"));
        assertEquals("Bearer abc.def", q.header("Authorization"));
        assertEquals(BodyType.JSON, q.bodyType);
        assertEquals("{\"customerId\":\"c_4812\",\"amount\":129.5}", q.body);
        assertTrue(r.ignored().stream().anyMatch(s -> s.startsWith("--compressed")));
    }

    @Test
    void dataImpliesPostAndMultipleDataAreJoined() throws Exception {
        CurlParser.Result r = parse("curl https://x.test/form -d a=1 -d 'b=two words'");
        assertEquals(HttpMethod.POST, r.request().method);
        assertEquals(BodyType.FORM, r.request().bodyType);
        assertEquals(2, r.request().form.size());
        assertEquals(new KeyValue("b", "two words"), r.request().form.get(1));
    }

    @Test
    void combinedShortFlagsAndAttachedValues() throws Exception {
        CurlParser.Result r = parse("curl -sSLk -XPUT -HAccept:text/plain https://x.test/a");
        assertTrue(r.insecure());
        assertEquals(HttpMethod.PUT, r.request().method);
        assertEquals("text/plain", r.request().header("Accept"));
    }

    @Test
    void lastShortFlagInClusterTakesNextWord() throws Exception {
        CurlParser.Result r = parse("curl -sX DELETE https://x.test/a/1");
        assertEquals(HttpMethod.DELETE, r.request().method);
    }

    @Test
    void ansiCQuotingAndDoubleQuoteEscapes() throws Exception {
        CurlParser.Result r = parse("curl https://x.test -H $'X-Note: it\\'s\\tok' --data-raw \"say \\\"hi\\\"\"");
        assertEquals("it's\tok", r.request().header("X-Note"));
        assertEquals("say \"hi\"", r.request().body);
    }

    @Test
    void basicAuthFromUserFlag() throws Exception {
        RequestDef q = parse("curl -u alice:s3c:ret https://x.test").request();
        assertEquals(Auth.Type.BASIC, q.auth.type);
        assertEquals("alice", q.auth.username);
        assertEquals("s3c:ret", q.auth.password);
    }

    @Test
    void getFlagMovesDataIntoQuery() throws Exception {
        RequestDef q = parse("curl -G https://x.test/search?x=1 -d q=cats --data-urlencode 'tag=a b'").request();
        assertEquals(HttpMethod.GET, q.method);
        assertEquals("https://x.test/search?x=1&q=cats&tag=a%20b", q.url);
        assertEquals(BodyType.NONE, q.bodyType);
    }

    @Test
    void jsonFlagSetsHeaders() throws Exception {
        RequestDef q = parse("curl --json '{\"a\":1}' https://x.test").request();
        assertEquals(HttpMethod.POST, q.method);
        assertEquals("application/json", q.header("Content-Type"));
        assertEquals("application/json", q.header("Accept"));
        assertEquals(BodyType.JSON, q.bodyType);
    }

    @Test
    void headAndUrlOption() throws Exception {
        RequestDef q = parse("curl --head --url https://x.test/health").request();
        assertEquals(HttpMethod.HEAD, q.method);
        assertEquals("https://x.test/health", q.url);
    }

    @Test
    void unknownOptionsAreReportedNotDropped() throws Exception {
        CurlParser.Result r = parse("curl --frobnicate -o out.json --http2 https://x.test");
        assertTrue(r.ignored().contains("--frobnicate (unsupported option)"));
        assertTrue(r.ignored().contains("--output out.json"));
        assertEquals("https://x.test", r.request().url);
    }

    @Test
    void textBodyKeepsCurlsDefaultContentType() throws Exception {
        RequestDef q = parse("curl https://x.test -d 'just some text'").request();
        assertEquals(BodyType.TEXT, q.bodyType);
        assertEquals("application/x-www-form-urlencoded", q.header("Content-Type"));
    }

    @Test
    void maxTimeBecomesTimeout() throws Exception {
        assertEquals(3, parse("curl -m 2.5 https://x.test").request().timeoutSeconds);
    }

    @Test
    void unclosedQuoteReportsLineAndColumn() {
        String cmd = "curl https://x.test \\\n  -H 'Accept: json";
        CurlParseException e = assertThrows(CurlParseException.class, () -> parse(cmd));
        assertEquals(2, e.line());
        assertEquals(6, e.column());
        assertTrue(e.describe().startsWith("Unclosed single quote at line 2, column 6"));
    }

    @Test
    void missingUrlIsAnError() {
        assertThrows(CurlParseException.class, () -> parse("curl -X POST -d a=1"));
    }

    @Test
    void missingOptionValueIsAnError() {
        assertThrows(CurlParseException.class, () -> parse("curl https://x.test -H"));
    }

    @Test
    void detectsCurlPaste() {
        assertTrue(CurlParser.looksLikeCurl("  curl https://x"));
        assertTrue(CurlParser.looksLikeCurl("CURL -X GET x"));
        assertFalse(CurlParser.looksLikeCurl("https://curl.se"));
    }
}
