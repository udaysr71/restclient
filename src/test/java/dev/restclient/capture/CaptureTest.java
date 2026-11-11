package dev.restclient.capture;

import dev.restclient.http.ResponseData;
import dev.restclient.model.KeyValue;
import dev.restclient.testkit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertNull;
import static dev.restclient.testkit.Assertions.assertTrue;

class CaptureTest {
    private static final String BODY = """
            {"data": {"token": "abc.def", "expires": 3600, "admin": false, "nothing": null,
                      "items": [{"id": 7, "tags": ["x", "y"]}, {"id": 8}], "odd name.here": "odd"},
             "status": "ok"}""";

    private static ResponseData response(String body) {
        return ResponseData.ok(201, List.of(new KeyValue("Location", "/orders/7"), new KeyValue("content-type", "application/json"),
                new KeyValue("Set-Cookie", "session=s3ss; Path=/; HttpOnly"), new KeyValue("Set-Cookie", "theme=dark")),
                body.getBytes(StandardCharsets.UTF_8), 5, "h");
    }

    private static String get(String source) {
        return Capture.evaluate(source, response(BODY));
    }

    @Test
    void readsStatusHeadersCookiesAndTheWholeBody() {
        assertEquals("201", get("status"));
        assertEquals("/orders/7", get("header.location"));
        assertEquals("application/json", get("header: Content-Type"));
        assertEquals("s3ss", get("cookie.session"));
        assertEquals("dark", get("cookie.theme"));
        assertEquals(BODY, get("body"));
        assertNull(get("header.X-Missing"));
        assertNull(get("cookie.none"));
    }

    @Test
    void readsJsonPathsInEverySpelling() {
        assertEquals("abc.def", get("body.data.token"));
        assertEquals("abc.def", get("$.data.token"));
        assertEquals("abc.def", get("data.token"));
        assertEquals("3600", get("data.expires"));
        assertEquals("false", get("data.admin"));
        assertEquals("null", get("data.nothing"));
        assertEquals("7", get("data.items[0].id"));
        assertEquals("8", get("data.items[-1].id"));
        assertEquals("y", get("body.data.items[0].tags[1]"));
        assertEquals("odd", get("data[\"odd name.here\"]"));
        assertEquals("odd", get("data['odd name.here']"));
        assertEquals("ok", get("body.status"), "a field called status, as opposed to the status code");
        assertEquals("{\"id\":8}", get("data.items[1]"), "objects are kept as JSON");
    }

    @Test
    void explainsWhatIsMissing() {
        List<KeyValue> rules = List.of(new KeyValue("a", "data.nope"), new KeyValue("b", "data.items[5].id"),
                new KeyValue("c", "data.token.deeper"), new KeyValue("d", "data.items[x]"), new KeyValue("e", ""),
                new KeyValue("bad name", "status"), new KeyValue("$guid", "status"), new KeyValue("off", "status", false),
                new KeyValue("f", "data.token[0]"));
        List<Capture.Outcome> out = Capture.run(rules, response(BODY));
        assertEquals(8, out.size(), "the disabled rule is skipped");
        assertTrue(out.stream().noneMatch(Capture.Outcome::ok));
        assertTrue(out.get(0).problem().contains("there's no “nope” in data"), out.get(0).problem());
        assertTrue(out.get(1).problem().contains("has 2 items"), out.get(1).problem());
        assertTrue(out.get(2).problem().contains("“deeper”"), out.get(2).problem());
        assertTrue(out.get(3).problem().contains("isn't a position"), out.get(3).problem());
        assertTrue(out.get(4).problem().contains("no source"), out.get(4).problem());
        assertTrue(out.get(5).problem().contains("can't be a variable name"), out.get(5).problem());
        assertTrue(out.get(6).problem().contains("can't be a variable name"), "built-in names can't be captured into");
        assertTrue(out.get(7).problem().contains("isn't a list"), out.get(7).problem());
    }

    @Test
    void nonJsonBodiesAndErrorsAreHandled() {
        ResponseData html = response("<html>");
        List<Capture.Outcome> out = Capture.run(List.of(new KeyValue("t", "body.token"), new KeyValue("s", "status")), html);
        assertTrue(out.get(0).problem().contains("isn't JSON"));
        assertEquals("201", out.get(1).value());
        ResponseData failed = ResponseData.failed(ResponseData.ErrorKind.TIMEOUT, null, "x", 1, "h");
        assertTrue(Capture.run(List.of(new KeyValue("s", "status")), failed).isEmpty(), "nothing is captured from a failed request");
        String deep = "[".repeat(100_000);
        assertNull(Capture.evaluate("body.a", response(deep))); // hostile nesting is just "not JSON"
    }

    @Test
    void longValuesAreCut() {
        String big = "{\"v\":\"" + "x".repeat(Capture.MAX_VALUE_CHARS + 500) + "\"}";
        assertEquals(Capture.MAX_VALUE_CHARS, Capture.run(List.of(new KeyValue("v", "v")), response(big)).get(0).value().length());
    }

    @Test
    void suggestsWhatTheResponseContains() {
        List<String> s = Capture.suggestions(response(BODY));
        assertTrue(s.contains("body.data.token") && s.contains("body.data.items[0].id") && s.contains("body.data.items[0].tags[0]"), s.toString());
        assertTrue(s.contains("body.data[\"odd name.here\"]"), s.toString());
        assertTrue(s.contains("status") && s.contains("header.Location") && s.contains("cookie.session"), s.toString());
        for (String source : s) {
            if (source.startsWith("body.")) assertTrue(Capture.evaluate(source, response(BODY)) != null, source + " must evaluate");
        }
        assertFalse(Capture.suggestions(null).isEmpty());
    }

    @Test
    void pathsConvertBothWays() {
        assertEquals(List.of("data", "items", 0, "odd name"), Capture.jsonPath("body.data.items[0][\"odd name\"]"));
        assertEquals("body.data.items[0][\"odd name\"]", Capture.pathSource(List.of("data", "items", 0, "odd name")));
        assertNull(Capture.jsonPath("status"));
        assertNull(Capture.jsonPath("header.X"));
        assertNull(Capture.jsonPath("a[1"));
    }
}
