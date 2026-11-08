package dev.restclient.postman;

import dev.restclient.json.Json;
import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.testkit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertTrue;

class PostmanTest {
    /** Shaped like a real Postman v2.1 export, with the awkward parts. */
    private static final String V21 = """
            {
              "info": {
                "_postman_id": "5c1b1f8e-0000-4000-8000-000000000001",
                "name": "Shop API",
                "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
              },
              "auth": { "type": "bearer", "bearer": [ { "key": "token", "value": "{{token}}", "type": "string" } ] },
              "variable": [
                { "key": "baseUrl", "value": "https://api.example.com", "type": "string" },
                { "key": "apiSecret", "value": "s3cr3t", "type": "secret" },
                { "key": "page", "value": 2 }
              ],
              "event": [ { "listen": "prerequest", "script": { "exec": [ "pm.environment.set('t', Date.now());" ] } } ],
              "item": [
                {
                  "name": "Orders",
                  "item": [
                    {
                      "name": "List orders",
                      "request": {
                        "method": "GET",
                        "header": [ { "key": "Accept", "value": "application/json" }, { "key": "X-Debug", "value": "1", "disabled": true } ],
                        "url": {
                          "raw": "{{baseUrl}}/orders?status=open&limit={{page}}",
                          "host": [ "{{baseUrl}}" ], "path": [ "orders" ],
                          "query": [ { "key": "status", "value": "open" }, { "key": "limit", "value": "{{page}}" },
                                     { "key": "debug", "value": "true", "disabled": true } ]
                        }
                      },
                      "response": [ { "name": "200 example", "code": 200, "body": "[]" } ]
                    },
                    {
                      "name": "Create order",
                      "event": [ { "listen": "test", "script": { "exec": [ "pm.test('ok', () => {});" ] } } ],
                      "request": {
                        "method": "POST",
                        "header": [],
                        "body": { "mode": "raw", "raw": "{\\"sku\\": \\"A1\\"}", "options": { "raw": { "language": "json" } } },
                        "url": "{{baseUrl}}/orders"
                      }
                    },
                    {
                      "name": "Get order",
                      "request": {
                        "method": "GET",
                        "url": { "raw": "{{baseUrl}}/orders/:orderId", "host": ["{{baseUrl}}"], "path": ["orders", ":orderId"],
                                 "variable": [ { "key": "orderId", "value": "42" } ] }
                      }
                    }
                  ]
                },
                {
                  "name": "Admin",
                  "auth": { "type": "basic", "basic": [ { "key": "username", "value": "admin" }, { "key": "password", "value": "{{adminPw}}" } ] },
                  "item": [
                    { "name": "Stats", "request": { "method": "GET", "url": "{{baseUrl}}/admin/stats" } },
                    { "name": "Public ping", "request": { "method": "GET", "auth": { "type": "noauth" }, "url": "{{baseUrl}}/ping" } },
                    { "name": "Upload", "request": { "method": "POST", "url": "{{baseUrl}}/upload",
                        "body": { "mode": "formdata", "formdata": [ { "key": "note", "value": "hi", "type": "text" },
                                                                    { "key": "file", "type": "file", "src": "/tmp/a.png" } ] } } },
                    { "name": "Login form", "request": { "method": "POST", "url": "{{baseUrl}}/login",
                        "body": { "mode": "urlencoded", "urlencoded": [ { "key": "user", "value": "ann" }, { "key": "remember", "value": "1", "disabled": true } ] } } }
                  ]
                },
                { "name": "GraphQL", "request": { "method": "POST", "url": "{{baseUrl}}/graphql",
                    "body": { "mode": "graphql", "graphql": { "query": "{ me { id } }", "variables": "{\\"a\\": 1}" } } } },
                { "name": "With API key", "request": { "method": "GET", "url": "{{baseUrl}}/keyed",
                    "auth": { "type": "apikey", "apikey": [ { "key": "key", "value": "X-Api-Key" }, { "key": "value", "value": "{{apiSecret}}" }, { "key": "in", "value": "header" } ] } } },
                { "name": "OAuth thing", "request": { "method": "GET", "url": "{{baseUrl}}/o", "auth": { "type": "oauth2", "oauth2": [] } } },
                { "name": "Insecure", "protocolProfileBehavior": { "strictSSL": false }, "request": { "method": "GET", "url": "https://self-signed.local/" } },
                { "name": "XML raw no language", "request": { "method": "PUT", "header": [ { "key": "Content-Type", "value": "application/xml" } ],
                    "body": { "mode": "raw", "raw": "<a/>" }, "url": "{{baseUrl}}/x" } }
              ]
            }
            """;

    private static CollectionNode find(List<CollectionNode> nodes, String name) {
        for (CollectionNode n : nodes) {
            if (n.name.equals(name)) return n;
            CollectionNode c = find(n.children, name);
            if (c != null) return c;
        }
        return null;
    }

    private static RequestDef req(ApiCollection c, String name) {
        CollectionNode n = find(c.items, name);
        assertTrue(n != null && n.request != null, "request " + name);
        return n.request;
    }

    @Test
    void readsAPostmanV21CollectionWithEverythingItCanRepresent() throws Exception {
        Postman.Result r = Postman.read(V21);
        ApiCollection c = r.collection();
        assertEquals("Shop API", c.name);
        assertEquals(12, r.requests());
        assertEquals(2, r.folders());

        assertEquals(3, c.variables.size());
        assertTrue(c.variables.get(1).secret, "secret variable type");
        assertEquals("2", c.variables.get(2).value, "numbers become text");

        RequestDef list = req(c, "List orders");
        assertEquals("{{baseUrl}}/orders?status=open&limit={{page}}", list.url);
        assertEquals(1, list.disabledParams.size());
        assertEquals("debug", list.disabledParams.get(0).key);
        assertFalse(list.headers.get(1).enabled, "disabled header kept, disabled");
        assertEquals(Auth.Type.BEARER, list.auth.type, "auth inherited from the collection");
        assertEquals("{{token}}", list.auth.token);

        RequestDef create = req(c, "Create order");
        assertEquals(HttpMethod.POST, create.method);
        assertEquals(BodyType.JSON, create.bodyType);
        assertEquals("{\"sku\": \"A1\"}", create.body);
        assertEquals("{{baseUrl}}/orders", create.url, "url as a plain string");

        assertEquals("{{baseUrl}}/orders/42", req(c, "Get order").url, ":path variables are filled in");

        RequestDef stats = req(c, "Stats");
        assertEquals(Auth.Type.BASIC, stats.auth.type, "auth inherited from the folder");
        assertEquals("admin", stats.auth.username);
        assertEquals(Auth.Type.NONE, req(c, "Public ping").auth.type, "noauth beats inherited auth");

        RequestDef upload = req(c, "Upload");
        assertEquals(BodyType.FORM, upload.bodyType);
        assertEquals(1, upload.form.size(), "text field kept, file left out");

        RequestDef login = req(c, "Login form");
        assertEquals(BodyType.FORM, login.bodyType);
        assertFalse(login.form.get(1).enabled);

        RequestDef gql = req(c, "GraphQL");
        assertEquals(BodyType.JSON, gql.bodyType);
        Map<?, ?> body = (Map<?, ?>) Json.parse(gql.body);
        assertEquals("{ me { id } }", body.get("query"));
        assertTrue(body.get("variables") instanceof Map<?, ?>, "GraphQL variables kept as JSON");

        RequestDef keyed = req(c, "With API key");
        assertTrue(keyed.headers.stream().anyMatch(h -> h.key.equals("X-Api-Key") && h.value.equals("{{apiSecret}}")), "API key → header");

        assertEquals(Boolean.TRUE, req(c, "Insecure").insecure, "strictSSL false → skip verification");
        assertEquals(BodyType.XML, req(c, "XML raw no language").bodyType, "raw body type from Content-Type");

        String notes = String.join("\n", r.notes());
        assertTrue(notes.contains("2 scripts were left out"), notes);
        assertTrue(notes.contains("1 file upload"), notes);
        assertTrue(notes.contains("1 saved example response"), notes);
        assertTrue(notes.contains("“oauth2” auth isn't supported"), notes);
        assertTrue(notes.contains("Multipart"), notes);
    }

    @Test
    void readsV20AuthObjectsAndStringHeaders() throws Exception {
        String v20 = """
                { "info": { "name": "Old", "schema": "https://schema.getpostman.com/json/collection/v2.0.0/collection.json" },
                  "item": [ { "name": "A", "request": { "method": "get", "url": "http://h/a",
                      "header": "X-One: 1\\nX-Two: two words",
                      "auth": { "type": "basic", "basic": { "username": "u", "password": "p" } } } } ] }
                """;
        ApiCollection c = Postman.read(v20).collection();
        RequestDef a = c.items.get(0).request;
        assertEquals(HttpMethod.GET, a.method, "lowercase method");
        assertEquals("u", a.auth.username);
        assertEquals(2, a.headers.size());
        assertEquals("two words", a.headers.get(1).value);
    }

    @Test
    void readsPostmanEnvironments() throws Exception {
        String env = """
                { "id": "x", "name": "Staging", "_postman_variable_scope": "environment",
                  "values": [ { "key": "baseUrl", "value": "https://staging", "enabled": true, "type": "default" },
                              { "key": "token", "value": "abc", "enabled": true, "type": "secret" },
                              { "key": "off", "value": "1", "enabled": false } ] }
                """;
        Postman.Result r = Postman.read(env);
        assertFalse(r.isCollection());
        Environment e = r.environment();
        assertEquals("Staging", e.name);
        assertEquals(3, e.variables.size());
        assertTrue(e.variables.get(1).secret);
        assertFalse(e.variables.get(2).enabled);
    }

    @Test
    void writesV21ThatReadsBackTheSame() throws Exception {
        ApiCollection c = new ApiCollection();
        c.name = "Round trip";
        c.variables.add(new Variable("baseUrl", "http://localhost:8080"));
        Variable s = new Variable("key", "k1");
        s.secret = true;
        c.variables.add(s);
        CollectionNode folder = CollectionNode.folder("Users");
        RequestDef a = new RequestDef();
        a.name = "Find";
        a.method = HttpMethod.GET;
        a.url = "{{baseUrl}}/users?q=ann&limit=5";
        a.disabledParams.add(new KeyValue("debug", "1", false));
        a.headers.add(new KeyValue("Accept", "application/json"));
        a.headers.add(new KeyValue("X-Off", "1", false));
        a.auth = new Auth();
        a.auth.type = Auth.Type.BEARER;
        a.auth.token = "{{key}}";
        a.insecure = Boolean.TRUE;
        folder.children.add(CollectionNode.request(a));
        RequestDef b = new RequestDef();
        b.name = "Create";
        b.method = HttpMethod.POST;
        b.url = "https://api.example.com:8443/v1/users";
        b.bodyType = BodyType.JSON;
        b.body = "{\"name\": \"Ann\"}";
        b.auth = new Auth();
        b.auth.type = Auth.Type.BASIC;
        b.auth.username = "u";
        b.auth.password = "p";
        RequestDef f = new RequestDef();
        f.name = "Form";
        f.method = HttpMethod.POST;
        f.url = "http://h/f";
        f.bodyType = BodyType.FORM;
        f.form.add(new KeyValue("a", "1"));
        f.form.add(new KeyValue("b", "2", false));
        c.items.add(folder);
        c.items.add(CollectionNode.request(b));
        c.items.add(CollectionNode.request(f));

        String json = Postman.writeCollection(c);
        Map<?, ?> root = (Map<?, ?>) Json.parse(json);
        assertEquals(Postman.SCHEMA_21, ((Map<?, ?>) root.get("info")).get("schema"));
        // the URL in object form, so Postman shows Params
        Map<?, ?> url = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) root.get("item")).get(0)).get("item")).get(0)).get("request")).get("url");
        assertEquals(List.of("{{baseUrl}}"), url.get("host"));
        assertEquals(List.of("users"), url.get("path"));
        assertEquals(3, ((List<?>) url.get("query")).size());
        Map<?, ?> createUrl = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((List<?>) root.get("item")).get(1)).get("request")).get("url");
        assertEquals("https", createUrl.get("protocol"));
        assertEquals("8443", createUrl.get("port"));
        assertEquals(List.of("api", "example", "com"), createUrl.get("host"));

        ApiCollection back = Postman.read(json).collection();
        assertEquals("Round trip", back.name);
        assertTrue(back.variables.get(1).secret);
        RequestDef a2 = back.items.get(0).children.get(0).request;
        assertEquals(a.url, a2.url);
        assertEquals(1, a2.disabledParams.size());
        assertFalse(a2.headers.get(1).enabled);
        assertEquals("{{key}}", a2.auth.token);
        assertEquals(Boolean.TRUE, a2.insecure);
        RequestDef b2 = back.items.get(1).request;
        assertEquals(b.url, b2.url);
        assertEquals(BodyType.JSON, b2.bodyType);
        assertEquals(b.body, b2.body);
        assertEquals("p", b2.auth.password);
        RequestDef f2 = back.items.get(2).request;
        assertEquals(BodyType.FORM, f2.bodyType);
        assertFalse(f2.form.get(1).enabled);
        assertTrue(Postman.read(json).notes().isEmpty(), "a round trip has nothing to report");
    }

    @Test
    void environmentsLeaveSecretsOutUnlessAsked() throws Exception {
        Environment e = new Environment();
        e.name = "Prod";
        e.variables.add(new Variable("host", "h"));
        Variable s = new Variable("token", "top-secret");
        s.secret = true;
        e.variables.add(s);
        String without = Postman.writeEnvironment(e, false);
        assertFalse(without.contains("top-secret"));
        assertTrue(without.contains("\"secret\""));
        assertTrue(Postman.writeEnvironment(e, true).contains("top-secret"));
        Environment back = Postman.read(Postman.writeEnvironment(e, true)).environment();
        assertEquals("top-secret", back.variables.get(1).value);
        assertTrue(back.variables.get(1).secret);
    }

    @Test
    void untrustedFilesAreHandledSafely() throws Exception {
        String evil = """
                { "info": { "name": "<html><img src='http://example.com/x.png'>Evil\\u0007" }, "item": [
                  { "name": "\\u202Eexe.txt", "request": "http://h/" } ] }
                """;
        ApiCollection c = Postman.read(evil).collection();
        assertFalse(c.name.toLowerCase().startsWith("<html"), "can't start Swing HTML: " + c.name);
        assertFalse(c.name.contains("\u0007"), "control characters removed");
        assertFalse(c.items.get(0).name.contains("‮"), "bidi overrides removed");
        assertEquals("http://h/", c.items.get(0).request.url, "request given as a URL string");
        for (String bad : List.of("[]", "not json", "{\"requests\": [], \"order\": []}", "{\"foo\": 1}")) {
            boolean refused = false;
            try {
                Postman.read(bad);
            } catch (Postman.ImportException e) {
                refused = true;
                assertFalse(e.getMessage().isBlank());
            }
            assertTrue(refused, bad);
        }
        StringBuilder deep = new StringBuilder("{\"info\":{\"name\":\"d\"},\"item\":");
        List<String> close = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            deep.append("[{\"name\":\"f\",\"item\":");
            close.add("}]");
        }
        deep.append("[]").append(String.join("", close)).append("}");
        boolean refused = false;
        try {
            Postman.read(deep.toString());
        } catch (Postman.ImportException e) {
            refused = true;
        }
        assertTrue(refused, "absurd nesting is refused, not a stack overflow");
    }

    @Test
    void fileNames() {
        assertEquals("shop-api.postman_collection.json", Postman.fileName("Shop API!", "collection"));
        assertEquals("export.postman_environment.json", Postman.fileName("✓✓", "environment"));
    }
}
