package dev.restclient.curl;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.Environment;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.vars.VariableResolver;
import dev.restclient.testkit.Test;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertTrue;

class CurlExporterTest {

    @Test
    void quotesSingleQuotesForTheShell() {
        assertEquals("'it'\\''s'", CurlExporter.quote("it's"));
    }

    @Test
    void simpleGetIsOneLine() {
        RequestDef r = new RequestDef();
        r.url = "https://x.test/a?b=1";
        assertEquals("curl 'https://x.test/a?b=1'", CurlExporter.export(r, null, false));
    }

    @Test
    void insecureAddsK() {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        assertTrue(CurlExporter.export(r, null, true).startsWith("curl -k 'https://x.test'"));
    }

    @Test
    void keepsOrResolvesVariables() {
        RequestDef r = new RequestDef();
        r.url = "{{baseUrl}}/orders";
        r.headers.add(new KeyValue("Authorization", "Bearer {{token}}"));
        Environment env = new Environment();
        env.variables.add(new Variable("baseUrl", "https://staging.example.com"));
        env.variables.add(new Variable("token", "t0k", true));
        VariableResolver vars = new VariableResolver(env, null, null);

        String kept = CurlExporter.export(r, null, false);
        assertTrue(kept.contains("'{{baseUrl}}/orders'"));
        assertTrue(kept.contains("Bearer {{token}}"));

        String resolved = CurlExporter.export(r, vars, false);
        assertTrue(resolved.contains("'https://staging.example.com/orders'"));
        assertTrue(resolved.contains("'Authorization: Bearer t0k'"));
    }

    @Test
    void disabledHeadersAreLeftOut() {
        RequestDef r = new RequestDef();
        r.url = "https://x.test";
        r.headers.add(new KeyValue("X-Debug", "true", false));
        assertFalse(CurlExporter.export(r, null, false).contains("X-Debug"));
    }

    // ---- round trips: export then parse gives the same request -------------------------

    private static RequestDef roundTrip(RequestDef r, boolean insecure) throws Exception {
        CurlParser.Result res = CurlParser.parse(CurlExporter.export(r, null, insecure));
        assertEquals(insecure, res.insecure());
        assertTrue(res.ignored().isEmpty(), "nothing should be ignored: " + res.ignored());
        return res.request();
    }

    @Test
    void roundTripJsonPost() throws Exception {
        RequestDef r = new RequestDef();
        r.method = HttpMethod.POST;
        r.url = "https://x.test/v1/items?dry_run=true";
        r.headers.add(new KeyValue("X-Trace", "a'b\"c $HOME"));
        r.bodyType = BodyType.JSON;
        r.body = "{\n  \"name\": \"it's \\\"quoted\\\"\",\n  \"n\": 1\n}";
        RequestDef back = roundTrip(r, true);
        assertEquals(r.method, back.method);
        assertEquals(r.url, back.url);
        assertEquals("a'b\"c $HOME", back.header("X-Trace"));
        assertEquals(BodyType.JSON, back.bodyType);
        assertEquals(r.body, back.body);
    }

    @Test
    void roundTripForm() throws Exception {
        RequestDef r = new RequestDef();
        r.method = HttpMethod.POST;
        r.url = "https://x.test/login";
        r.bodyType = BodyType.FORM;
        r.form.add(new KeyValue("user name", "a&b=c"));
        r.form.add(new KeyValue("pass", "p@ss word+1"));
        RequestDef back = roundTrip(r, false);
        assertEquals(BodyType.FORM, back.bodyType);
        assertEquals(r.form, back.form);
    }

    @Test
    void roundTripBasicAuthAndMethods() throws Exception {
        for (HttpMethod m : HttpMethod.values()) {
            RequestDef r = new RequestDef();
            r.method = m;
            r.url = "https://x.test/r";
            r.auth = new Auth();
            r.auth.type = Auth.Type.BASIC;
            r.auth.username = "bob";
            r.auth.password = "p:w'd";
            RequestDef back = roundTrip(r, false);
            assertEquals(m, back.method);
            assertEquals(Auth.Type.BASIC, back.auth.type);
            assertEquals("bob", back.auth.username);
            assertEquals("p:w'd", back.auth.password);
        }
    }

    @Test
    void roundTripKeepsVariablesAndTimeout() throws Exception {
        RequestDef r = new RequestDef();
        r.method = HttpMethod.PATCH;
        r.url = "{{baseUrl}}/orders/{{id}}";
        r.headers.add(new KeyValue("X-Tenant-Id", "{{tenantId}}"));
        r.bodyType = BodyType.TEXT;
        r.body = "status={{status}}";
        r.timeoutSeconds = 12;
        RequestDef back = roundTrip(r, false);
        assertEquals(r.url, back.url);
        assertEquals("{{tenantId}}", back.header("X-Tenant-Id"));
        assertEquals(BodyType.TEXT, back.bodyType);
        assertEquals(r.body, back.body);
        assertEquals(12, back.timeoutSeconds);
    }

    @Test
    void roundTripWithCollectionVariableResolution() throws Exception {
        ApiCollection c = new ApiCollection();
        c.variables.add(new Variable("host", "https://col.example"));
        RequestDef r = new RequestDef();
        r.url = "{{host}}/x";
        String cmd = CurlExporter.export(r, new VariableResolver(null, c, null), false);
        assertEquals("https://col.example/x", CurlParser.parse(cmd).request().url);
    }

    @Test
    void multipartAndFileBodiesRoundTrip() throws Exception {
        RequestDef r = new RequestDef();
        r.method = HttpMethod.POST;
        r.url = "https://x.test/up";
        r.bodyType = BodyType.MULTIPART;
        r.headers.add(new KeyValue("Content-Type", "multipart/form-data"));
        r.multipart.add(new KeyValue("name", "it's Ann"));
        r.multipart.add(new KeyValue("at", "@home; really"));
        r.multipart.add(KeyValue.file("photo", "/tmp/a b.png", true));
        r.multipart.add(KeyValue.file("odd", "/tmp/x;y,z.bin", true));
        r.multipart.add(new KeyValue("off", "x", false));
        String cmd = CurlExporter.export(r, null, false);
        assertTrue(cmd.contains("-F 'name=it'\\''s Ann'"), cmd);
        assertTrue(cmd.contains("--form-string 'at=@home; really'"), cmd);
        assertTrue(cmd.contains("-F 'photo=@/tmp/a b.png'"), cmd);
        assertTrue(cmd.contains("-F 'odd=@\"/tmp/x;y,z.bin\"'"), cmd);
        assertFalse(cmd.contains("Content-Type"), "curl writes the multipart header itself");
        RequestDef back = CurlParser.parse(cmd).request();
        assertEquals(BodyType.MULTIPART, back.bodyType);
        assertEquals(r.multipart.subList(0, 4), back.multipart);

        RequestDef f = new RequestDef();
        f.method = HttpMethod.PUT;
        f.url = "https://x.test/f";
        f.bodyType = BodyType.BINARY;
        f.bodyFile = "/tmp/report.pdf";
        String fc = CurlExporter.export(f, null, false);
        assertTrue(fc.contains("--data-binary '@/tmp/report.pdf'") && fc.contains("-H 'Content-Type: application/pdf'"), fc);
        RequestDef fb = CurlParser.parse(fc).request();
        assertEquals(BodyType.BINARY, fb.bodyType);
        assertEquals("/tmp/report.pdf", fb.bodyFile);
    }
}
