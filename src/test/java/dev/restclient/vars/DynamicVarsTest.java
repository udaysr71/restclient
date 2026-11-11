package dev.restclient.vars;

import dev.restclient.model.Environment;
import dev.restclient.model.Variable;
import dev.restclient.testkit.Test;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertTrue;

class DynamicVarsTest {

    @Test
    void builtInsProduceValuesOfTheRightShape() {
        VariableResolver r = VariableResolver.empty();
        assertTrue(r.resolve("{{$guid}}").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertTrue(r.resolve("{{$randomUUID}}").matches("[0-9a-f-]{36}"));
        long ts = Long.parseLong(r.resolve("{{$timestamp}}"));
        assertTrue(Math.abs(ts - System.currentTimeMillis() / 1000) < 5, "seconds since 1970");
        assertTrue(r.resolve("{{$isoTimestamp}}").matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z"));
        int n = Integer.parseInt(r.resolve("{{ $randomInt }}"));
        assertTrue(n >= 0 && n <= 1000);
        assertTrue(r.resolve("{{$randomBoolean}}").matches("true|false"));
        assertTrue(r.resolve("{{$randomAlphaNumeric}}").matches("[A-Za-z0-9]"));
    }

    @Test
    void everyOccurrenceGetsItsOwnValue() {
        String[] two = VariableResolver.empty().resolve("{{$guid}} {{$guid}}").split(" ");
        assertFalse(two[0].equals(two[1]));
    }

    @Test
    void builtInsCountAsDefinedAndUnknownDollarNamesDoNot() {
        VariableResolver r = VariableResolver.empty();
        assertTrue(r.isDefined("$guid"));
        assertFalse(r.isDefined("$randomEmail"));
        assertEquals("{{$randomEmail}}", r.resolve("{{$randomEmail}}"), "unknown ones are sent as written");
        assertTrue(r.unresolved("{{$guid}}{{$randomEmail}}{{nope}}").equals(java.util.Set.of("$randomEmail", "nope")));
        assertTrue(r.lookup("$guid").isEmpty(), "no scope defines a built-in");
        assertFalse(r.names().contains("$guid"), "autocomplete offers them only after $");
    }

    @Test
    void aUserVariableCannotShadowABuiltIn() {
        Environment e = new Environment();
        e.variables.add(new Variable("$timestamp", "fixed"));
        assertFalse(new VariableResolver(e, null, null).resolve("{{$timestamp}}").equals("fixed"));
    }

    @Test
    void builtInsWorkInsideVariableValues() {
        Environment e = new Environment();
        e.variables.add(new Variable("orderId", "order-{{$randomInt}}"));
        assertTrue(new VariableResolver(e, null, null).resolve("{{orderId}}").matches("order-\\d+"));
    }

    @Test
    void aRecordingCanBeReplayedSoTheSentAndShownRequestsMatch() {
        VariableResolver rec = VariableResolver.empty().recording();
        String url = rec.resolve("/a/{{$guid}}");
        String body = rec.resolve("{\"id\":\"{{$guid}}\",\"n\":{{$randomInt}}}");
        VariableResolver replay = rec.replaying();
        assertEquals(url, replay.resolveMasked("/a/{{$guid}}"));
        assertEquals(body, replay.resolveMasked("{\"id\":\"{{$guid}}\",\"n\":{{$randomInt}}}"));
        assertTrue(replay.resolve("{{$guid}}").length() == 36, "after the recording runs out, values are fresh");
    }

    @Test
    void describesEveryBuiltIn() {
        for (String name : DynamicVars.names()) {
            assertTrue(DynamicVars.describe(name) != null && !DynamicVars.describe(name).isBlank(), name);
            assertTrue(DynamicVars.generate(name) != null, name);
        }
        assertTrue(DynamicVars.describe("$nope") == null);
    }

    @Test
    void aRequestsLocalVariablesWinOverEveryOtherScope() {
        Environment e = new Environment();
        e.name = "Staging";
        e.variables.add(new Variable("userId", "7"));
        e.variables.add(new Variable("host", "api.example.com"));
        VariableResolver base = new VariableResolver(e, null, null);
        java.util.List<dev.restclient.model.KeyValue> locals = java.util.List.of(
                new dev.restclient.model.KeyValue("userId", "99"), new dev.restclient.model.KeyValue("page", "{{userId}}-{{host}}"),
                new dev.restclient.model.KeyValue("off", "x", false), new dev.restclient.model.KeyValue("bad{name", "x"),
                new dev.restclient.model.KeyValue("$guid", "fixed"));
        VariableResolver r = base.withLocals(locals);
        assertEquals("99", r.resolve("{{userId}}"));
        assertEquals(VariableResolver.Scope.REQUEST, r.lookup("userId").orElseThrow().scope());
        assertEquals(2, r.lookupAll("userId").size(), "the environment's value is still there, overridden");
        assertEquals("99-api.example.com", r.resolve("{{page}}"), "locals can use other variables");
        assertFalse(r.isDefined("off") || r.isDefined("bad{name"), "switched-off rows and impossible names are ignored");
        assertFalse(r.resolve("{{$guid}}").equals("fixed"), "built-ins can't be overridden");
        assertTrue(r.names().contains("page"));
        assertEquals("7", base.resolve("{{userId}}"), "the original resolver is untouched");
        assertTrue(base.withLocals(java.util.List.of()) == base);
        assertEquals("99", r.recording().replaying().resolve("{{userId}}"), "copies keep the locals");
    }

    @Test
    void namesMayContainSpacesAndOtherAlphabets() {
        Environment e = new Environment();
        e.variables.add(new Variable("base url", "https://api.example.com"));
        e.variables.add(new Variable("API Key ", "k-123")); // typed with a stray space after it
        e.variables.add(new Variable("größe", "42"));
        e.variables.add(new Variable("a  b", "two spaces"));
        VariableResolver r = new VariableResolver(e, null, null);
        assertEquals("https://api.example.com/v1?k=k-123&n=42", r.resolve("{{base url}}/v1?k={{ API Key }}&n={{größe}}"));
        assertEquals("two spaces", r.resolve("{{a  b}}"));
        assertTrue(r.isDefined("base url") && r.isDefined("API Key"));
        assertTrue(r.names().contains("API Key"), "listed without the stray space");
        assertEquals(java.util.Set.of("not here"), r.unresolved("{{base url}} {{not here}}"));
        assertEquals("base url", VariableResolver.tokens("x {{  base url  }} y").get(0).name(), "spaces around the name aren't part of it");
        assertTrue(VariableResolver.references("{{base url}}/x", "base url"));
        // still not variables: JSON-looking braces, empty braces, names with other punctuation
        for (String not : new String[]{"{{ \"a\": 1 }}", "{{}}", "{{   }}", "{{a/b}}", "{{a:b}}", "{{a{b}}", "{{a\nb}}"}) {
            assertTrue(VariableResolver.tokens(not).isEmpty(), not);
        }
        assertTrue(VariableResolver.isUserName("base url") && VariableResolver.isUserName("größe") && VariableResolver.isUserName("a.b-c_d"));
        assertFalse(VariableResolver.isUserName(" lead") || VariableResolver.isUserName("trail ") || VariableResolver.isUserName("")
                || VariableResolver.isUserName("$guid") || VariableResolver.isUserName("a{b") || VariableResolver.isUserName("x".repeat(129)));
        java.util.List<dev.restclient.model.KeyValue> locals = java.util.List.of(new dev.restclient.model.KeyValue(" base url ", "http://local"));
        assertEquals("http://local", r.withLocals(locals).resolve("{{base url}}"), "local variables too");
    }
}
