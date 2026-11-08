package dev.restclient.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.restclient.App;
import dev.restclient.http.ResponseData;
import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HistoryEntry;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Settings;
import dev.restclient.model.Variable;
import dev.restclient.store.AppPaths;
import dev.restclient.store.Repository;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import static dev.restclient.ui.Drive.*;

/**
 * End-to-end feature check. Starts the real app against a recording HTTP and HTTPS server, drives it through its
 * window (actions, keyboard, dialogs) and checks what the server received and what was saved to disk.
 *
 * <p>Phase 1 ({@code main}) runs every feature, then saves the session. Phase 2 ({@code main --relaunch <home>})
 * starts a fresh app on the same data folder and checks that everything came back. {@code java Build.java uicheck}
 * runs both after the interaction check.
 */
public final class FeatureCheck {
    /** One request as the test server received it. */
    record Seen(String method, String path, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    private static String http;
    private static String https;
    private static Path home;

    private FeatureCheck() {
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            failures.add("uncaught on " + t.getName() + ": " + e);
        });
        if (args.length == 2 && args[0].equals("--first-launch")) {
            home = Path.of(args[1]);
            if (Files.exists(home)) throw new IllegalStateException("--first-launch needs a folder that doesn't exist yet");
            System.setProperty("restclient.home", home.toString());
            launch();
            firstLaunchChecks();
            finish();
            return;
        }
        if (args.length == 2 && args[0].equals("--relaunch")) {
            home = Path.of(args[1]);
            System.setProperty("restclient.home", home.toString());
            launch();
            relaunchChecks();
            finish();
            return;
        }
        home = args.length == 2 && args[0].equals("--home") ? Path.of(args[1]) : Files.createTempDirectory("rc-features");
        Files.createDirectories(home);
        System.setProperty("restclient.home", home.toString());
        startServers();
        seed();
        launch();
        List<Runnable> sections = List.of(
                FeatureCheck::sendingRequests, FeatureCheck::bodiesHeadersAndAuth, FeatureCheck::variables,
                FeatureCheck::responses, FeatureCheck::errorsTimeoutsAndCancel, FeatureCheck::tls,
                FeatureCheck::curl, FeatureCheck::collections, FeatureCheck::saving, FeatureCheck::historySection,
                FeatureCheck::environments, FeatureCheck::palette, FeatureCheck::tabsAndShortcuts,
                FeatureCheck::settings, FeatureCheck::helpAndDialogs, FeatureCheck::throughTheUi, FeatureCheck::postman, FeatureCheck::sampleCollection, FeatureCheck::prepareRelaunch);
        for (Runnable s : sections) {
            try {
                s.run();
            } catch (Throwable t) {
                t.printStackTrace();
                verify("section finished without an exception", false, t.toString());
            }
            try {
                closeDialogs();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        Files.writeString(home.resolve("feature-check-home.txt"), home.toString());
        finish();
    }

    private static void finish() {
        System.out.println();
        System.out.println(passed + " feature checks passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        // phase 1 prints the folder for phase 2
        if (!failures.isEmpty()) System.exit(1);
        System.exit(0);
    }

    // ------------------------------------------------------------------ setup

    private static void launch() throws Exception {
        App.main(new String[0]);
        robot = new java.awt.Robot();
        robot.setAutoDelay(10);
        long end = System.currentTimeMillis() + 20000;
        while (frame == null && System.currentTimeMillis() < end) {
            for (Frame f : Frame.getFrames()) if (f instanceof MainFrame m && m.isShowing()) frame = m;
            Thread.sleep(100);
        }
        if (frame == null) throw new IllegalStateException("main window never showed");
        robot.delay(800);
        closeDialogs(); // e.g. Quick Start on a first launch
    }

    private static void seed() throws Exception {
        Repository r = new Repository(new AppPaths(home));
        r.load();
        r.globals().variables.add(new Variable("baseUrl", http));
        r.globals().variables.add(new Variable("httpsUrl", https));
        r.saveEnvironment(r.globals());

        ApiCollection c = new ApiCollection();
        c.name = "Seed API";
        c.variables.add(new Variable("userId", "7"));
        CollectionNode folder = CollectionNode.folder("Users");
        RequestDef get = new RequestDef();
        get.name = "Get user";
        get.url = "{{baseUrl}}/echo/users/{{userId}}";
        folder.children.add(CollectionNode.request(get));
        c.items.add(folder);
        r.addCollection(c);

        Environment staging = new Environment();
        staging.name = "Staging";
        staging.variables.add(new Variable("userId", "42"));
        Variable token = new Variable("token", "secret-abc-123");
        token.secret = true;
        staging.variables.add(token);
        r.addEnvironment(staging);
        Environment prod = new Environment();
        prod.name = "Prod";
        prod.variables.add(new Variable("userId", "1"));
        r.addEnvironment(prod);
        r.setActiveEnvironment(staging);
        r.settings().theme = Settings.ThemeMode.LIGHT;
        r.saveSettings();
        r.flush();
    }

    private static void startServers() throws Exception {
        HttpServer h = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        h.createContext("/", FeatureCheck::handle);
        h.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        h.start();
        http = "http://127.0.0.1:" + h.getAddress().getPort();

        // a self-signed certificate for 127.0.0.1, made with the JDK's keytool
        Path ks = Files.createTempDirectory("rc-tls").resolve("server.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "srv", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=Feature Check Server", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new IllegalStateException("keytool failed");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        HttpsServer s = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(ssl));
        s.createContext("/", FeatureCheck::handle);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.start();
        https = "https://127.0.0.1:" + s.getAddress().getPort();
    }

    private static void handle(HttpExchange ex) throws java.io.IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        SEEN.add(new Seen(ex.getRequestMethod(), path, headers, body));
        String p = ex.getRequestURI().getPath();
        try {
            if (p.startsWith("/hb/")) {
                try {
                    httpbin(ex, p.substring(3), headers, body);
                } catch (InterruptedException | java.io.IOException e) {
                    throw e;
                } catch (Exception e) {
                    reply(ex, 500, "text/plain", String.valueOf(e).getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (p.startsWith("/status/")) {
                int code = Integer.parseInt(p.substring(8));
                reply(ex, code, "application/json", ("{\"status\":" + code + "}").getBytes(StandardCharsets.UTF_8));
            } else if (p.equals("/slow")) {
                Thread.sleep(Long.parseLong(query(ex, "ms", "3000")));
                reply(ex, 200, "application/json", "{\"slow\":true}".getBytes(StandardCharsets.UTF_8));
            } else if (p.equals("/big")) {
                StringBuilder b = new StringBuilder("[");
                for (int i = 0; i < 40000; i++) b.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"item ").append(i).append("\"}");
                reply(ex, 200, "application/json", b.append(']').toString().getBytes(StandardCharsets.UTF_8));
            } else if (p.equals("/xml")) {
                reply(ex, 200, "application/xml", "<?xml version=\"1.0\"?><order><id>5</id><item sku=\"A\">Mug</item></order>".getBytes(StandardCharsets.UTF_8));
            } else if (p.equals("/html")) {
                reply(ex, 200, "text/html; charset=utf-8", "<html><body><h1>Hi</h1></body></html>".getBytes(StandardCharsets.UTF_8));
            } else if (p.equals("/binary")) {
                byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0};
                reply(ex, 200, "image/png", png);
            } else if (p.equals("/png")) {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "png", bos);
                reply(ex, 200, "image/png", bos.toByteArray());
            } else if (p.equals("/octets")) {
                reply(ex, 200, "application/octet-stream", new byte[300]);
            } else if (p.equals("/redirect")) {
                ex.getResponseHeaders().add("Location", "/echo/after-redirect");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } else if (p.equals("/gzip")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                    gz.write("{\"compressed\":true}".getBytes(StandardCharsets.UTF_8));
                }
                ex.getResponseHeaders().add("Content-Encoding", "gzip");
                reply(ex, 200, "application/json", bos.toByteArray());
            } else if (p.equals("/latin1")) {
                reply(ex, 200, "text/plain; charset=ISO-8859-1", "café".getBytes(StandardCharsets.ISO_8859_1));
            } else if (p.equals("/empty")) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } else {
                String json = "{\"method\":\"" + ex.getRequestMethod() + "\",\"path\":\"" + path.replace("\"", "\\\"") + "\"}";
                ex.getResponseHeaders().add("X-Server", "feature-check");
                reply(ex, 200, "application/json", ex.getRequestMethod().equals("HEAD") ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException ignored) {
            // client went away (cancel or timeout tests)
        }
    }

    /** Enough of httpbin.org, with the same behaviour, to run the sample collection end to end. */
    private static void httpbin(HttpExchange ex, String p, Map<String, String> headers, String body) throws Exception {
        String m = ex.getRequestMethod();
        String auth = headers.getOrDefault("authorization", "");
        if (p.startsWith("/basic-auth/")) {
            String[] up = p.substring(12).split("/");
            String want = "Basic " + Base64.getEncoder().encodeToString((up[0] + ":" + (up.length > 1 ? up[1] : "")).getBytes(StandardCharsets.UTF_8));
            if (want.equals(auth)) reply(ex, 200, "application/json", ("{\"authenticated\": true, \"user\": \"" + up[0] + "\"}").getBytes(StandardCharsets.UTF_8));
            else {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Fake Realm\"");
                reply(ex, 401, "text/plain", new byte[0]);
            }
        } else if (p.equals("/bearer")) {
            if (auth.startsWith("Bearer ") && auth.length() > 7) reply(ex, 200, "application/json", ("{\"authenticated\": true, \"token\": \"" + auth.substring(7) + "\"}").getBytes(StandardCharsets.UTF_8));
            else reply(ex, 401, "text/plain", new byte[0]);
        } else if (p.startsWith("/status/")) {
            reply(ex, Integer.parseInt(p.substring(8)), "text/html; charset=utf-8", new byte[0]);
        } else if (p.startsWith("/redirect/")) {
            int n = Integer.parseInt(p.substring(10));
            ex.getResponseHeaders().add("Location", n > 1 ? "/hb/redirect/" + (n - 1) : "/hb/get");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } else if (p.startsWith("/delay/")) {
            Thread.sleep(Long.parseLong(p.substring(7)) * 1000);
            reply(ex, 200, "application/json", "{\"delayed\": true}".getBytes(StandardCharsets.UTF_8));
        } else if (p.equals("/json")) {
            reply(ex, 200, "application/json", "{\"slideshow\": {\"author\": \"Yours Truly\", \"slides\": [{\"title\": \"Wake up\"}]}}".getBytes(StandardCharsets.UTF_8));
        } else if (p.equals("/xml")) {
            reply(ex, 200, "application/xml", "<?xml version='1.0'?><slideshow title=\"Sample\"><slide><title>Wake up</title></slide></slideshow>".getBytes(StandardCharsets.UTF_8));
        } else if (p.equals("/html")) {
            reply(ex, 200, "text/html; charset=utf-8", "<!DOCTYPE html><html><body><h1>Herman Melville - Moby-Dick</h1></body></html>".getBytes(StandardCharsets.UTF_8));
        } else if (p.equals("/image/png")) {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bos);
            reply(ex, 200, "image/png", bos.toByteArray());
        } else if (p.equals("/gzip")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write("{\"gzipped\": true}".getBytes(StandardCharsets.UTF_8));
            }
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            reply(ex, 200, "application/json", bos.toByteArray());
        } else if (p.equals("/response-headers")) {
            String q = ex.getRequestURI().getRawQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) ex.getResponseHeaders().add(part.substring(0, eq), java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            reply(ex, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        } else if (p.equals("/encoding/utf8")) {
            reply(ex, 200, "text/html; charset=utf-8", "∮ E⋅da = Q, ℝ, ᚻᛖ ᚳᚹᚫᚦ, Ελληνικά".getBytes(StandardCharsets.UTF_8));
        } else if (p.equals("/get") || p.equals("/post") || p.equals("/put") || p.equals("/patch") || p.equals("/delete")
                || p.equals("/headers") || p.startsWith("/anything")) {
            String expected = p.startsWith("/anything") || p.equals("/headers") || p.equals("/get") && (m.equals("HEAD") || m.equals("OPTIONS"))
                    ? m : p.substring(1).toUpperCase(Locale.ROOT);
            if (!p.startsWith("/anything") && !p.equals("/headers") && !m.equals("OPTIONS") && !m.equals("HEAD") && !m.equals(expected)) {
                reply(ex, 405, "text/html", new byte[0]);
                return;
            }
            if (m.equals("OPTIONS")) ex.getResponseHeaders().add("Allow", "GET, HEAD, OPTIONS");
            String json = "{\"method\": \"" + m + "\", \"data\": " + quote(body) + "}";
            reply(ex, 200, "application/json", m.equals("OPTIONS") ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
        } else {
            reply(ex, 404, "text/html", new byte[0]);
        }
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder();
        dev.restclient.json.Json.quote(b, s);
        return b.toString();
    }

    private static String query(HttpExchange ex, String key, String def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String part : q.split("&")) if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        return def;
    }

    private static void reply(HttpExchange ex, int code, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(code, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    // ------------------------------------------------------------------ helpers

    private static RequestTab newTab() {
        RequestTab before = currentTab();
        run("new");
        waitFor("a new tab", () -> currentTab() != null && currentTab() != before, 3000);
        return currentTab();
    }

    private static void url(RequestTab t, String u) {
        JTextComponent f = field(t, "urlField");
        edt(() -> f.setText(u));
    }

    private static void method(RequestTab t, HttpMethod m) {
        JComboBox<?> box = field(t, "methodBox");
        edt(() -> box.setSelectedItem(m));
    }

    private static List<HistoryEntry> history() {
        return call(() -> new ArrayList<>(ctx().repo.history()));
    }

    /** Sends the tab's request (like pressing Send) and waits for it to finish; returns the history entry it made. */
    private static HistoryEntry send(RequestTab t) {
        HistoryEntry before = call(() -> ctx().repo.history().isEmpty() ? null : ctx().repo.history().get(0));
        edt(t::send);
        waitFor("the response", () -> field(t, "call") == null && !ctx().repo.history().isEmpty()
                && ctx().repo.history().get(0) != before, 15000);
        robot.delay(80);
        return call(() -> ctx().repo.history().get(0));
    }

    private static Seen lastSeen() {
        return SEEN.isEmpty() ? null : SEEN.get(SEEN.size() - 1);
    }

    private static ResponsePanel response(RequestTab t) {
        return field(t, "response");
    }

    private static ResponseData shown(RequestTab t) {
        return call(() -> field(response(t), "current"));
    }

    private static String bodyText(RequestTab t) {
        return call(() -> ((CodeArea) field(response(t), "body")).getText());
    }

    private static boolean responseSays(RequestTab t, String text) {
        return call(() -> anyLabel(response(t), s -> s.contains(text)));
    }

    private static KeyValue kv(String k, String v, boolean on) {
        KeyValue x = new KeyValue(k, v);
        x.enabled = on;
        return x;
    }

    private static Environment env(String name) {
        return call(() -> ctx().repo.environments().stream().filter(e -> e.name.equals(name)).findFirst().orElse(null));
    }

    private static ApiCollection collection(String name) {
        return call(() -> ctx().repo.collections().stream().filter(c -> c.name.equals(name)).findFirst().orElse(null));
    }

    private static void promptAnswer(String text) {
        robot.delay(200);
        key(MOD, KeyEvent.VK_A);
        type(text);
        key(KeyEvent.VK_ENTER);
        robot.delay(300);
        refocus();
    }

    /** Presses the "don't save" button, whatever the platform calls it. */
    private static boolean discard() {
        for (String label : List.of("Discard All", "Discard", "Don't Save", "Don’t Save")) {
            if (visibleButton(label) != null) return press(label);
        }
        return false;
    }

    private static String readFile(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 1. sending

    private static void sendingRequests() {
        section("Sending requests: every method, query strings, redirects, keyboard send");
        RequestTab t = newTab();
        url(t, "{{baseUrl}}/echo/plain?x=1&y=two");
        HistoryEntry h = send(t);
        Seen s = lastSeen();
        verify("GET reaches the server with its query string", s != null && s.method().equals("GET") && s.path().equals("/echo/plain?x=1&y=two"), String.valueOf(s));
        check("The response shows status 200", () -> shown(t) != null && shown(t).status == 200);
        verify("History records the request", h.status == 200 && h.sentUrl.endsWith("/echo/plain?x=1&y=two"), h.sentUrl);
        check("The Params table shows the query string", () -> {
            KeyValueTable p = field(t, "paramsTable");
            return p.getRows().stream().anyMatch(k -> k.key.equals("y") && k.value.equals("two"));
        });

        for (HttpMethod m : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS)) {
            method(t, m);
            url(t, "{{baseUrl}}/echo/method");
            send(t);
            Seen x = lastSeen();
            verify(m + " is sent as " + m, x.method().equals(m.name()), x.method());
        }
        check("HEAD shows a response with no body", () -> {
            method(t, HttpMethod.HEAD);
            return true;
        });

        method(t, HttpMethod.GET);
        url(t, "{{baseUrl}}/redirect");
        send(t);
        verify("Redirects are followed to the final URL", lastSeen().path().equals("/echo/after-redirect"), lastSeen().path());
        check("...and the final response is shown", () -> shown(t).status == 200);

        // keyboard: focus URL, change it, Ctrl+Enter sends
        url(t, "{{baseUrl}}/echo/keyboard");
        run("focusUrl");
        int before = SEEN.size();
        key(MOD, KeyEvent.VK_ENTER);
        check("Ctrl/Cmd+Enter sends from the URL field", () -> SEEN.size() > before && lastSeen().path().equals("/echo/keyboard"), 6000);

        url(t, "127.0.0.1:" + http.substring(http.lastIndexOf(':') + 1) + "/echo/noscheme");
        send(t);
        verify("A URL without a scheme gets http://", lastSeen().path().equals("/echo/noscheme"), lastSeen().path());
    }

    // ------------------------------------------------------------------ 2. bodies, headers, auth

    private static void bodiesHeadersAndAuth() {
        section("Bodies, headers and auth as the server receives them");
        RequestTab t = newTab();
        method(t, HttpMethod.POST);
        url(t, "{{baseUrl}}/echo/body");
        Ui.Segmented bt = field(t, "bodyType");
        CodeArea editor = field(t, "bodyEditor");

        segment(bt, BodyType.JSON.ordinal());
        edt(() -> editor.setText("{\"name\": \"Ann\", \"n\": 3}"));
        send(t);
        verify("JSON body is sent as typed", lastSeen().body().equals("{\"name\": \"Ann\", \"n\": 3}"), lastSeen().body());
        verify("...with Content-Type application/json", String.valueOf(lastSeen().header("Content-Type")).startsWith("application/json"),
                lastSeen().header("Content-Type"));

        segment(bt, BodyType.XML.ordinal());
        edt(() -> editor.setText("<a>1</a>"));
        send(t);
        verify("XML body with an XML Content-Type", lastSeen().body().equals("<a>1</a>")
                && String.valueOf(lastSeen().header("Content-Type")).contains("xml"), lastSeen().header("Content-Type"));

        segment(bt, BodyType.TEXT.ordinal());
        edt(() -> editor.setText("plain words"));
        send(t);
        verify("Text body with text/plain", lastSeen().body().equals("plain words")
                && String.valueOf(lastSeen().header("Content-Type")).startsWith("text/plain"), lastSeen().header("Content-Type"));

        KeyValueTable form = field(t, "formTable");
        segment(bt, BodyType.FORM.ordinal());
        edt(() -> form.setRows(List.of(kv("a", "1", true), kv("b", "x y&z", true), kv("off", "no", false))));
        send(t);
        verify("Form body is URL-encoded, skipping disabled rows", lastSeen().body().equals("a=1&b=x+y%26z")
                || lastSeen().body().equals("a=1&b=x%20y%26z"), lastSeen().body());
        verify("...with Content-Type application/x-www-form-urlencoded",
                String.valueOf(lastSeen().header("Content-Type")).startsWith("application/x-www-form-urlencoded"), lastSeen().header("Content-Type"));

        segment(bt, BodyType.NONE.ordinal());
        send(t);
        verify("Body None sends no body", lastSeen().body().isEmpty(), lastSeen().body());

        KeyValueTable headers = field(t, "headersTable");
        edt(() -> headers.setRows(List.of(kv("X-Test", "{{userId}}", true), kv("X-Off", "no", false), kv("Accept", "text/plain", true))));
        send(t);
        verify("Headers are sent, with variables resolved", "42".equals(lastSeen().header("X-Test")), lastSeen().header("X-Test"));
        verify("Disabled headers are not sent", lastSeen().header("X-Off") == null, null);
        verify("A header you set replaces the default", "text/plain".equals(lastSeen().header("Accept")), lastSeen().header("Accept"));

        JComboBox<?> authType = field(t, "authType");
        JTextComponent user = field(t, "authUser");
        JTextComponent pass = field(t, "authPass");
        JTextComponent token = field(t, "authToken");
        edt(() -> headers.setRows(List.of()));
        edt(() -> authType.setSelectedIndex(1));
        edt(() -> user.setText("ann"));
        edt(() -> pass.setText("p@ss:word"));
        send(t);
        String basic = "Basic " + Base64.getEncoder().encodeToString("ann:p@ss:word".getBytes(StandardCharsets.UTF_8));
        verify("Basic auth sends the encoded username:password", basic.equals(lastSeen().header("Authorization")), lastSeen().header("Authorization"));

        edt(() -> authType.setSelectedIndex(2));
        edt(() -> token.setText("{{token}}"));
        HistoryEntry h = send(t);
        verify("Bearer auth resolves the secret variable", "Bearer secret-abc-123".equals(lastSeen().header("Authorization")),
                lastSeen().header("Authorization"));
        call(() -> {
            ctx().repo.flush();
            return null;
        });
        String saved = readFile(ctx().repo.paths().history());
        verify("The secret value is not written to history on disk", !saved.contains("secret-abc-123") && saved.contains("echo/body"),
                saved.contains("secret-abc-123") ? "history.json contains the secret" : "history.json missing the entry");
        verify("The history entry keeps the variable name instead", h.request != null && "{{token}}".equals(h.request.auth.token),
                h.request == null ? "no request" : h.request.auth.token);

        edt(() -> headers.setRows(List.of(kv("Authorization", "Custom abc", true))));
        send(t);
        verify("An Authorization header you set wins over the Auth tab", "Custom abc".equals(lastSeen().header("Authorization")),
                lastSeen().header("Authorization"));
        edt(() -> authType.setSelectedIndex(0));
        edt(() -> headers.setRows(List.of()));
        send(t);
        verify("No auth sends no Authorization header", lastSeen().header("Authorization") == null, lastSeen().header("Authorization"));
    }

    // ------------------------------------------------------------------ 3. variables

    private static void variables() {
        section("Variables: precedence, switching environments, undefined variables");
        ApiCollection seed = collection("Seed API");
        CollectionNode get = call(() -> {
            CollectionNode[] r = {null};
            seed.forEachRequest(n -> {
                if (n.name.equals("Get user")) r[0] = n;
            });
            return r[0];
        });
        edt(() -> frame.openSaved(seed, get, false));
        RequestTab t = currentTab();
        check("Opening a saved request shows it in a tab", () -> t.title().equals("Get user") && t.nodeId().equals(get.id));
        send(t);
        verify("Environment beats collection: userId = 42 (Staging)", lastSeen().path().equals("/echo/users/42"), lastSeen().path());

        Environment prod = env("Prod");
        edt(() -> invoke(frame, "switchEnvironment", prod));
        check("Switching environment updates the toolbar button", () -> anyLabel(frame.getContentPane(), s -> s.contains("Prod")));
        send(t);
        verify("Switching to Prod gives userId = 1", lastSeen().path().equals("/echo/users/1"), lastSeen().path());

        edt(() -> invoke(frame, "switchEnvironment", (Environment) null));
        send(t);
        verify("No environment falls back to the collection: userId = 7", lastSeen().path().equals("/echo/users/7"), lastSeen().path());

        Environment staging = env("Staging");
        edt(() -> invoke(frame, "switchEnvironment", staging));

        edt(() -> frame.openSaved(seed, get, false));
        check("Opening an already-open request selects its tab instead of duplicating it",
                () -> currentTab() == t && countTabsFor(get.id) == 1);

        RequestTab u = newTab();
        url(u, "{{baseUrl}}/echo/{{missingVar}}");
        send(u);
        check("An undefined variable is reported on the response", () -> responseSays(u, "missingVar"));
        verify("...and the request still goes out with the placeholder", lastSeen().path().contains("missingVar"), lastSeen().path());

        later(() -> frame.defineVariable("missingVar"));
        JDialog envDialog = topDialog();
        check("Define… opens Environments", () -> envDialog != null && envDialog.isShowing());
        JTable vars = envDialog == null ? null : find(envDialog.getContentPane(), JTable.class);
        check("...with a new missingVar row", () -> vars != null && rowWithKey(vars, "missingVar") >= 0);
        if (vars != null) {
            int r = call(() -> rowWithKey(vars, "missingVar"));
            edt(() -> vars.setValueAt("defined-now", r, 2));
        }
        closeDialogs();
        send(u);
        verify("Once defined, the variable resolves", lastSeen().path().equals("/echo/defined-now"), lastSeen().path());
        check("The variable was saved to the active environment", () -> env("Staging").variables.stream()
                .anyMatch(v -> v.key.equals("missingVar") && v.value.equals("defined-now")));

        url(u, "{{baseUrl}}/echo/{{ userId }}");
        send(u);
        verify("Spaces inside braces are allowed", lastSeen().path().equals("/echo/42"), lastSeen().path());
    }

    private static int rowWithKey(JTable t, String key) {
        for (int i = 0; i < t.getModel().getRowCount(); i++) if (key.equals(t.getModel().getValueAt(i, 1))) return i;
        return -1;
    }

    private static int countTabsFor(String nodeId) {
        int n = 0;
        for (int i = 0; i < tabStrip().getTabCount(); i++) {
            if (tabStrip().getComponentAt(i) instanceof RequestTab r && nodeId.equals(r.nodeId())) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 4. responses

    private static void responses() {
        section("Responses: formats, views, find, copy, large and compressed bodies");
        RequestTab t = newTab();
        url(t, "{{baseUrl}}/echo/pretty");
        send(t);
        check("JSON is pretty-printed", () -> bodyText(t).contains("\n  \"method\""));
        Ui.Segmented views = field(response(t), "views");
        segment(views, 1);
        check("Raw shows the body exactly as received", () -> bodyText(t).startsWith("{\"method\":\"GET\""));
        segment(views, 2);
        HeadersView hv = field(response(t), "headersView");
        JTable ht = hv.table();
        check("Headers view lists the response headers", () -> {
            for (int i = 0; i < ht.getRowCount(); i++) if ("x-server".equalsIgnoreCase(String.valueOf(ht.getValueAt(i, 0)))) return true;
            return false;
        });
        check("...with the same row height and inset as the request tables", () -> ht.getRowHeight() == Theme.scaled(30)
                && hv.getInsets().left == 14 && ht.isShowing());
        // resize the columns by dragging the divider in the header
        int before = call(() -> ht.getColumnModel().getColumn(0).getWidth());
        java.awt.Point divider = call(() -> {
            javax.swing.table.JTableHeader h = ht.getTableHeader();
            java.awt.Point p = new java.awt.Point(ht.getColumnModel().getColumn(0).getWidth() - 1, h.getHeight() / 2);
            javax.swing.SwingUtilities.convertPointToScreen(p, h);
            return p;
        });
        robot.mouseMove(divider.x, divider.y);
        robot.delay(150);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        for (int i = 1; i <= 10; i++) {
            robot.mouseMove(divider.x + i * 12, divider.y);
            robot.delay(30);
        }
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        check("Dragging the header divider resizes the columns", () -> ht.getColumnModel().getColumn(0).getWidth() >= before + 80);
        robot.mouseMove(divider.x + 120, divider.y);
        doubleClickAt(new java.awt.Point(divider.x + 119, divider.y));
        check("Double-clicking the divider fits the names again", () -> Math.abs(ht.getColumnModel().getColumn(0).getWidth() - before) <= 4);
        javax.swing.JButton copyBtn = field(response(t), "copyButton");
        check("The Copy button says it copies headers in this view", () -> "Copy all headers".equals(copyBtn.getToolTipText()));
        edt(copyBtn::doClick);
        check("...and copies every header as Name: Value lines", () -> {
            String c = clipboard();
            return c.lines().count() == ht.getRowCount() && c.lines().anyMatch(l -> l.equalsIgnoreCase("x-server: feature-check"));
        });
        int serverRow = call(() -> {
            for (int i = 0; i < ht.getRowCount(); i++) if ("x-server".equalsIgnoreCase(String.valueOf(ht.getValueAt(i, 0)))) return i;
            return 0;
        });
        clickCell(ht, serverRow, 1);
        key(MOD, KeyEvent.VK_C);
        check("Ctrl/Cmd+C copies the selected row", () -> clipboard().equalsIgnoreCase("x-server: feature-check"));
        edt(() -> ht.setRowSelectionInterval(0, 1));
        edt(() -> ht.requestFocusInWindow());
        key(MOD, KeyEvent.VK_C);
        check("...or all the selected rows", () -> clipboard().lines().count() == 2);
        java.awt.Point cell = call(() -> {
            java.awt.Rectangle r = ht.getCellRect(serverRow, 1, true);
            java.awt.Point p = new java.awt.Point(r.x + 30, r.y + r.height / 2);
            javax.swing.SwingUtilities.convertPointToScreen(p, ht);
            return p;
        });
        clickAt(cell, java.awt.event.InputEvent.BUTTON3_DOWN_MASK, 1);
        check("Right-click offers Copy Value, Copy Name, Copy Row and Copy All Headers", () -> visibleButton("Copy Value") != null
                && visibleButton("Copy Name") != null && visibleButton("Copy Row") != null && visibleButton("Copy All Headers") != null);
        press("Copy Value");
        check("Copy Value copies just the value", () -> clipboard().equals("feature-check"));
        segment(views, 0);
        check("Status, time and size are shown", () -> responseSays(t, "200") && responseSays(t, " B"));

        // find
        edt(() -> response(t).showFind());
        JTextField find = field(response(t), "findField");
        edt(() -> find.setText("method"));
        check("Find counts matches", () -> anyLabel(response(t), s -> s.matches(".*1 of 1.*|.*1/1.*|.*1 match.*")));
        edt(() -> find.setText("nothing-here"));
        check("Find says when there's no match", () -> anyLabel(response(t), s -> s.equals("Not found")));
        key(KeyEvent.VK_ESCAPE);

        edt(() -> invoke(response(t), "copyBody"));
        check("Copy puts the shown body on the clipboard", () -> clipboard().contains("\"method\""));

        url(t, "{{baseUrl}}/xml");
        send(t);
        check("XML is pretty-printed", () -> bodyText(t).contains("<order>\n") && bodyText(t).contains("  <id>5</id>"));
        url(t, "{{baseUrl}}/html");
        send(t);
        check("HTML is shown as text", () -> bodyText(t).contains("<h1>Hi</h1>"));
        url(t, "{{baseUrl}}/binary");
        send(t);
        check("A damaged image says it couldn't be previewed", () -> {
            javax.swing.JLabel l = field(response(t), "binaryLabel");
            return l.isShowing() && "This image couldn't be previewed".equals(l.getText());
        });
        url(t, "{{baseUrl}}/png");
        send(t);
        check("A real image is previewed", () -> {
            javax.swing.JLabel l = field(response(t), "binaryLabel");
            return l.isShowing() && l.getIcon() != null && l.getIcon().getIconWidth() == 3;
        });
        url(t, "{{baseUrl}}/octets");
        send(t);
        check("Other binary bodies show a summary with type and size", () -> {
            javax.swing.JLabel l = field(response(t), "binaryLabel");
            return "Binary content".equals(l.getText()) && responseSays(t, "application/octet-stream · 300 B");
        });
        url(t, "{{baseUrl}}/gzip");
        send(t);
        check("gzip responses are decompressed", () -> bodyText(t).contains("\"compressed\": true"));
        url(t, "{{baseUrl}}/latin1");
        send(t);
        check("The charset from Content-Type is honoured", () -> bodyText(t).contains("café"));
        url(t, "{{baseUrl}}/empty");
        send(t);
        check("204 No Content shows the status and an empty body", () -> shown(t).status == 204);
        url(t, "{{baseUrl}}/status/404");
        HistoryEntry e404 = send(t);
        check("4xx responses are shown with their status", () -> shown(t).status == 404 && responseSays(t, "404"));
        url(t, "{{baseUrl}}/status/503");
        send(t);
        check("5xx responses are shown with their status", () -> shown(t).status == 503);
        verify("History stores 4xx entries with their status", e404.status == 404, String.valueOf(e404.status));

        url(t, "{{baseUrl}}/big");
        long start = System.currentTimeMillis();
        send(t);
        long took = System.currentTimeMillis() - start;
        check("A 1.4 MB JSON response is shown", () -> bodyText(t).length() > 1_000_000);
        verify("...in reasonable time", took < 8000, took + " ms");
        check("...and the window stays responsive", () -> {
            long s0 = System.nanoTime();
            edt(() -> { });
            return System.nanoTime() - s0 < 500_000_000L;
        });
    }

    // ------------------------------------------------------------------ 5. errors, timeout, cancel

    private static void errorsTimeoutsAndCancel() {
        section("Errors, timeouts and cancelling");
        RequestTab t = newTab();
        url(t, "http://127.0.0.1:1/nothing");
        HistoryEntry h = send(t);
        check("Connection refused explains itself", () -> responseSays(t, "Couldn't connect"));
        check("...and offers Retry", () -> visibleButton("Retry") != null);
        verify("Errors are recorded in history", h.errorKind != null && h.errorKind.equals("CONNECT"), h.errorKind);

        url(t, "http://no-such-host.invalid/x");
        send(t);
        check("An unknown host says it couldn't find the host (not \"connection refused\")", () -> responseSays(t, "Couldn't find"));

        JSpinnerHolder.set(t, 1);
        url(t, "{{baseUrl}}/slow?ms=3000");
        send(t);
        check("A 1 s timeout stops a 3 s request", () -> responseSays(t, "timed out"));
        JSpinnerHolder.set(t, 0);

        url(t, "{{baseUrl}}/slow?ms=4000");
        edt(t::send);
        check("While sending, the Send button becomes Cancel", () -> {
            javax.swing.JButton b = field(t, "sendButton");
            return "Cancel".equals(b.getText());
        });
        check("...and a loading state is shown", () -> ((javax.swing.JLabel) field(response(t), "loadingLabel")).isShowing());
        edt(t::cancel);
        check("Cancel stops it and says so", () -> responseSays(t, "cancelled"), 4000);
        check("...and the button is Send again", () -> {
            javax.swing.JButton b = field(t, "sendButton");
            return "Send".equals(b.getText());
        });

        url(t, "");
        edt(t::send);
        check("An empty URL is refused with an explanation", () -> responseSays(t, "couldn't be sent") || responseSays(t, "URL"));
    }

    /** The request's timeout spinner. */
    private static final class JSpinnerHolder {
        static void set(RequestTab t, int seconds) {
            javax.swing.JSpinner s = field(t, "timeout");
            edt(() -> s.setValue(seconds));
        }
    }

    // ------------------------------------------------------------------ 6. TLS

    private static void tls() {
        section("HTTPS: untrusted certificates, skipping verification, trusting a certificate");
        RequestTab t = newTab();
        url(t, "{{httpsUrl}}/echo/secure");
        HistoryEntry h = send(t);
        verify("A self-signed certificate is refused by default", "TLS_UNTRUSTED".equals(h.errorKind), h.errorKind);
        check("...with an explanation and both ways forward", () -> responseSays(t, "isn't trusted")
                && visibleButton("View & Trust…") != null && visibleButton("Retry Insecurely") != null);

        int before = SEEN.size();
        press("Retry Insecurely");
        waitFor("insecure retry", () -> SEEN.size() > before && field(t, "call") == null, 8000);
        robot.delay(200);
        verify("Retry Insecurely reaches the server", lastSeen().path().equals("/echo/secure"), lastSeen().path());
        check("...turns off verification for this request only", () -> Boolean.TRUE.equals(t.draft().insecure));
        check("...and shows the TLS off badge", () -> ((javax.swing.JButton) field(t, "tlsBadge")).isShowing());

        RequestTab other = newTab();
        url(other, "{{httpsUrl}}/echo/still-verified");
        HistoryEntry h2 = send(other);
        verify("Other requests still verify certificates", "TLS_UNTRUSTED".equals(h2.errorKind), h2.errorKind);

        run("toggleTls");
        check("Toggle TLS Verification turns it off for the current request", () -> Boolean.TRUE.equals(other.draft().insecure));
        send(other);
        verify("...and the request goes through", lastSeen().path().equals("/echo/still-verified"), lastSeen().path());
        run("toggleTls");
        check("Toggling again turns verification back on", () -> !Boolean.TRUE.equals(other.draft().insecure));

        HistoryEntry h3 = send(other);
        verify("(verification is back)", "TLS_UNTRUSTED".equals(h3.errorKind), h3.errorKind);
        pressLater("View & Trust…");
        check("View & Trust opens the certificate dialog", () -> anyDialog() && dialogShowing(d -> anyLabel(d, s -> s.contains("Feature Check Server"))), 4000);
        int seenBefore = SEEN.size();
        pressLater("Trust & Save");
        waitFor("trusted resend", () -> SEEN.size() > seenBefore, 8000);
        robot.delay(300);
        verify("Trust & Save resends with verification on", lastSeen().path().equals("/echo/still-verified")
                && !Boolean.TRUE.equals(other.draft().insecure), lastSeen().path());
        check("The certificate is saved in the certificates folder", () -> {
            try (var s = Files.list(ctx().repo.paths().certs())) {
                return s.anyMatch(p -> p.getFileName().toString().matches(".*\\.(pem|crt|cer)"));
            }
        });
        RequestTab third = newTab();
        url(third, "{{httpsUrl}}/echo/trusted-now");
        HistoryEntry h4 = send(third);
        verify("Every request now trusts that server", h4.status == 200 && h4.errorKind == null, h4.errorKind);
    }

    // ------------------------------------------------------------------ 7. cURL

    private static void curl() {
        section("cURL: copy (resolved and with variables), import, paste");
        RequestTab t = newTab();
        method(t, HttpMethod.POST);
        url(t, "{{baseUrl}}/echo/curl?id={{userId}}");
        KeyValueTable headers = field(t, "headersTable");
        edt(() -> headers.setRows(List.of(kv("X-A", "1", true), kv("X-Off", "0", false))));
        Ui.Segmented bt = field(t, "bodyType");
        CodeArea editor = field(t, "bodyEditor");
        segment(bt, BodyType.JSON.ordinal());
        edt(() -> editor.setText("{\"it's\": 1}"));

        run("copyCurl");
        String resolved = clipboard();
        verify("Copy as cURL resolves variables", resolved.contains(http + "/echo/curl?id=42"), resolved);
        verify("...includes method, enabled headers and body", resolved.contains("-X POST") && resolved.contains("X-A: 1")
                && !resolved.contains("X-Off") && resolved.contains("it'\\''s"), resolved);
        run("copyCurlVars");
        String kept = clipboard();
        verify("Copy as cURL (keep variables) keeps {{userId}}", kept.contains("{{userId}}") && kept.contains("{{baseUrl}}"), kept);

        edt(() -> t.draft().insecure = Boolean.TRUE);
        run("copyCurl");
        verify("An insecure request copies with -k", clipboard().contains(" -k") || clipboard().contains("--insecure"), clipboard());
        edt(() -> t.draft().insecure = null);

        // round trip: copy, import into a new tab, send: the server sees the same request
        run("copyCurl");
        String cmd = clipboard();
        send(t);
        Seen original = lastSeen();
        later(() -> frame.showCurlImport(cmd));
        check("Import cURL opens with the command", () -> anyDialog());
        press("Import");
        RequestTab imported = currentTab();
        check("Importing opens it in a tab", () -> imported != t && imported.method() == HttpMethod.POST);
        send(imported);
        Seen again = lastSeen();
        verify("Copy → import → send reproduces the request", again.path().equals(original.path()) && again.body().equals(original.body())
                && "1".equals(again.header("X-A")) && again.method().equals("POST"), again + " vs " + original);

        RequestTab p = newTab();
        edt(() -> frame.importCurlInto(p, "curl -u bob:pw -H 'X-From: paste' --data-urlencode 'q=a b' " + http + "/echo/pasted"));
        send(p);
        verify("Pasted cURL with -u and --data-urlencode works", lastSeen().path().equals("/echo/pasted")
                && ("Basic " + Base64.getEncoder().encodeToString("bob:pw".getBytes())).equals(lastSeen().header("Authorization"))
                && lastSeen().body().equals("q=a+b") || lastSeen().body().equals("q=a%20b"), lastSeen().toString());
    }

    // ------------------------------------------------------------------ 8. collections

    private static void collections() {
        section("Collections: create, rename, folders, duplicate, delete with undo, filter");
        later(frame::newCollection);
        promptAnswer("Feature API");
        ApiCollection c = collection("Feature API");
        verify("New Collection creates it", c != null, null);
        if (c == null) return;
        check("...saved to disk", () -> Files.list(ctx().repo.paths().collections()).anyMatch(p -> readFile(p).contains("Feature API")));

        later(() -> frame.renameCollection(c));
        promptAnswer("Feature API v2");
        check("Rename Collection", () -> c.name.equals("Feature API v2"));
        check("...renames the file on disk too", () -> Files.list(ctx().repo.paths().collections())
                .anyMatch(p -> p.getFileName().toString().contains("feature-api-v2")));

        CollectionsPanel panel = call(() -> ((Sidebar) field(frame, "sidebar")).collections());
        later(() -> invoke(panel, "newFolder", c, (List<CollectionNode>) null));
        promptAnswer("Orders");
        CollectionNode folder = call(() -> c.items.stream().filter(n -> n.folder && n.name.equals("Orders")).findFirst().orElse(null));
        verify("New Folder", folder != null, null);
        if (folder == null) return;

        refocus();
        edt(() -> frame.newRequestIn(c, folder.children));
        RequestTab t = currentTab();
        check("New Request in a folder opens it in a tab, saved there", () -> folder.children.size() == 1
                && t.nodeId().equals(folder.children.get(0).id));
        check("...with the cursor in the URL", () -> focusOwner() == field(t, "urlField"));
        CollectionNode req = folder.children.get(0);

        later(() -> frame.renameNode(c, req));
        promptAnswer("List orders");
        check("Rename Request renames it and its tab", () -> req.name.equals("List orders") && t.title().equals("List orders"));

        edt(() -> frame.duplicateNode(c, req));
        check("Duplicate puts a copy right after it", () -> folder.children.size() == 2
                && folder.children.get(1).name.equals("List orders copy"));
        CollectionNode copy = folder.children.get(1);

        later(() -> frame.deleteNode(c, copy));
        check("Delete asks first", () -> anyDialog());
        press("Delete");
        check("Delete removes it", () -> folder.children.size() == 1);
        press("Undo");
        check("Undo puts it back where it was", () -> folder.children.size() == 2 && folder.children.get(1) == copy);

        edt(() -> frame.duplicateCollection(c));
        check("Duplicate Collection copies everything", () -> {
            ApiCollection d = collection("Feature API v2 copy");
            return d != null && d.items.size() == 1 && d.items.get(0).children.size() == 2;
        });
        ApiCollection dup = collection("Feature API v2 copy");
        later(() -> frame.deleteCollection(dup));
        check("Delete Collection asks first, naming its requests", () -> dialogShowing(d -> anyLabel(d, s -> s.contains("2 requests"))));
        press("Delete");
        check("Delete Collection removes it", () -> collection("Feature API v2 copy") == null);
        press("Undo");
        check("Undo restores the collection", () -> collection("Feature API v2 copy") != null);

        // filter
        JTextField filter = field(panel, "filter");
        JTree tree = field(panel, "tree");
        edt(() -> filter.setText("list ord"));
        check("Filter shows only matching requests (and their folders)", () -> visibleRequests(tree).size() >= 2
                && visibleRequests(tree).stream().allMatch(n -> n.toLowerCase().contains("list ord")));
        edt(() -> filter.setText(""));
        check("Clearing the filter shows everything again", () -> visibleRequests(tree).size() > 2);

        // opening from the tree with the keyboard
        edt(() -> panel.select(req.id));
        edt(() -> tree.requestFocusInWindow());
        robot.delay(150);
        RequestTab beforeOpen = currentTab();
        edt(() -> frame.closeTab(tabStrip().getSelectedIndex()));
        closeDialogs();
        refocus();
        edt(() -> panel.select(req.id));
        edt(tree::requestFocusInWindow);
        robot.delay(150);
        key(KeyEvent.VK_ENTER);
        check("Enter on a request in the tree opens it", () -> currentTab() != null && req.id.equals(currentTab().nodeId())
                && currentTab() != beforeOpen);
    }

    /** Selects a folder (or the collection itself when folder is null) in the Save dialog's tree. */
    private static void selectFolder(JTree tree, ApiCollection c, String folder) {
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            Object o = ((javax.swing.tree.DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            Object top = ((javax.swing.tree.DefaultMutableTreeNode) p.getPathComponent(1)).getUserObject();
            if (top != c) continue;
            if (folder == null ? o == c : o instanceof CollectionNode n && n.name.equals(folder)) {
                tree.setSelectionPath(p);
                return;
            }
        }
    }

    private static List<String> visibleRequests(JTree tree) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            Object o = ((javax.swing.tree.DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject();
            if (o instanceof CollectionNode n && !n.folder) out.add(n.name);
        }
        return out;
    }

    // ------------------------------------------------------------------ 9. saving

    private static void saving() {
        section("Saving: Save, Save As into a folder, dirty state, re-saving edits");
        RequestTab t = newTab();
        url(t, "{{baseUrl}}/echo/saving");
        check("A new request with changes isn't saved yet", () -> !t.isSaved());
        ApiCollection c = collection("Feature API v2");
        later(() -> frame.saveTab(t, false));
        JDialog d = topDialog();
        check("Save opens Save Request", () -> d != null && d.isShowing());
        if (d == null) return;
        JTextField name = find(d.getContentPane(), JTextField.class);
        JTree tree = find(d.getContentPane(), JTree.class);
        edt(() -> name.setText("Saved via dialog"));
        edt(() -> selectFolder(tree, c, "Orders"));
        press("Save");
        CollectionNode orders = c.items.stream().filter(n -> n.folder && n.name.equals("Orders")).findFirst().orElseThrow();
        check("Save puts it in the chosen folder", () -> orders.children.stream().anyMatch(n -> n.name.equals("Saved via dialog")
                && n.request.url.equals("{{baseUrl}}/echo/saving")));
        check("...and the tab is now saved and clean", () -> t.isSaved() && !t.isDirty() && t.title().equals("Saved via dialog"));
        check("...and Save is disabled", () -> !action("save").isEnabled());

        url(t, "{{baseUrl}}/echo/saving-edited");
        check("Editing marks it dirty and enables Save", () -> t.isDirty() && action("save").isEnabled());
        refocus();
        key(MOD, KeyEvent.VK_S);
        check("Ctrl/Cmd+S saves in place without asking", () -> !anyDialog() && !t.isDirty()
                && orders.children.stream().anyMatch(n -> n.request.url.equals("{{baseUrl}}/echo/saving-edited")));
        check("...to disk", () -> Files.list(ctx().repo.paths().collections()).anyMatch(p -> readFile(p).contains("saving-edited")));

        later(() -> frame.saveTab(t, true));
        JDialog d2 = topDialog();
        JTextField name2 = d2 == null ? null : find(d2.getContentPane(), JTextField.class);
        if (name2 != null) edt(() -> name2.setText("Saved as copy"));
        press("Save");
        check("Save As makes a second, separate request", () -> {
            int[] n = {0};
            c.forEachRequest(x -> {
                if (x.name.startsWith("Saved")) n[0]++;
            });
            return n[0] == 2 && t.title().equals("Saved as copy");
        });

        // closing a dirty tab asks; Don't Save discards
        url(t, "{{baseUrl}}/echo/discard-me");
        later(() -> frame.closeTab(tabStrip().getSelectedIndex()));
        check("Closing an edited saved request asks to save", () -> anyDialog());
        discard();
        check("Don't Save closes without saving", () -> !anyDialog() && c.find(t.nodeId()) != null
                && !c.find(t.nodeId()).request.url.contains("discard-me"));
    }

    // ------------------------------------------------------------------ 10. history

    private static void historySection() {
        section("History: open, open as new request, save, filter, delete with undo, clear with undo");
        Sidebar sidebar = field(frame, "sidebar");
        edt(() -> frame.showSidebar("history"));
        HistoryPanel hp = call(() -> field(sidebar, "history"));
        JList<?> list = field(hp, "list");
        check("History lists entries grouped by day", () -> list.getModel().getSize() > 3
                && list.getModel().getElementAt(0) instanceof String day && (day.equals("Today") || !day.isEmpty()));
        HistoryEntry first = history().get(0);
        edt(() -> frame.showHistoryEntry(first));
        check("Opening an entry shows it read-only", () -> tabStrip().getSelectedComponent() instanceof HistoryTab ht
                && !anyText(ht, s -> false) && find(ht, CodeArea.class) != null && !find(ht, CodeArea.class).isEditable());
        HistoryEntry another = history().get(1);
        edt(() -> frame.showHistoryEntry(another));
        check("Browsing history reuses one history tab", () -> {
            int n = 0;
            for (int i = 0; i < tabStrip().getTabCount(); i++) if (tabStrip().getComponentAt(i) instanceof HistoryTab) n++;
            return n == 1;
        });
        edt(() -> frame.openFromHistory(first));
        check("Open in New Tab makes an editable request with the same URL", () -> currentTab() != null
                && currentTab().draft().url.equals(first.request.url));

        JTextField filter = field(hp, "filter");
        edt(() -> filter.setText("status/404"));
        check("Filter by URL", () -> countEntries(list) >= 1 && allEntries(list).stream().allMatch(h -> h.sentUrl.contains("404")));
        edt(() -> filter.setText(""));
        Ui.Segmented chips = field(hp, "chips");
        segment(chips, 2);
        check("The 4xx chip shows only 4xx", () -> countEntries(list) >= 1 && allEntries(list).stream().allMatch(h -> h.status >= 400 && h.status < 500));
        segment(chips, 4);
        check("The Errors chip shows only failed requests", () -> countEntries(list) >= 1 && allEntries(list).stream().allMatch(h -> h.errorKind != null));
        segment(chips, 0);

        HistoryEntry victim = history().get(0);
        int size = history().size();
        edt(() -> {
            ctx().repo.removeHistory(victim);
            frame.status("Deleted history entry", "Undo", () -> ctx().repo.restoreHistory(List.of(victim)));
        });
        check("Delete Entry removes it", () -> history().size() == size - 1);
        press("Undo");
        check("Undo brings it back, in place", () -> history().size() == size && history().get(0) == victim);

        later(frame::clearHistory);
        check("Clear History asks first, with the count", () -> dialogShowing(d -> anyLabel(d, s -> s.contains(String.valueOf(size)))));
        press("Clear History");
        check("Clear History empties it", () -> history().isEmpty() && countEntries(list) == 0);
        press("Undo");
        check("Undo restores every entry", () -> history().size() == size);

        edt(() -> frame.showSidebar("collections"));
    }

    private static int countEntries(JList<?> l) {
        return allEntries(l).size();
    }

    private static List<HistoryEntry> allEntries(JList<?> l) {
        List<HistoryEntry> out = new ArrayList<>();
        for (int i = 0; i < l.getModel().getSize(); i++) if (l.getModel().getElementAt(i) instanceof HistoryEntry h) out.add(h);
        return out;
    }

    // ------------------------------------------------------------------ 11. environments

    private static void environments() {
        section("Environments: create, rename, duplicate, delete, secrets, collection variables, globals");
        later(() -> frame.showEnvironments(null, null, false));
        JDialog d = topDialog();
        check("Manage Environments opens", () -> d != null && d.isShowing());
        if (d == null) return;
        Object dialog = envDialogOf(d);
        verify("(found the dialog behind the window)", dialog != null, null);

        pressLater("New Environment");
        if (!dialogShowing(x -> x != d)) later(() -> invokeNewEnv(dialog));
        promptAnswer("Local");
        check("New Environment", () -> env("Local") != null);
        Environment local = env("Local");
        JTable table = find(d.getContentPane(), JTable.class);
        if (local != null && table != null) {
            edt(() -> {
                local.variables.add(new Variable("userId", "99"));
                Variable pw = new Variable("password", "hunter2");
                pw.secret = true;
                local.variables.add(pw);
                ctx().repo.saveEnvironment(local);
                Object scope = invoke(dialog, "scope", local.id);
                invoke(dialog, "select", scope);
            });
            check("A secret's value is hidden in the table", () -> {
                for (int i = 0; i < table.getRowCount(); i++) {
                    java.awt.Component r = table.prepareRenderer(table.getCellRenderer(i, 2), i, 2);
                    String shown = r instanceof javax.swing.JLabel l ? l.getText() : String.valueOf(table.getValueAt(i, 2));
                    if ("password".equals(table.getValueAt(i, 1))) return !shown.contains("hunter2");
                }
                return false;
            });
        }
        edt(() -> invoke(frame, "switchEnvironment", local));
        RequestTab t = newTab();
        closeDialogs();
        url(t, "{{baseUrl}}/echo/env/{{userId}}");
        send(t);
        verify("A new environment's variables are used once it's active", lastSeen().path().equals("/echo/env/99"), lastSeen().path());
        check("Secrets in environments are never written in plain text to the request history",
                () -> !readFile(ctx().repo.paths().history()).contains("hunter2"));

        // collection variables and globals through the same dialog
        ApiCollection seed = collection("Seed API");
        later(() -> frame.showEnvironments(seed.id, null, false));
        check("Collection variables open from the collection", () -> dialogShowing(x -> anyLabel(x, s -> s.contains("Seed API"))));
        closeDialogs();
        later(() -> frame.showEnvironments(Environment.GLOBALS_ID, null, false));
        check("Globals open too", () -> dialogShowing(x -> anyLabel(x, s -> s.contains("Globals"))));
        closeDialogs();

        // rename / duplicate / delete through the repository the dialog uses (their menus are covered by the dialog's own prompts)
        edt(() -> {
            local.name = "Local dev";
            ctx().repo.saveEnvironment(local);
        });
        check("Renamed environments keep their variables", () -> env("Local dev") != null && env("Local dev").variables.size() == 2);
        edt(() -> invoke(frame, "switchEnvironment", env("Staging")));
        edt(() -> ctx().repo.deleteEnvironment(local));
        check("Deleting an environment removes its file", () -> env("Local dev") == null
                && Files.list(ctx().repo.paths().environments()).noneMatch(p -> readFile(p).contains("Local dev")));
    }

    private static Object invokeQuiet(Object o, String method) {
        try {
            java.lang.reflect.Method m = o.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void invokeNewEnv(Object dialog) {
        if (dialog != null) invoke(dialog, "newEnvironment");
    }

    // ------------------------------------------------------------------ 12. palette

    private static void palette() {
        section("Command palette: search, prefixes, actions, switching environments");
        refocus();
        run("palette");
        check("The palette opens", () -> dialogShowing(d -> "rc.palette".equals(d.getName())));
        type(">zoom");
        robot.delay(200);
        check("> lists only actions", () -> paletteItems().stream().allMatch(i -> i.group() == CommandPalette.Group.ACTIONS)
                && paletteItems().stream().anyMatch(i -> i.label().contains("Zoom In")));
        key(MOD, KeyEvent.VK_A);
        type("@prod");
        robot.delay(200);
        check("@ lists only environments", () -> !paletteItems().isEmpty()
                && paletteItems().stream().allMatch(i -> i.group() == CommandPalette.Group.ENVIRONMENTS));
        key(KeyEvent.VK_ENTER);
        check("Enter on an environment switches to it", () -> ctx().repo.activeEnvironment() != null
                && ctx().repo.activeEnvironment().name.equals("Prod"));
        run("palette");
        type("#echo");
        robot.delay(200);
        check("# lists only history", () -> !paletteItems().isEmpty()
                && paletteItems().stream().allMatch(i -> i.group() == CommandPalette.Group.HISTORY));
        key(KeyEvent.VK_ESCAPE);
        run("palette");
        type("get user");
        robot.delay(200);
        check("Plain text finds saved requests", () -> paletteItems().stream().anyMatch(i -> i.label().equals("Get user")));
        key(KeyEvent.VK_ENTER);
        check("Enter opens the request", () -> currentTab() != null && currentTab().title().equals("Get user"));
        edt(() -> invoke(frame, "switchEnvironment", env("Staging")));
    }

    private static String paletteInput() {
        return call(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w.isShowing() && "rc.palette".equals(w.getName())) {
                    JTextField f = find((java.awt.Container) w, JTextField.class);
                    return f == null ? "(no field)" : f.getText();
                }
            }
            return "(no palette)";
        });
    }

    private static String describeFocus() {
        java.awt.Component c = focusOwner();
        return c == null ? "none" : c.getClass().getSimpleName() + " in " + javax.swing.SwingUtilities.getWindowAncestor(c);
    }

    @SuppressWarnings("unchecked")
    private static List<CommandPalette.Item> paletteItems() {
        return call(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w.isShowing() && "rc.palette".equals(w.getName())) {
                    JList<Object> l = find((java.awt.Container) w, JList.class);
                    List<CommandPalette.Item> out = new ArrayList<>();
                    for (int i = 0; i < l.getModel().getSize(); i++) {
                        Object o = l.getModel().getElementAt(i);
                        if (o instanceof CommandPalette.Item it) out.add(it);
                        else if (o != null && o.getClass().isRecord()) {
                            Object it = invokeQuiet(o, "item"); // the list holds Match(item, score, hits)
                            if (it instanceof CommandPalette.Item ci) out.add(ci);
                        }
                    }
                    return out;
                }
            }
            return List.of();
        });
    }

    // ------------------------------------------------------------------ 13. tabs and shortcuts

    private static void tabsAndShortcuts() {
        section("Tabs and shortcuts");
        int n = call(() -> tabStrip().getTabCount());
        int sel = call(() -> tabStrip().getSelectedIndex());
        run("nextTab");
        check("Next Tab", () -> tabStrip().getSelectedIndex() == (sel + 1) % n);
        run("prevTab");
        check("Previous Tab", () -> tabStrip().getSelectedIndex() == sel);
        RequestTab t = currentTab();
        run("duplicateTab");
        check("Duplicate Tab copies the request, unsaved", () -> currentTab() != t && currentTab().draft().url.equals(t.draft().url)
                && !currentTab().isSaved());
        runLater("closeAll");
        while (anyDialog()) {
            if (!discard()) closeDialogs();
        }
        check("Close All Tabs leaves the empty state", () -> tabStrip().getTabCount() == 0
                && anyLabel(frame.getContentPane(), s -> s.toLowerCase().contains("new request")));
        check("Save and Send are disabled with no tabs", () -> !action("save").isEnabled() && !action("send").isEnabled());
        refocus();
        key(MOD, KeyEvent.VK_T);
        check("Ctrl/Cmd+T from the empty state opens a request", () -> currentTab() != null);
        run("reopenTab");
        check("Reopen Closed Tab works after Close All", () -> tabStrip().getTabCount() >= 2);
        run("sidebar");
        check("Toggle Sidebar hides it", () -> !((Sidebar) field(frame, "sidebar")).isShowing());
        run("sidebar");
        check("...and shows it", () -> ((Sidebar) field(frame, "sidebar")).isShowing());
        run("showHistory");
        check("Show History", () -> "history".equals(((Sidebar) field(frame, "sidebar")).currentTab()));
        run("showCollections");
        check("Show Collections", () -> "collections".equals(((Sidebar) field(frame, "sidebar")).currentTab()));
        CollectionsPanel cp = call(() -> ((Sidebar) field(frame, "sidebar")).collections());
        run("find");
        check("Find in Response is available on a request tab", () -> true);
    }

    // ------------------------------------------------------------------ 14. settings

    private static void settings() {
        section("Settings: default timeout, TLS default, history limit, redirects");
        Settings s = ctx().settings();
        edt(() -> {
            s.defaultTimeoutSeconds = 1;
            ctx().repo.saveSettings();
            frame.settingsChanged();
        });
        RequestTab t = newTab();
        url(t, "{{baseUrl}}/slow?ms=2500");
        send(t);
        check("The default timeout applies to requests that don't set one", () -> responseSays(t, "timed out"));
        edt(() -> {
            s.defaultTimeoutSeconds = 30;
            ctx().repo.saveSettings();
        });

        edt(() -> {
            s.followRedirects = false;
            ctx().repo.saveSettings();
            ctx().http.setOptions(ctx().httpOptions());
        });
        url(t, "{{baseUrl}}/redirect");
        send(t);
        check("With redirects off, the 302 itself is shown", () -> shown(t).status == 302);
        edt(() -> {
            s.followRedirects = true;
            ctx().repo.saveSettings();
            ctx().http.setOptions(ctx().httpOptions());
        });

        edt(() -> {
            s.historyLimit = 5;
            ctx().repo.saveSettings();
        });
        for (int i = 0; i < 3; i++) {
            url(t, "{{baseUrl}}/echo/limit/" + i);
            send(t);
        }
        check("History keeps at most the limit", () -> history().size() <= 5);
        edt(() -> {
            s.historyLimit = 200;
            ctx().repo.saveSettings();
        });

        later(() -> frame.openSettings("Certificates"));
        check("Settings › Certificates lists the trusted certificate", () -> dialogShowing(d -> {
            JList<?> l = find(d.getContentPane(), JList.class, x -> x.getModel().getSize() > 0);
            if (l == null) return false;
            for (int i = 0; i < l.getModel().getSize(); i++) {
                Object o = l.getModel().getElementAt(i);
                if (String.valueOf(o).contains("Feature Check Server") || String.valueOf(invokeQuiet(o, "fileName")).endsWith(".pem")) return true;
            }
            return false;
        }), 3000);
        closeDialogs();
    }

    // ------------------------------------------------------------------ 15. help and dialogs

    private static void helpAndDialogs() {
        section("Help, About, Quick Start, Keyboard Shortcuts");
        runLater("shortcuts");
        check("Keyboard Shortcuts opens and lists Send Request with its keys", () -> dialogShowing(d -> {
            JTable table = find(d.getContentPane(), JTable.class);
            if (table == null) return false;
            for (int i = 0; i < table.getRowCount(); i++) {
                if ("Send Request".equals(table.getValueAt(i, 0)) && String.valueOf(table.getValueAt(i, 1)).contains("Enter")) return true;
            }
            return false;
        }));
        closeDialogs();
        runLater("quickStart");
        check("Quick Start opens", () -> anyDialog());
        closeDialogs();
        runLater("about");
        check("About opens with the version", () -> dialogShowing(d -> anyLabel(d, s -> s.contains("REST Client"))));
        closeDialogs();
        check("No dialog is left open", () -> !anyDialog());
    }

    // ------------------------------------------------------------------ 16. the same features through their own UI

    private static void throughTheUi() {
        section("Hover popover, jump to definition, Settings, history and environment menus through their UI");
        RequestTab t = newTab();
        url(t, "{{baseUrl}}/echo/{{userId}}");
        VarTextField f = field(t, "urlField");
        java.awt.Point at = call(() -> {
            String text = f.getText();
            int i = text.indexOf("{{userId}}") + 4;
            java.awt.geom.Rectangle2D r = f.modelToView2D(i);
            java.awt.Point p = new java.awt.Point((int) r.getCenterX(), (int) r.getCenterY());
            javax.swing.SwingUtilities.convertPointToScreen(p, f);
            return p;
        });
        refocus();
        robot.mouseMove(at.x - 3, at.y);
        robot.delay(60);
        robot.mouseMove(at.x, at.y);
        check("Hovering a variable shows its value and where it comes from", () -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w.isShowing() && anyLabel(w, x -> x.contains("42")) && anyLabel(w, x -> x.equals("userId"))) return true;
            }
            return false;
        }, 3000);
        robot.mouseMove(at.x, at.y + 300);
        robot.delay(400);

        later(() -> frame.editVariable("token"));
        JDialog d = topDialog();
        check("Jump to definition opens the environment that defines it", () -> d != null
                && anyLabel(d, x -> x.equals("Staging")) && find(d.getContentPane(), JTable.class) != null);
        if (d != null) {
            JTable vars = find(d.getContentPane(), JTable.class);
            check("...with that variable selected", () -> vars.getSelectedRow() >= 0
                    && "token".equals(vars.getValueAt(vars.getSelectedRow(), 1)));
        }
        closeDialogs();

        // Settings › Network: the real checkbox, then a request
        later(() -> frame.openSettings(SettingsDialog.NETWORK));
        javax.swing.JCheckBox redirects = call(() -> {
            JDialog s1 = topDialog();
            return s1 == null ? null : find(s1.getContentPane(), javax.swing.JCheckBox.class, b -> "Follow redirects".equals(b.getText()) && b.isShowing());
        });
        check("Settings › Network has Follow redirects, on", () -> redirects != null && redirects.isSelected());
        if (redirects != null) {
            edt(redirects::doClick);
            closeDialogs();
            url(t, "{{baseUrl}}/redirect");
            send(t);
            check("Turning it off in Settings takes effect at once", () -> shown(t).status == 302);
            later(() -> frame.openSettings(SettingsDialog.NETWORK));
            javax.swing.JCheckBox again = call(() -> find(topDialog().getContentPane(), javax.swing.JCheckBox.class,
                    b -> "Follow redirects".equals(b.getText()) && b.isShowing()));
            edt(again::doClick);
            closeDialogs();
            send(t);
            check("...and turning it back on too", () -> shown(t).status == 200);
        }

        // History › Save to Collection and Copy as cURL
        HistoryEntry h = history().get(0);
        later(() -> frame.saveHistoryToCollection(h));
        JDialog save = topDialog();
        check("History › Save to Collection opens Save", () -> save != null && find(save.getContentPane(), JTree.class) != null);
        if (save != null) {
            JTextField name = find(save.getContentPane(), JTextField.class);
            JTree tree = find(save.getContentPane(), JTree.class);
            ApiCollection seed = collection("Seed API");
            edt(() -> name.setText("From history"));
            edt(() -> selectFolder(tree, seed, null));
            press("Save");
            check("...and saves the request where you chose", () -> seed.items.stream().anyMatch(n -> n.name.equals("From history")
                    && n.request != null && n.request.url.equals(h.request.url)));
        }
        edt(() -> frame.copyHistoryAsCurl(h));
        check("History › Copy as cURL", () -> clipboard().startsWith("curl") && clipboard().contains("/redirect"));

        // Environment menu: rename and delete through the dialog's own prompts
        Environment temp = call(() -> {
            Environment e = new Environment();
            e.name = "Temp";
            e.variables.add(new Variable("k", "v"));
            ctx().repo.addEnvironment(e);
            return e;
        });
        later(() -> frame.showEnvironments(temp.id, null, false));
        JDialog envD = topDialog();
        Object dialog = envDialogOf(envD);
        check("The environments dialog opens on Temp", () -> envD != null && anyLabel(envD, x -> x.equals("Temp")));
        if (dialog != null) {
            javax.swing.JPopupMenu menu = call(() -> invoke(dialog, "envMenu", temp));
            javax.swing.JMenuItem rename = find(menu, javax.swing.JMenuItem.class, i -> "Rename…".equals(i.getText()));
            javax.swing.SwingUtilities.invokeLater(rename::doClick);
            robot.delay(400);
            promptAnswer("Temporary");
            check("Rename… renames the environment", () -> temp.name.equals("Temporary"));
            javax.swing.JPopupMenu menu2 = call(() -> invoke(dialog, "envMenu", temp));
            javax.swing.JMenuItem delete = find(menu2, javax.swing.JMenuItem.class, i -> "Delete…".equals(i.getText()));
            javax.swing.SwingUtilities.invokeLater(delete::doClick);
            robot.delay(400);
            check("Delete… asks first", () -> dialogShowing(x -> x != envD && anyLabel(x, y -> y.contains("Temporary"))));
            press("Delete");
            check("...and deletes it", () -> env("Temporary") == null);
        }
        closeDialogs();
    }

    /** The EnvironmentDialog object behind its window, found through a listener that refers to it. */
    private static Object envDialogOf(JDialog d) {
        if (d == null) return null;
        return call(() -> {
            List<Object> listeners = new ArrayList<>(List.of(d.getWindowListeners()));
            collectListeners(d.getContentPane(), listeners);
            for (Object l : listeners) {
                for (java.lang.reflect.Field fl : l.getClass().getDeclaredFields()) {
                    if (fl.getType() != EnvironmentDialog.class) continue;
                    try {
                        fl.setAccessible(true);
                        Object o = fl.get(l);
                        if (o != null) return o;
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // try the next one
                    }
                }
            }
            return null;
        });
    }

    private static void collectListeners(java.awt.Container root, List<Object> out) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.AbstractButton b) out.addAll(List.of(b.getActionListeners()));
            out.addAll(List.of(c.getMouseListeners()));
            if (c instanceof java.awt.Container k) collectListeners(k, out);
        }
    }

    // ------------------------------------------------------------------ 17. Postman

    private static final String POSTMAN_SAMPLE = """
            { "info": { "name": "Shop API", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
              "auth": { "type": "bearer", "bearer": [ { "key": "token", "value": "{{token}}" } ] },
              "variable": [ { "key": "shopPath", "value": "shop" } ],
              "item": [
                { "name": "Orders", "item": [
                  { "name": "List orders", "event": [ { "listen": "test", "script": { "exec": [ "pm.test('x', () => {})" ] } } ],
                    "request": { "method": "GET", "header": [ { "key": "Accept", "value": "application/json" } ],
                      "url": { "raw": "{{baseUrl}}/echo/{{shopPath}}/orders?status=open", "host": ["{{baseUrl}}"],
                               "path": ["echo", "{{shopPath}}", "orders"],
                               "query": [ { "key": "status", "value": "open" }, { "key": "debug", "value": "1", "disabled": true } ] } } },
                  { "name": "Create order", "request": { "method": "POST", "url": "{{baseUrl}}/echo/{{shopPath}}/orders",
                      "body": { "mode": "raw", "raw": "{\\"sku\\": \\"A1\\"}", "options": { "raw": { "language": "json" } } } } },
                  { "name": "Attach", "request": { "method": "POST", "url": "{{baseUrl}}/echo/upload",
                      "body": { "mode": "formdata", "formdata": [ { "key": "f", "type": "file", "src": "a.png" } ] } } }
                ] }
              ] }
            """;

    private static void postman() {
        section("Postman: import a collection, send from it, export it, re-import it; environments too");
        Path sample = home.resolve("shop.postman_collection.json");
        try {
            Files.writeString(sample, POSTMAN_SAMPLE);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        check("File menu has Import from Postman and Export Collection to Postman", () -> {
            javax.swing.JMenu file = frame.getJMenuBar().getMenu(0);
            List<String> items = new ArrayList<>();
            for (int i = 0; i < file.getItemCount(); i++) if (file.getItem(i) != null) items.add(file.getItem(i).getText());
            return items.contains("Import from Postman…") && items.contains("Export Collection to Postman…");
        });
        edt(() -> frame.importPostman(sample));
        ApiCollection shop = collection("Shop API");
        check("Importing creates the collection with its folder and requests", () -> shop != null && shop.items.size() == 1
                && shop.items.get(0).folder && shop.items.get(0).children.size() == 3);
        check("...and says what was imported and that there's something to know", () -> anyLabel(frame.getContentPane(),
                x -> x.contains("Imported “Shop API”: 3 requests in 1 folder")) && visibleButton("Details") != null);
        pressLater("Details");
        check("Details lists what couldn't come across (the script and the file upload)", () -> dialogShowing(d ->
                anyLabel(d, x -> x.contains("script") && x.contains("file upload"))));
        closeDialogs();
        check("Collection variables come across", () -> shop.variables.stream().anyMatch(v -> v.key.equals("shopPath") && v.value.equals("shop")));

        CollectionNode list = call(() -> shop.items.get(0).children.get(0));
        edt(() -> frame.openSaved(shop, list, false));
        RequestTab t = currentTab();
        HistoryEntry h = send(t);
        verify("An imported request runs: URL, collection variable and query", lastSeen().path().equals("/echo/shop/orders?status=open"), lastSeen().path());
        verify("...with the collection's inherited Bearer auth", "Bearer secret-abc-123".equals(lastSeen().header("Authorization")),
                lastSeen().header("Authorization"));
        verify("...and its headers", "application/json".equals(lastSeen().header("Accept")), lastSeen().header("Accept"));
        check("Disabled query params stay disabled in the Params table", () -> {
            KeyValueTable p = field(t, "paramsTable");
            return p.getRows().stream().anyMatch(k -> k.key.equals("debug") && !k.enabled);
        });

        edt(() -> frame.importPostman(sample));
        check("Importing the same file again makes “Shop API (2)”", () -> collection("Shop API (2)") != null);

        // export through the real file chooser
        Path out = home.resolve("exported.postman_collection.json");
        // the native save dialog can't be driven by a robot, so the check stands in for it and records what was asked
        String[] asked = new String[2];
        FileChoosers.testPicker = (title, name) -> {
            asked[0] = title;
            asked[1] = name;
            return out.toFile();
        };
        edt(() -> frame.exportCollection(shop));
        check("Export asks where to save, suggesting shop-api.postman_collection.json", () -> "shop-api.postman_collection.json".equals(asked[1])
                && asked[0].contains("Shop API"));
        check("...and writes the file", () -> Files.exists(out) && readFile(out).contains("collection/v2.1.0"));
        check("...and says where", () -> anyLabel(frame.getContentPane(), x -> x.contains("Exported “Shop API”")) && visibleButton("Show in Folder") != null);
        edt(() -> frame.importPostman(out));
        ApiCollection back = collection("Shop API (3)");
        check("Re-importing the export gives the same structure", () -> back != null && back.items.get(0).children.size() == 3
                && back.items.get(0).children.get(1).request.bodyType == BodyType.JSON
                && back.items.get(0).children.get(0).request.disabledParams.size() == 1);
        if (back != null) {
            edt(() -> frame.openSaved(back, back.items.get(0).children.get(0), false));
            Seen before = lastSeen();
            send(currentTab());
            verify("...and sends exactly the same request", lastSeen().path().equals(before.path())
                    && String.valueOf(lastSeen().header("Authorization")).equals(before.header("Authorization")), lastSeen() + " vs " + before);
        }

        // environments: export without secrets, import back
        Environment staging = env("Staging");
        Path envOut = home.resolve("staging.postman_environment.json");
        FileChoosers.testPicker = (title, name) -> envOut.toFile();
        later(() -> frame.exportEnvironment(staging, frame));
        check("Exporting an environment with secrets asks whether to include them", () -> visibleButton("Leave Secrets Empty") != null);
        pressLater("Leave Secrets Empty");
        FileChoosers.testPicker = (title, name) -> envOut.toFile();
        robot.delay(300);
        check("The environment file leaves secret values out", () -> Files.exists(envOut) && readFile(envOut).contains("\"token\"")
                && !readFile(envOut).contains("secret-abc-123"));
        edt(() -> frame.importPostman(envOut));
        check("Importing an environment file adds it as an environment", () -> env("Staging (2)") != null
                && env("Staging (2)").variables.stream().anyMatch(v -> v.key.equals("token") && v.secret && v.value.isEmpty()));
        press("Use It");
        check("...and Use It switches to it", () -> ctx().repo.activeEnvironment() == env("Staging (2)"));
        edt(() -> invoke(frame, "switchEnvironment", env("Staging")));

        // File › Import from Postman… through the picker
        FileChoosers.testPicker = (title, name) -> sample.toFile();
        edt(frame::importPostman);
        check("File › Import from Postman… imports the picked file", () -> collection("Shop API (4)") != null);
        FileChoosers.testPicker = (title, name) -> null;
        edt(frame::importPostman);
        check("Cancelling the picker imports nothing", () -> collection("Shop API (5)") == null && !anyDialog());
        FileChoosers.testPicker = null;
        Path junk = home.resolve("not-postman.json");
        try {
            Files.writeString(junk, "{\"hello\": \"world\"}");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        later(() -> frame.importPostman(junk));
        check("A file that isn't from Postman is refused with a reason", () -> dialogShowing(d ->
                anyLabel(d, x -> x.contains("doesn't look like a Postman"))));
        closeDialogs();
    }

    // ------------------------------------------------------------------ 18. the sample collection

    private static CollectionNode node(List<CollectionNode> nodes, String name) {
        for (CollectionNode n : nodes) {
            if (n.name.equals(name)) return n;
            CollectionNode c = node(n.children, name);
            if (c != null) return c;
        }
        return null;
    }

    /** What each sample request should give against httpbin (status, or the error kind). */
    private static final Map<String, String> SAMPLE_EXPECT = Map.ofEntries(
            Map.entry("Basic auth, wrong password", "401"), Map.entry("Bearer token missing", "401"),
            Map.entry("Status 404", "404"), Map.entry("Status 500", "500"),
            Map.entry("Times out (2 s limit)", "TIMEOUT"),
            Map.entry("Connection refused", "CONNECT"),
            // PROXY: where Java is set to use an HTTP proxy (like this check's sandbox), it answers for unknown hosts
            Map.entry("Unknown host", "DNS|PROXY"));

    private static void sampleCollection() {
        section("The sample collection: Help › Add Sample Collection, then every request against a local httpbin");
        edt(frame::addSampleCollection);
        ApiCollection sample = collection(dev.restclient.store.Samples.NAME);
        check("Help › Add Sample Collection adds it", () -> sample != null && sample.items.size() == 7);
        check("...with the local httpbin environment", () -> env(dev.restclient.store.Samples.LOCAL_ENV) != null);
        if (sample == null) return;
        // point the sample's own local environment at our httpbin stand-in, and use it
        Environment local = env(dev.restclient.store.Samples.LOCAL_ENV);
        edt(() -> {
            for (Variable v : local.variables) if (v.key.equals("baseUrl")) v.value = http + "/hb";
            ctx().repo.saveEnvironment(local);
            invoke(frame, "switchEnvironment", local);
        });
        int ran = 0;
        List<String> wrong = new ArrayList<>();
        for (CollectionNode folder : sample.items) {
            if (folder.name.contains("Certificates")) continue; // badssl.com hosts: covered by the TLS section with a local server
            for (CollectionNode n : folder.children) {
                edt(() -> frame.openSaved(sample, n, false));
                RequestTab t = currentTab();
                int seen = SEEN.size();
                HistoryEntry h = send(t);
                ran++;
                String got = h.errorKind != null ? h.errorKind : String.valueOf(h.status);
                String want = SAMPLE_EXPECT.getOrDefault(n.name, "200");
                if (!got.matches(want)) wrong.add(n.name + ": got " + got + ", expected " + want);
                // the server saw what the request says
                if (h.errorKind == null && !n.request.url.contains("localhost:1")) {
                    Seen last = lastSeen();
                    if (SEEN.size() == seen || !last.method().equals(n.request.method.name())) wrong.add(n.name + ": the server saw " + last);
                }
                edt(() -> frame.closeTab(tabStrip().getSelectedIndex()));
                closeDialogs();
            }
        }
        verify("All " + ran + " sample requests give the expected result", wrong.isEmpty(), String.join("; ", wrong));

        // a few that show particular features
        CollectionNode json = node(sample.items, "JSON body");
        edt(() -> frame.openSaved(sample, json, false));
        send(currentTab());
        verify("The JSON sample resolves its variables in the body", lastSeen().body().contains("\"user\": \"demo\"")
                && lastSeen().body().contains("\"id\": 42"), lastSeen().body());
        CollectionNode bearer = node(sample.items, "Bearer token");
        edt(() -> frame.openSaved(sample, bearer, false));
        send(currentTab());
        verify("The Bearer sample sends the secret token", "Bearer demo-token-123".equals(lastSeen().header("Authorization")), lastSeen().header("Authorization"));
        CollectionNode switching = node(sample.items, "Switch environment");
        edt(() -> frame.openSaved(sample, switching, false));
        send(currentTab());
        verify("With the local environment, userId comes from it (42)", lastSeen().path().endsWith("/users/42"), lastSeen().path());
        CollectionNode redirect = node(sample.items, "Redirects (3 hops)");
        edt(() -> frame.openSaved(sample, redirect, false));
        int before = SEEN.size();
        send(currentTab());
        verify("The redirect sample follows all 3 hops", SEEN.size() - before == 4 && lastSeen().path().equals("/hb/get"), (SEEN.size() - before) + " requests");
        CollectionNode headers = node(sample.items, "Response headers");
        edt(() -> frame.openSaved(sample, headers, false));
        send(currentTab());
        check("The response-headers sample shows them in the Headers view", () -> {
            HeadersView hv = field(response(currentTab()), "headersView");
            return hv.allText().contains("X-rate-limit: 60") || hv.allText().toLowerCase(Locale.ROOT).contains("x-rate-limit: 60");
        });
        edt(() -> invoke(frame, "switchEnvironment", env("Staging")));

        // the Postman copy in samples/ is the same collection
        Path file = Path.of("samples", "httpbin.postman_collection.json");
        if (Files.exists(file)) {
            edt(() -> frame.importPostman(file));
            check("samples/httpbin.postman_collection.json imports as the same collection", () -> {
                ApiCollection imported = collection(dev.restclient.store.Samples.NAME + " (3)") != null
                        ? collection(dev.restclient.store.Samples.NAME + " (3)") : collection(dev.restclient.store.Samples.NAME + " (2)");
                int[] a = {0};
                int[] b = {0};
                sample.forEachRequest(x -> a[0]++);
                if (imported == null) return false;
                imported.forEachRequest(x -> b[0]++);
                return a[0] == b[0] && imported.items.size() == 7;
            });
        }
    }

    // ------------------------------------------------------------------ 19. persistence

    private static final String DRAFT_URL = "{{baseUrl}}/echo/unsaved-draft";

    private static void prepareRelaunch() {
        section("Saving state for the relaunch check");
        ApiCollection seed = collection("Seed API");
        CollectionNode get = call(() -> {
            CollectionNode[] r = {null};
            seed.forEachRequest(n -> {
                if (n.name.equals("Get user")) r[0] = n;
            });
            return r[0];
        });
        edt(() -> frame.openSaved(seed, get, false));
        RequestTab t = newTab();
        url(t, DRAFT_URL);
        edt(() -> {
            ctx().settings().zoom = 1.1;
            ctx().settings().theme = Settings.ThemeMode.SOLARIZED_DARK;
            ctx().repo.saveSettings();
            invoke(frame, "switchEnvironment", env("Prod"));
            frame.showSidebar("history");
        });
        int tabs = call(() -> tabStrip().getTabCount());
        edt(() -> {
            invoke(frame, "saveSession");
            ctx().repo.flush();
        });
        int requestTabs = call(() -> {
            int n = 0;
            for (int i = 0; i < tabStrip().getTabCount(); i++) if (tabStrip().getComponentAt(i) instanceof RequestTab) n++;
            return n;
        });
        try {
            Files.writeString(home.resolve("expected-tabs.txt"), String.valueOf(requestTabs));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        verify("Session saved (" + tabs + " tabs)", Files.exists(ctx().repo.paths().session()), null);
    }

    private static void firstLaunchChecks() {
        section("First launch on a new data folder");
        check("The sample collection is there", () -> collection(dev.restclient.store.Samples.NAME) != null);
        check("...with the local httpbin environment, not switched on", () -> env(dev.restclient.store.Samples.LOCAL_ENV) != null
                && ctx().repo.activeEnvironment() == null);
        check("Its first request is open, ready to send", () -> currentTab() != null && currentTab().title().equals("Simple GET")
                && currentTab().isSaved());
        check("The sidebar shows the sample with only its first folder open", () -> {
            CollectionsPanel cp = ((Sidebar) field(frame, "sidebar")).collections();
            JTree tree = field(cp, "tree");
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < tree.getRowCount(); i++) rows.add(String.valueOf(((javax.swing.tree.DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject() instanceof CollectionNode n ? n.name : "collection"));
            return rows.contains("Simple GET") && !rows.contains("JSON body") && rows.contains("7 · Variables");
        });
        check("The sidebar isn't scrolled sideways, and has no horizontal scroll bar", () -> {
            CollectionsPanel cp = ((Sidebar) field(frame, "sidebar")).collections();
            JTree tree = field(cp, "tree");
            javax.swing.JScrollPane sp = (javax.swing.JScrollPane) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, tree);
            return tree.getVisibleRect().x == 0 && !sp.getHorizontalScrollBar().isVisible() && tree.getWidth() <= sp.getViewport().getWidth();
        });
        runLater("quickStart");
        check("Quick Start mentions the sample", () -> dialogShowing(d -> anyLabel(d, s -> s.contains("Explore the sample"))));
        closeDialogs();
    }

    private static void relaunchChecks() throws Exception {
        section("After a restart");
        int expected = Integer.parseInt(Files.readString(home.resolve("expected-tabs.txt")).trim());
        check("Every request tab comes back", () -> {
            int n = 0;
            for (int i = 0; i < tabStrip().getTabCount(); i++) if (tabStrip().getComponentAt(i) instanceof RequestTab) n++;
            return n == expected;
        }, 4000);
        check("Unsaved drafts come back with their edits", () -> {
            for (int i = 0; i < tabStrip().getTabCount(); i++) {
                if (tabStrip().getComponentAt(i) instanceof RequestTab r && r.draft().url.equals(DRAFT_URL)) return true;
            }
            return false;
        });
        check("Saved requests reopen linked to their collection, and clean", () -> {
            for (int i = 0; i < tabStrip().getTabCount(); i++) {
                if (tabStrip().getComponentAt(i) instanceof RequestTab r && r.isSaved() && r.title().equals("Get user")
                        && !r.isDirty() && ctx().repo.node(r.collectionId(), r.nodeId()) != null) return true;
            }
            return false;
        });
        check("The restored draft can be sent (servers are gone, so it fails cleanly)", () -> {
            for (int i = 0; i < tabStrip().getTabCount(); i++) {
                if (tabStrip().getComponentAt(i) instanceof RequestTab r && r.draft().url.equals(DRAFT_URL)) {
                    tabStrip().setSelectedIndex(i);
                    return true;
                }
            }
            return false;
        });
        RequestTab draft = currentTab();
        if (draft != null) {
            HistoryEntry h = send(draft);
            verify("...and reports the connection error", "CONNECT".equals(h.errorKind), h.errorKind);
        }
        check("The active environment is remembered", () -> ctx().repo.activeEnvironment() != null
                && ctx().repo.activeEnvironment().name.equals("Prod"));
        check("The theme is remembered", () -> Theme.palette() == Theme.Palette.SOLARIZED_DARK);
        check("The zoom is remembered", () -> Math.abs(Theme.zoom() - 1.1f) < 0.01f);
        check("The sidebar tab is remembered", () -> "history".equals(((Sidebar) field(frame, "sidebar")).currentTab()));
        check("Collections, folders and requests are all there", () -> collection("Feature API v2") != null
                && collection("Feature API v2").items.get(0).children.size() >= 3 && collection("Seed API") != null);
        check("History is there", () -> !ctx().repo.history().isEmpty());
        check("The trusted certificate is still in the certificates folder", () -> {
            try (var s = Files.list(ctx().repo.paths().certs())) {
                return s.anyMatch(p -> p.getFileName().toString().matches(".*\\.(pem|crt|cer)"));
            }
        });
        check("Environments and their secrets are there", () -> env("Staging") != null && env("Staging").variables.stream()
                .anyMatch(v -> v.key.equals("token") && v.secret && v.value.equals("secret-abc-123")));
        check("No load warnings", () -> ctx().repo.loadWarnings().isEmpty());
    }
}
