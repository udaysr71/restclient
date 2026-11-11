package dev.restclient.store;

import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;

/**
 * The sample collection: a guided tour of the app against <a href="https://httpbin.org">httpbin.org</a>, a free
 * service that echoes back what it receives, plus a few <a href="https://badssl.com">badssl.com</a> hosts for
 * the certificate flows. Each folder shows one area of the app; request names say what to try.
 *
 * <p>Added on first launch (when there are no collections yet) and any time from Help › Add Sample Collection.
 * The same collection is in {@code samples/httpbin.postman_collection.json} for Postman users.
 */
public final class Samples {
    public static final String NAME = "Sample: httpbin";
    public static final String LOCAL_ENV = "Local httpbin (Docker)";

    private Samples() {
    }

    public static ApiCollection httpbin() {
        ApiCollection c = new ApiCollection();
        c.name = NAME;
        c.variables.add(new Variable("baseUrl", "https://httpbin.org"));
        c.variables.add(new Variable("username", "demo"));
        c.variables.add(secret("password", "s3cret-pass"));
        c.variables.add(secret("token", "demo-token-123"));
        c.variables.add(new Variable("userId", "7"));
        c.variables.add(new Variable("sampleUuid", "send-Capture-a-value-first"));
        c.variables.add(new Variable("greeting", "hello from REST Client"));

        CollectionNode basics = folder(c, "1 · Basics");
        add(basics, get("Simple GET", "{{baseUrl}}/get"));
        RequestDef q = get("Query parameters", "{{baseUrl}}/get?search=rest%20client&page=2&sort=desc");
        q.disabledParams.add(new KeyValue("debug", "true", false));
        add(basics, q);
        RequestDef h = get("Custom headers", "{{baseUrl}}/headers");
        h.headers.add(new KeyValue("X-Greeting", "{{greeting}}"));
        h.headers.add(new KeyValue("Accept", "application/json"));
        h.headers.add(new KeyValue("X-Not-Sent", "this row is disabled", false));
        add(basics, h);
        add(basics, req("PUT", HttpMethod.PUT, "{{baseUrl}}/put"));
        add(basics, req("PATCH", HttpMethod.PATCH, "{{baseUrl}}/patch"));
        add(basics, req("DELETE", HttpMethod.DELETE, "{{baseUrl}}/delete"));
        add(basics, req("HEAD (no body)", HttpMethod.HEAD, "{{baseUrl}}/get"));
        add(basics, req("OPTIONS", HttpMethod.OPTIONS, "{{baseUrl}}/get"));

        CollectionNode bodies = folder(c, "2 · Request bodies");
        RequestDef json = req("JSON body", HttpMethod.POST, "{{baseUrl}}/post");
        json.bodyType = BodyType.JSON;
        json.body = "{\"user\": \"{{username}}\", \"id\": {{userId}}, \"tags\": [\"sample\", \"json\"], \"active\": true}";
        add(bodies, json);
        RequestDef xml = req("XML body", HttpMethod.POST, "{{baseUrl}}/post");
        xml.bodyType = BodyType.XML;
        xml.body = "<?xml version=\"1.0\"?>\n<order>\n  <id>{{userId}}</id>\n  <item sku=\"MUG-01\">Coffee mug</item>\n</order>";
        add(bodies, xml);
        RequestDef text = req("Text body", HttpMethod.POST, "{{baseUrl}}/post");
        text.bodyType = BodyType.TEXT;
        text.body = "Any text you like.\nVariables work here too: {{greeting}}";
        add(bodies, text);
        RequestDef form = req("Form body", HttpMethod.POST, "{{baseUrl}}/post");
        form.bodyType = BodyType.FORM;
        form.form.add(new KeyValue("name", "{{username}}"));
        form.form.add(new KeyValue("message", "spaces & symbols are encoded"));
        form.form.add(new KeyValue("skipped", "disabled rows aren't sent", false));
        add(bodies, form);

        CollectionNode auth = folder(c, "3 · Auth");
        RequestDef basic = get("Basic auth", "{{baseUrl}}/basic-auth/{{username}}/{{password}}");
        basic.auth = basic("{{username}}", "{{password}}");
        add(auth, basic);
        RequestDef wrong = get("Basic auth, wrong password", "{{baseUrl}}/basic-auth/{{username}}/{{password}}");
        wrong.auth = basic("{{username}}", "not-the-password");
        add(auth, wrong);
        RequestDef bearer = get("Bearer token", "{{baseUrl}}/bearer");
        bearer.auth = new Auth();
        bearer.auth.type = Auth.Type.BEARER;
        bearer.auth.token = "{{token}}";
        add(auth, bearer);
        add(auth, get("Bearer token missing", "{{baseUrl}}/bearer"));

        CollectionNode responses = folder(c, "4 · Responses");
        add(responses, get("JSON", "{{baseUrl}}/json"));
        add(responses, get("XML", "{{baseUrl}}/xml"));
        add(responses, get("HTML", "{{baseUrl}}/html"));
        add(responses, get("Image", "{{baseUrl}}/image/png"));
        add(responses, get("gzip", "{{baseUrl}}/gzip"));
        add(responses, get("Response headers",
                "{{baseUrl}}/response-headers?X-Rate-Limit=60&Cache-Control=no-cache&X-Request-Id=sample-123"));
        add(responses, get("Redirects (3 hops)", "{{baseUrl}}/redirect/3"));
        add(responses, get("Status 404", "{{baseUrl}}/status/404"));
        add(responses, get("Status 500", "{{baseUrl}}/status/500"));
        add(responses, get("UTF-8 text", "{{baseUrl}}/encoding/utf8"));

        CollectionNode errors = folder(c, "5 · Timeouts and errors");
        add(errors, get("Slow (3 s)", "{{baseUrl}}/delay/3"));
        RequestDef timeout = get("Times out (2 s limit)", "{{baseUrl}}/delay/5");
        timeout.timeoutSeconds = 2;
        add(errors, timeout);
        add(errors, get("Connection refused", "http://localhost:1/"));
        add(errors, get("Unknown host", "https://no-such-host.invalid/"));

        CollectionNode tls = folder(c, "6 · Certificates");
        add(tls, get("Self-signed", "https://self-signed.badssl.com/"));
        add(tls, get("Wrong host name", "https://wrong.host.badssl.com/"));
        add(tls, get("Expired", "https://expired.badssl.com/"));
        RequestDef skip = get("Verification off", "https://self-signed.badssl.com/");
        skip.insecure = Boolean.TRUE;
        add(tls, skip);

        CollectionNode vars = folder(c, "7 · Variables");
        add(vars, get("Hover a variable", "{{baseUrl}}/anything/users/{{userId}}?greeting={{greeting}}"));
        add(vars, get("Switch environment", "{{baseUrl}}/anything/users/{{userId}}"));
        add(vars, get("Undefined variable", "{{baseUrl}}/anything/{{notDefinedYet}}"));

        CollectionNode chain = folder(c, "8 · Capture, built-ins, uploads");
        RequestDef capture = get("Capture a value", "{{baseUrl}}/uuid");
        capture.captures.add(new KeyValue("sampleUuid", "body.uuid"));
        capture.captures.add(new KeyValue("sampleType", "header.Content-Type"));
        add(chain, capture);
        add(chain, get("Use the captured value", "{{baseUrl}}/anything/{{sampleUuid}}"));
        RequestDef builtIns = req("Built-in variables", HttpMethod.POST, "{{baseUrl}}/post?requestId={{$guid}}");
        builtIns.bodyType = BodyType.JSON;
        builtIns.body = "{\n  \"id\": \"{{$guid}}\",\n  \"createdAt\": \"{{$isoTimestamp}}\",\n  \"seconds\": {{$timestamp}},\n  \"lucky\": {{$randomInt}}\n}";
        add(chain, builtIns);
        RequestDef multipart = req("Multipart form", HttpMethod.POST, "{{baseUrl}}/post");
        multipart.bodyType = BodyType.MULTIPART;
        multipart.multipart.add(new KeyValue("title", "Hello from {{username}}"));
        multipart.multipart.add(new KeyValue("note", "Set a row's Type to File to upload a file"));
        add(chain, multipart);
        return c;
    }

    /**
     * An environment that points the sample at a local httpbin, e.g. {@code docker run -p 8080:80 kennethreitz/httpbin},
     * and overrides userId, so switching environments shows variables changing.
     */
    public static Environment localEnvironment() {
        Environment e = new Environment();
        e.name = LOCAL_ENV;
        e.color = Environment.COLORS[4];
        e.variables.add(new Variable("baseUrl", "http://localhost:8080"));
        e.variables.add(new Variable("userId", "42"));
        return e;
    }

    /** Fixed ids for the files in samples/, so regenerating them gives the same files. */
    static final String SAMPLE_COLLECTION_ID = "8f6c2b1e-5a3d-4c7e-9b10-2f4e6a8c0d11";
    static final String SAMPLE_ENVIRONMENT_ID = "3d9a7c5e-1b2f-4e6a-8c0d-4f6a8b1c3e57";

    /** The Postman files in samples/, exactly as {@code java Build.java samples} writes them. */
    public static String postmanCollection() {
        ApiCollection c = httpbin();
        c.id = SAMPLE_COLLECTION_ID;
        return dev.restclient.postman.Postman.writeCollection(c);
    }

    public static String postmanEnvironment() {
        Environment e = localEnvironment();
        e.id = SAMPLE_ENVIRONMENT_ID;
        return dev.restclient.postman.Postman.writeEnvironment(e, true);
    }

    /** Writes the samples/ files. Run by {@code java Build.java samples}. */
    public static void main(String[] args) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of(args.length > 0 ? args[0] : "samples");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("httpbin.postman_collection.json"), postmanCollection());
        java.nio.file.Files.writeString(dir.resolve("local-httpbin.postman_environment.json"), postmanEnvironment());
    }

    private static Variable secret(String k, String v) {
        Variable x = new Variable(k, v);
        x.secret = true;
        return x;
    }

    private static Auth basic(String user, String pass) {
        Auth a = new Auth();
        a.type = Auth.Type.BASIC;
        a.username = user;
        a.password = pass;
        return a;
    }

    private static CollectionNode folder(ApiCollection c, String name) {
        CollectionNode f = CollectionNode.folder(name);
        c.items.add(f);
        return f;
    }

    private static void add(CollectionNode folder, RequestDef r) {
        CollectionNode n = CollectionNode.request(r);
        n.name = r.name;
        folder.children.add(n);
    }

    private static RequestDef get(String name, String url) {
        return req(name, HttpMethod.GET, url);
    }

    private static RequestDef req(String name, HttpMethod m, String url) {
        RequestDef r = new RequestDef();
        r.name = name;
        r.method = m;
        r.url = url;
        return r;
    }
}
