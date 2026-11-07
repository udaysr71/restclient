package dev.restclient.http;

import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.Environment;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.vars.VariableResolver;
import dev.restclient.testkit.Test;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertNull;
import static dev.restclient.testkit.Assertions.assertThrows;
import static dev.restclient.testkit.Assertions.assertTrue;

class PreparedRequestTest {

    private static VariableResolver vars() {
        Environment e = new Environment();
        e.name = "Staging";
        e.variables.add(new Variable("baseUrl", "https://staging.example.com"));
        e.variables.add(new Variable("token", "secret-token", true));
        e.variables.add(new Variable("user", "alice"));
        return new VariableResolver(e, null, null);
    }

    private static String header(PreparedRequest p, String name) {
        for (KeyValue kv : p.headers) if (kv.key.equalsIgnoreCase(name)) return kv.value;
        return null;
    }

    @Test
    void resolvesUrlAndAddsSchemeWhenMissing() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "{{baseUrl}}/v2/orders?q=a b";
        PreparedRequest p = PreparedRequest.prepare(r, vars());
        assertEquals("https://staging.example.com/v2/orders?q=a b", p.url);
        assertEquals("q=a%20b", p.uri.getRawQuery());

        r.url = "localhost:8080/health";
        assertEquals("http://localhost:8080/health", PreparedRequest.prepare(r, vars()).url);
    }

    @Test
    void bearerAndBasicAuthBecomeHeaders() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.auth.type = Auth.Type.BEARER;
        r.auth.token = "{{token}}";
        assertEquals("Bearer secret-token", header(PreparedRequest.prepare(r, vars()), "Authorization"));

        r.auth.type = Auth.Type.BASIC;
        r.auth.username = "{{user}}";
        r.auth.password = "pw";
        String expected = "Basic " + Base64.getEncoder().encodeToString("alice:pw".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header(PreparedRequest.prepare(r, vars()), "Authorization"));
    }

    @Test
    void explicitAuthorizationHeaderWins() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.headers.add(new KeyValue("Authorization", "Custom abc"));
        r.auth.type = Auth.Type.BEARER;
        r.auth.token = "t";
        PreparedRequest p = PreparedRequest.prepare(r, vars());
        assertEquals("Custom abc", header(p, "Authorization"));
        assertEquals(1, p.headers.size());
    }

    @Test
    void contentTypeIsAutomaticUnlessSet() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.bodyType = BodyType.JSON;
        r.body = "{}";
        assertEquals("application/json", header(PreparedRequest.prepare(r, vars()), "Content-Type"));
        r.headers.add(new KeyValue("content-type", "application/vnd.api+json"));
        assertEquals("application/vnd.api+json", header(PreparedRequest.prepare(r, vars()), "Content-Type"));
    }

    @Test
    void formIsUrlEncodedAndDisabledRowsSkipped() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.bodyType = BodyType.FORM;
        r.form.add(new KeyValue("name", "a b&c"));
        r.form.add(new KeyValue("skip", "x", false));
        PreparedRequest p = PreparedRequest.prepare(r, vars());
        assertEquals("name=a+b%26c", p.body);
        assertEquals("application/x-www-form-urlencoded", header(p, "Content-Type"));
    }

    @Test
    void noBodyForNone() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.body = "ignored";
        assertNull(PreparedRequest.prepare(r, vars()).body);
    }

    @Test
    void restrictedHeadersAreSkippedWithAWarning() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.headers.add(new KeyValue("Host", "other"));
        PreparedRequest p = PreparedRequest.prepare(r, vars());
        assertTrue(p.headers.isEmpty());
        assertTrue(p.warnings.get(0).contains("Host"));
    }

    @Test
    void unresolvedVariablesAreReported() throws Exception {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.headers.add(new KeyValue("X-Tenant-Id", "{{tenantId}}"));
        PreparedRequest p = PreparedRequest.prepare(r, vars());
        assertTrue(p.unresolved.contains("tenantId"));
    }

    @Test
    void maskedCopyHidesSecrets() {
        RequestDef r = new RequestDef();
        r.url = "{{baseUrl}}/x";
        r.auth.type = Auth.Type.BEARER;
        r.auth.token = "{{token}}";
        PreparedRequest p = PreparedRequest.prepareMasked(r, vars());
        assertEquals("Bearer " + VariableResolver.MASK, header(p, "Authorization"));
        assertEquals("https://staging.example.com/x", p.url);
    }

    @Test
    void invalidUrlsGiveFriendlyErrors() {
        RequestDef r = new RequestDef();
        r.url = "";
        assertThrows(PreparedRequest.InvalidRequestException.class, () -> PreparedRequest.prepare(r, vars()));
        r.url = "ftp://x.test/file";
        assertThrows(PreparedRequest.InvalidRequestException.class, () -> PreparedRequest.prepare(r, vars()));
    }
}
