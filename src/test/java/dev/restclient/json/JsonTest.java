package dev.restclient.json;

import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Settings;
import dev.restclient.testkit.Test;

import java.util.List;
import java.util.Map;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertNull;
import static dev.restclient.testkit.Assertions.assertThrows;
import static dev.restclient.testkit.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesAllValueKinds() throws Exception {
        Object v = Json.parse(" {\"s\":\"a\\\"b\\u00e9\\n\",\"n\":-1.50e3,\"t\":true,\"f\":false,\"z\":null,\"a\":[1,{}],\"o\":{}} ");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals("a\"bé\n", m.get("s"));
        assertEquals(new Json.Num("-1.50e3"), m.get("n"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertTrue(m.containsKey("z") && m.get("z") == null);
        assertEquals(2, ((List<?>) m.get("a")).size());
    }

    @Test
    void rejectsInvalidJsonWithPosition() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\n  \"a\": 01\n}"));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
        assertThrows(JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1} x"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(JsonException.class, () -> Json.parse("\"bad \\x escape\""));
        assertThrows(JsonException.class, () -> Json.parse(""));
    }

    @Test
    void writeAndParseRoundTrip() throws Exception {
        String text = Json.write(Json.parse("{\"k\":[1,2.50,\"x\\ty\"],\"e\":[],\"m\":{\"q\":null}}"), true);
        assertEquals("{\n  \"k\": [\n    1,\n    2.50,\n    \"x\\ty\"\n  ],\n  \"e\": [],\n  \"m\": {\n    \"q\": null\n  }\n}\n", text);
        assertEquals("{\"a\":\"\\u0001\"}", Json.write(Map.of("a", "\u0001"), false));
    }

    @Test
    void reformatKeepsTokensExactly() {
        String in = "{\"a\":1.000,\"a\":\"dup\",\"u\":\"\\u00e9\\/\",\"x\":[ ],\"y\":{ }}";
        assertEquals("{\n  \"a\": 1.000,\n  \"a\": \"dup\",\n  \"u\": \"\\u00e9\\/\",\n  \"x\": [],\n  \"y\": {}\n}", Json.reformat(in));
        assertEquals("[\n  1,\n  [\n    2\n  ]\n]", Json.reformat("[1,[2]]"));
        assertEquals("\"plain\"", Json.reformat("  \"plain\" "));
        assertNull(Json.reformat("{not json}"));
        assertNull(Json.reformat("   "));
    }

    @Test
    void deepNestingIsRejectedNotCrashing() {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        assertNull(Json.reformat(deep));
    }

    @Test
    void mapperRoundTripsModels() throws Exception {
        RequestDef r = new RequestDef();
        r.name = "List orders";
        r.method = HttpMethod.PATCH;
        r.url = "{{baseUrl}}/v2";
        r.headers.add(new KeyValue("X-A", "1", false));
        r.bodyType = BodyType.JSON;
        r.body = "{\"a\": 1}";
        r.auth.type = Auth.Type.BEARER;
        r.auth.token = "{{token}}";
        r.insecure = Boolean.TRUE;
        r.timeoutSeconds = 12;

        String json = JsonMapper.toJson(r);
        RequestDef back = JsonMapper.fromJson(json, RequestDef.class);
        assertTrue(back.sameContent(r), json);
        assertEquals(r.id, back.id);

        RequestDef noInsecure = r.copy();
        noInsecure.insecure = null;
        assertTrue(!JsonMapper.toJson(noInsecure).contains("insecure"), "null fields are left out");
    }

    @Test
    void mapperToleratesUnknownAndMissingFields() throws Exception {
        Settings s = JsonMapper.fromJson("{\"historyLimit\":50,\"theme\":\"PURPLE\",\"newThing\":[1,2],"
                + "\"keystorePasswords\":{\"a.p12\":\"pw\"}}", Settings.class);
        assertEquals(50, s.historyLimit);
        assertEquals(Settings.ThemeMode.SYSTEM, s.theme);
        assertEquals(30, s.defaultTimeoutSeconds);
        assertEquals("pw", s.keystorePasswords.get("a.p12"));
    }

    @Test
    void mapperReportsTypeMismatches() {
        JsonException e = assertThrows(JsonException.class, () -> JsonMapper.fromJson("{\"historyLimit\":\"lots\"}", Settings.class));
        assertTrue(e.getMessage().contains("historyLimit"), e.getMessage());
    }
}
