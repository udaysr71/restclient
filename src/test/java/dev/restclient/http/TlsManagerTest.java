package dev.restclient.http;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.restclient.testkit.AfterEach;
import dev.restclient.testkit.BeforeEach;
import dev.restclient.testkit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.restclient.testkit.Assertions.assertEquals;
import static dev.restclient.testkit.Assertions.assertNotNull;
import static dev.restclient.testkit.Assertions.assertTrue;

/**
 * Uses a real HTTPS server on 127.0.0.1 with a self-signed certificate whose only name is
 * {@code DNS:localhost}. That lets us check untrusted, trusted-after-adding, hostname-mismatch
 * and skip-verification behaviour end to end.
 */
class TlsManagerTest {
    private Path dir;
    private Path certs;
    private Path serverStore;
    private HttpsServer server;
    private int port;
    private final Map<String, String> passwords = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("restclient-tls");
        certs = Files.createDirectories(dir.resolve("certs"));
        serverStore = dir.resolve("server.p12");
        keytool(serverStore, "server", "CN=localhost", "changeit", "SAN=dns:localhost");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverStore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop(0);
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void keytool(Path store, String alias, String dname, String password, String ext) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", dname, "-ext", ext, "-validity", "3", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", password, "-keypass", password)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    private TlsManager tls() throws Exception {
        TlsManager t = new TlsManager(certs, () -> passwords);
        t.reload();
        return t;
    }

    private static ResponseData get(HttpService http, String url, boolean insecure) throws Exception {
        PreparedRequest p = PreparedRequest.prepare(request(url), null);
        return http.send(p, insecure, Duration.ofSeconds(10)).result().get();
    }

    private static dev.restclient.model.RequestDef request(String url) {
        dev.restclient.model.RequestDef r = new dev.restclient.model.RequestDef();
        r.url = url;
        return r;
    }

    private static HttpService http(TlsManager t) {
        return new HttpService(t, new HttpService.Options(5, true, false));
    }

    private X509Certificate serverCert() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverStore)) {
            ks.load(in, "changeit".toCharArray());
        }
        return (X509Certificate) ks.getCertificate("server");
    }

    @Test
    void untrustedThenTrustedAfterSavingCertificate() throws Exception {
        TlsManager t = tls();
        HttpService http = http(t);
        String url = "https://localhost:" + port + "/";

        ResponseData first = get(http, url, false);
        assertEquals(ResponseData.ErrorKind.TLS_UNTRUSTED, first.errorKind, String.valueOf(first.errorMessage));

        X509Certificate[] chain = t.lastChain("localhost");
        assertNotNull(chain, "chain presented by the server is captured for the Trust dialog");
        assertEquals("localhost", TlsManager.commonName(chain[0]));

        Path saved = t.saveCertificate(chain[0], TlsManager.uniquePath(certs, TlsManager.commonName(chain[0])));
        assertEquals("localhost.pem", saved.getFileName().toString());
        assertEquals("localhost-2.pem", TlsManager.uniquePath(certs, "localhost").getFileName().toString());

        t.reload();
        http.rebuild();
        ResponseData second = get(http, url, false);
        assertEquals(200, second.status, String.valueOf(second.errorMessage));
        assertEquals("ok", second.bodyText());
        assertEquals(1, t.addedTrustedCount());
        assertTrue(t.systemTrustedCount() > 0, "system trust store is merged in");
    }

    @Test
    void hostnameIsStillCheckedWhenVerifying() throws Exception {
        Files.writeString(certs.resolve("server.pem"), TlsManager.toPem(serverCert()));
        HttpService http = http(tls());
        // Trusted certificate, but its only name is "localhost", not 127.0.0.1.
        ResponseData r = get(http, "https://127.0.0.1:" + port + "/", false);
        assertEquals(ResponseData.ErrorKind.TLS_HOSTNAME, r.errorKind, String.valueOf(r.errorMessage));
    }

    @Test
    void skipVerificationIgnoresBothTrustAndHostname() throws Exception {
        HttpService http = http(tls());
        ResponseData untrustedHost = get(http, "https://localhost:" + port + "/", true);
        assertEquals(200, untrustedHost.status, String.valueOf(untrustedHost.errorMessage));
        ResponseData wrongHost = get(http, "https://127.0.0.1:" + port + "/", true);
        assertEquals(200, wrongHost.status, String.valueOf(wrongHost.errorMessage));
    }

    @Test
    void badFilesAreReportedAndTheRestStillLoad() throws Exception {
        Files.writeString(certs.resolve("good.pem"), TlsManager.toPem(serverCert()));
        Files.writeString(certs.resolve("broken.cer"), "this is not a certificate");
        Files.writeString(certs.resolve("notes.txt"), "hello");
        Files.writeString(certs.resolve(".DS_Store"), "x");

        TlsManager t = tls();
        Map<String, CertFileStatus> byName = new HashMap<>();
        for (CertFileStatus s : t.status()) byName.put(s.fileName(), s);

        assertEquals(CertFileStatus.Status.LOADED, byName.get("good.pem").status());
        assertTrue(byName.get("good.pem").detail().startsWith("localhost · CA certificate"), byName.get("good.pem").detail());
        assertEquals(CertFileStatus.Status.FAILED, byName.get("broken.cer").status());
        assertEquals(CertFileStatus.Status.IGNORED, byName.get("notes.txt").status());
        assertTrue(!byName.containsKey(".DS_Store"), "hidden files are skipped");
        assertEquals(1, t.failedCount());
        assertEquals(1, t.addedTrustedCount());
    }

    @Test
    void clientKeystoresAndPasswords() throws Exception {
        // Default password "changeit" is tried automatically.
        Files.copy(serverStore, certs.resolve("client-default.p12"));
        // A custom password needs to be configured.
        Path custom = certs.resolve("client-custom.p12");
        keytool(custom, "me", "CN=me", "s3cret!", "KU=digitalSignature");

        TlsManager t = tls();
        Map<String, CertFileStatus> byName = new HashMap<>();
        for (CertFileStatus s : t.status()) byName.put(s.fileName(), s);
        assertEquals(CertFileStatus.Status.LOADED, byName.get("client-default.p12").status());
        assertEquals(1, byName.get("client-default.p12").clientKeys());
        assertEquals(CertFileStatus.Status.FAILED, byName.get("client-custom.p12").status());
        assertTrue(byName.get("client-custom.p12").detail().contains("password"), byName.get("client-custom.p12").detail());

        passwords.put("client-custom.p12", "s3cret!");
        t.reload();
        List<CertFileStatus> again = t.status();
        assertTrue(again.stream().allMatch(s -> s.status() == CertFileStatus.Status.LOADED), again.toString());
    }

    @Test
    void pemBundleWithMultipleCertificates() throws Exception {
        String pem = TlsManager.toPem(serverCert());
        Files.writeString(certs.resolve("bundle.pem"), "# comment\n" + pem + "\n" + pem);
        TlsManager t = tls();
        assertEquals(2, t.status().get(0).trustedCerts());
    }

    @Test
    void fingerprintFormat() throws Exception {
        String fp = TlsManager.sha256(serverCert());
        assertEquals(32 * 3 - 1, fp.length());
        assertTrue(fp.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
        URI.create("https://localhost:" + port); // keep URI import meaningful
    }
}
