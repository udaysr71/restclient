package dev.restclient.openapi;

import dev.restclient.json.Json;
import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.testkit.Test;

import java.util.List;
import java.util.Map;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertFalse;
import static dev.restclient.testkit.Assertions.assertThrows;
import static dev.restclient.testkit.Assertions.assertTrue;

class OpenApiTest {
    static final String PETSTORE = """
            openapi: 3.0.3
            info:
              title: Pet Store
              version: 1.0.0
            servers:
              - url: https://{region}.petstore.example/v1/
                variables:
                  region:
                    default: eu
              - url: https://staging.petstore.example/v1
            tags:
              - name: pets
              - name: unused
            security:
              - bearer: []
            paths:
              /pets:
                parameters:
                  - $ref: '#/components/parameters/Trace'
                get:
                  tags: [pets]
                  summary: List pets
                  parameters:
                    - name: limit
                      in: query
                      required: true
                      schema: { type: integer, default: 20 }
                    - name: tag
                      in: query
                      schema: { type: string, enum: [dog, cat] }
                    - name: session
                      in: cookie
                      schema: { type: string }
                post:
                  tags: [pets]
                  operationId: createPet
                  requestBody:
                    content:
                      application/xml:
                        schema: { $ref: '#/components/schemas/Pet' }
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Pet'
              /pets/{petId}:
                get:
                  tags: [pets]
                  summary: Get a pet
                  security: []
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      example: 42
                      schema: { type: integer }
                delete:
                  tags: [admin]
                  security:
                    - key: []
                  parameters:
                    - { name: petId, in: path, required: true, schema: { type: integer } }
              /pets/{petId}/photo:
                post:
                  summary: Upload a photo
                  security:
                    - basic: []
                  requestBody:
                    content:
                      multipart/form-data:
                        schema:
                          type: object
                          properties:
                            caption: { type: string, example: Rex at the beach }
                            photo: { type: string, format: binary }
              /login:
                post:
                  security:
                    - oauth: [read]
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          allOf:
                            - type: object
                              properties:
                                user: { type: string }
                            - type: object
                              properties:
                                remember: { type: boolean }
              /export:
                put:
                  requestBody:
                    content:
                      application/octet-stream:
                        schema: { type: string, format: binary }
                trace:
                  summary: nope
            components:
              parameters:
                Trace:
                  name: X-Trace
                  in: header
                  schema: { type: string }
              securitySchemes:
                bearer: { type: http, scheme: bearer }
                basic: { type: http, scheme: basic }
                key: { type: apiKey, in: header, name: X-Api-Key }
                oauth: { type: oauth2, flows: {} }
              schemas:
                Pet:
                  type: object
                  properties:
                    id: { type: integer, readOnly: true }
                    name: { type: string, example: Rex }
                    born: { type: string, format: date }
                    tags:
                      type: array
                      items: { type: string }
                    owner: { $ref: '#/components/schemas/Owner' }
                Owner:
                  type: object
                  properties:
                    email: { type: string, format: email }
                    pets:
                      type: array
                      items: { $ref: '#/components/schemas/Pet' }
            """;

    private static RequestDef req(ApiCollection c, String name) {
        for (CollectionNode n : c.items) {
            if (n.request != null && n.name.equals(name)) return n.request;
            for (CollectionNode ch : n.children) if (ch.request != null && ch.name.equals(name)) return ch.request;
        }
        throw new AssertionError("no request named " + name);
    }

    private static String var(ApiCollection c, String name) {
        for (Variable v : c.variables) if (v.key.equals(name)) return v.value;
        return null;
    }

    @Test
    void openApi3YamlBecomesACollection() throws Exception {
        OpenApi.Result r = OpenApi.read(PETSTORE);
        ApiCollection c = r.collection();
        assertEquals("Pet Store", c.name);
        assertEquals(7, r.requests());
        assertEquals(2, r.folders(), "pets and admin; the unused tag makes no folder");
        assertEquals("pets", c.items.get(0).name);
        assertEquals(3, c.items.get(0).children.size());
        assertEquals("admin", c.items.get(1).name);
        assertEquals("https://eu.petstore.example/v1", var(c, "baseUrl"), "server variables filled in, trailing slash dropped");

        RequestDef list = req(c, "List pets");
        assertEquals(HttpMethod.GET, list.method);
        assertEquals("{{baseUrl}}/pets?limit=20", list.url, "required query parameters are in the URL");
        assertEquals("tag", list.disabledParams.get(0).key);
        assertEquals("dog", list.disabledParams.get(0).value, "optional ones are there, switched off");
        assertEquals("X-Trace", list.headers.get(0).key);
        assertFalse(list.headers.get(0).enabled, "an optional header from the path's shared parameters");
        assertEquals(Auth.Type.BEARER, list.auth.type);
        assertEquals("{{token}}", list.auth.token);

        RequestDef create = req(c, "createPet");
        assertEquals(BodyType.JSON, create.bodyType, "JSON is preferred when several types are offered");
        Map<?, ?> body = (Map<?, ?>) Json.parse(create.body);
        assertEquals("Rex", body.get("name"));
        assertEquals("2024-01-01", body.get("born"));
        assertFalse(body.containsKey("id"), "read-only fields are left out of request bodies");
        assertEquals("[\"string\"]", Json.write(body.get("tags"), false));
        Map<?, ?> owner = (Map<?, ?>) body.get("owner");
        assertEquals("user@example.com", owner.get("email"));
        assertEquals("[null]", Json.write(owner.get("pets"), false), "a schema that contains itself stops");

        RequestDef get = req(c, "Get a pet");
        assertEquals("{{baseUrl}}/pets/{{petId}}", get.url);
        assertEquals("42", var(c, "petId"));
        assertEquals(Auth.Type.NONE, get.auth.type, "security: [] switches auth off");

        RequestDef del = req(c, "DELETE /pets/{petId}");
        assertTrue(del.headers.stream().anyMatch(h -> h.key.equals("X-Api-Key") && h.value.equals("{{apiKey}}")));

        RequestDef photo = req(c, "Upload a photo");
        assertEquals(BodyType.MULTIPART, photo.bodyType);
        assertEquals("Rex at the beach", photo.multipart.get(0).value);
        assertTrue(photo.multipart.get(1).file && photo.multipart.get(1).value.isEmpty(), "binary property → file part to choose");
        assertEquals(Auth.Type.BASIC, photo.auth.type);
        assertEquals("{{password}}", photo.auth.password);

        RequestDef login = req(c, "POST /login");
        assertEquals(BodyType.FORM, login.bodyType);
        assertEquals(List.of("user", "remember"), login.form.stream().map(k -> k.key).toList());
        assertEquals("false", login.form.get(1).value);
        assertEquals(Auth.Type.BEARER, login.auth.type);

        assertEquals(BodyType.BINARY, req(c, "PUT /export").bodyType);
        for (Variable v : c.variables) if (v.key.equals("token") || v.key.equals("password") || v.key.equals("apiKey")) assertTrue(v.secret, v.key);

        String notes = String.join("\n", r.notes());
        assertTrue(notes.contains("lists 2 servers"), notes);
        assertTrue(notes.contains("1 cookie parameter was left out"), notes);
        assertTrue(notes.contains("TRACE"), notes);
        assertTrue(notes.contains("OAuth 2 sign-in isn't run"), notes);
    }

    @Test
    void swagger2JsonBecomesACollection() throws Exception {
        String swagger = """
                {"swagger": "2.0", "info": {"title": "Old API"}, "host": "api.old.example", "basePath": "/v2", "schemes": ["http", "https"],
                 "consumes": ["application/json"],
                 "securityDefinitions": {"k": {"type": "apiKey", "in": "query", "name": "api_key"}, "b": {"type": "basic"}},
                 "paths": {
                   "/users": {"post": {"summary": "Create user", "security": [{"k": []}],
                      "parameters": [{"in": "body", "name": "body", "schema": {"$ref": "#/definitions/User"}}]}},
                   "/users/{id}/avatar": {"post": {"consumes": ["multipart/form-data"], "security": [{"b": []}],
                      "parameters": [{"in": "path", "name": "id", "type": "string", "required": true},
                                     {"in": "formData", "name": "file", "type": "file"}, {"in": "formData", "name": "alt", "type": "string", "default": "me"}]}},
                   "/search": {"get": {"parameters": [{"in": "query", "name": "q", "type": "string", "required": true, "x-example": "a b&c"},
                                                      {"in": "formData", "name": "f", "type": "string"}]}}},
                 "definitions": {"User": {"type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "integer"}}}}}""";
        OpenApi.Result r = OpenApi.read(swagger);
        ApiCollection c = r.collection();
        assertEquals("https://api.old.example/v2", var(c, "baseUrl"), "https is preferred when offered");
        assertEquals(3, r.requests());
        assertEquals(0, r.folders());
        RequestDef create = req(c, "Create user");
        assertEquals(BodyType.JSON, create.bodyType);
        assertEquals("{\"name\":\"string\",\"age\":0}", Json.write(Json.parse(create.body), false));
        assertEquals("{{baseUrl}}/users?api_key={{apiKey}}", create.url);
        RequestDef avatar = req(c, "POST /users/{id}/avatar");
        assertEquals(BodyType.MULTIPART, avatar.bodyType);
        assertTrue(avatar.multipart.get(0).file);
        assertEquals("me", avatar.multipart.get(1).value);
        assertEquals(Auth.Type.BASIC, avatar.auth.type);
        RequestDef search = req(c, "GET /search");
        assertEquals("{{baseUrl}}/search?q=a%20b%26c", search.url, "values that would break the URL are encoded");
        assertEquals(BodyType.FORM, search.bodyType);
    }

    @Test
    void rejectsWhatItCannotRead() {
        assertTrue(assertThrows(OpenApi.ImportException.class, () -> OpenApi.read("{\"info\": {}, \"item\": []}")).getMessage().contains("doesn't look like"));
        assertTrue(assertThrows(OpenApi.ImportException.class, () -> OpenApi.read("openapi: 4.0.0\npaths: {}\n")).getMessage().contains("isn't supported"));
        assertTrue(assertThrows(OpenApi.ImportException.class, () -> OpenApi.read("swagger: '1.2'\npaths: {}\n")).getMessage().contains("isn't supported"));
        assertTrue(assertThrows(OpenApi.ImportException.class, () -> OpenApi.read("openapi: 3.0.0\ninfo: {}\n")).getMessage().contains("no paths"));
        assertTrue(assertThrows(OpenApi.ImportException.class, () -> OpenApi.read("openapi: 3.0.0\n\tpaths: {}\n")).getMessage().contains("line 2"));
        assertTrue(assertThrows(OpenApi.ImportException.class, () -> OpenApi.read("{\"openapi\": ")).getMessage().contains("valid JSON"));
        assertTrue(assertThrows(OpenApi.ImportException.class, () -> OpenApi.read("- a\n- b\n")).getMessage().contains("expected an object"));
    }

    @Test
    void untrustedContentIsContained() throws Exception {
        String spec = """
                openapi: 3.1.0
                info:
                  title: "<html><b>Evil</b>\\u0007 name"
                paths:
                  /a/{bad*name}/{ok}:
                    get:
                      summary: "<html><img src=x>"
                      tags: ["<html>tag"]
                      parameters:
                        - $ref: 'https://evil.example/params.yaml#/P'
                        - $ref: '../../etc/passwd'
                        - { name: "X-Bad\\r\\nInjected: 1", in: header, required: true, example: "v\\r\\nX-Other: 2" }
                      requestBody:
                        content:
                          application/json:
                            schema: { $ref: '#/components/schemas/A' }
                components:
                  schemas:
                    A: { $ref: '#/components/schemas/B' }
                    B: { $ref: '#/components/schemas/A' }
                """;
        OpenApi.Result r = OpenApi.read(spec);
        ApiCollection c = r.collection();
        assertFalse(c.name.toLowerCase().startsWith("<html"), c.name);
        assertFalse(c.name.contains("\u0007"));
        CollectionNode folder = c.items.get(0);
        assertFalse(folder.name.toLowerCase().startsWith("<html"), folder.name);
        RequestDef q = folder.children.get(0).request;
        assertFalse(q.name.toLowerCase().startsWith("<html"), q.name);
        assertEquals("{{baseUrl}}/a/{bad*name}/{{ok}}", q.url, "only names that can be variables become variables");
        assertEquals("X-BadInjected1", q.headers.get(0).key, "header names are reduced to legal characters");
        assertFalse(q.headers.get(0).value.contains("\n") || q.headers.get(0).value.contains("\r"));
        assertEquals("", q.body, "a reference loop gives no example rather than hanging");
        assertTrue(String.join("\n", r.notes()).contains("other files or URLs aren't followed"), r.notes().toString());
    }

    @Test
    void hugeSchemasStayBounded() throws Exception {
        StringBuilder s = new StringBuilder("{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"big\"},\"paths\":{\"/x\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/S0\"}}}}}}},\"components\":{\"schemas\":{");
        for (int i = 0; i < 30; i++) { // every schema refers to the next one ten times: 10^30 without a limit
            s.append(i == 0 ? "" : ",").append("\"S").append(i).append("\":{\"type\":\"object\",\"properties\":{");
            for (int k = 0; k < 10; k++) s.append(k == 0 ? "" : ",").append("\"p").append(k).append("\":{\"$ref\":\"#/components/schemas/S").append(i + 1).append("\"}");
            s.append("}}");
        }
        s.append(",\"S30\":{\"type\":\"string\"}}}}");
        long start = System.nanoTime();
        OpenApi.Result r = OpenApi.read(s.toString());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5000, "must finish quickly");
        assertTrue(r.collection().items.get(0).request.body.length() < 2_000_000);
    }

    @Test
    void recognisesOpenApiFiles() {
        assertTrue(OpenApi.looksLike(PETSTORE));
        assertTrue(OpenApi.looksLike("{\"swagger\": \"2.0\"}"));
        assertTrue(OpenApi.looksLike("{ \"info\": {}, \"openapi\":\"3.0.0\"}"));
        assertFalse(OpenApi.looksLike("{\"info\": {\"name\": \"x\"}, \"item\": []}"));
    }
}
