package dev.restclient.http;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Builds the SSL contexts used for requests.
 *
 * <h2>Verified (default)</h2>
 * Trust = the JDK's default trust store + every certificate found in {@code ~/.restclient/certs}
 * ({@code .pem .crt .cer .der} files and the trusted entries of {@code .p12 .pfx .jks} stores).
 * Keystores that contain private keys supply client certificates for mTLS.
 * A file that fails to load is reported and skipped; the others still load.
 *
 * <h2>Skipped</h2>
 * Uses a no-op {@link X509ExtendedTrustManager}. In JSSE, hostname (endpoint identification)
 * checks are performed <em>by the trust manager</em> when it is an {@code X509ExtendedTrustManager};
 * a plain {@code X509TrustManager} would get wrapped and still have the hostname checked. So this one
 * object turns off both chain validation and hostname verification, per request, without touching the
 * JVM-wide {@code jdk.internal.httpclient.disableHostnameVerification} property (which is read once and
 * would also weaken verified requests).
 */
public final class TlsManager {
    private static final char[] INTERNAL_KEY_PASSWORD = "restclient".toCharArray();
    private static final String[] DEFAULT_PASSWORDS = {"", "changeit"};

    private final Path certsDir;
    private final Supplier<Map<String, String>> passwords;
    private final Map<String, X509Certificate[]> lastChains = new ConcurrentHashMap<>();

    private volatile SSLContext secureContext;
    private volatile SSLContext insecureContext;
    private volatile List<CertFileStatus> lastStatus = List.of();
    private volatile int addedTrusted;
    private volatile int systemTrusted;

    public TlsManager(Path certsDir, Supplier<Map<String, String>> passwords) {
        this.certsDir = certsDir;
        this.passwords = passwords;
    }

    public Path certsDir() {
        return certsDir;
    }

    /** (Re)loads the certs folder and rebuilds both contexts. Never throws for a bad file. */
    public synchronized void reload() throws GeneralSecurityException {
        X509TrustManager systemTm = defaultTrustManager();
        X509Certificate[] system = systemTm.getAcceptedIssuers();

        KeyStore trust = emptyStore();
        int i = 0;
        for (X509Certificate c : system) trust.setCertificateEntry("system-" + (i++), c);
        systemTrusted = system.length;

        KeyStore clientKeys = emptyStore();
        int clientCount = 0;
        int added = 0;
        List<CertFileStatus> status = new ArrayList<>();
        for (Path file : listFiles()) {
            String name = file.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            try {
                if (lower.endsWith(".pem") || lower.endsWith(".crt") || lower.endsWith(".cer") || lower.endsWith(".der")) {
                    List<X509Certificate> certs = readCertificates(file);
                    int n = 0;
                    for (X509Certificate c : certs) trust.setCertificateEntry(name + "#" + (n++), c);
                    added += n;
                    status.add(new CertFileStatus(name, CertFileStatus.Status.LOADED, describe(certs), n, 0));
                } else if (lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".jks") || lower.endsWith(".keystore")) {
                    KeyStoreLoad load = loadKeyStore(file, name);
                    int trusted = 0;
                    int keys = 0;
                    for (String alias : java.util.Collections.list(load.store.aliases())) {
                        if (load.store.isKeyEntry(alias)) {
                            Key key = load.store.getKey(alias, load.password);
                            Certificate[] chain = load.store.getCertificateChain(alias);
                            if (key instanceof PrivateKey && chain != null) {
                                clientKeys.setKeyEntry(name + "#" + alias, key, INTERNAL_KEY_PASSWORD, chain);
                                keys++;
                            }
                        } else if (load.store.isCertificateEntry(alias)) {
                            trust.setCertificateEntry(name + "#" + alias, load.store.getCertificate(alias));
                            trusted++;
                        }
                    }
                    added += trusted;
                    clientCount += keys;
                    String detail = keys > 0
                            ? "Client certificate (mTLS)" + (trusted > 0 ? " · " + trusted + " trusted" : "")
                            : "Truststore · " + trusted + " certificate" + (trusted == 1 ? "" : "s");
                    status.add(new CertFileStatus(name, CertFileStatus.Status.LOADED, detail, trusted, keys));
                } else if (!name.startsWith(".")) {
                    status.add(new CertFileStatus(name, CertFileStatus.Status.IGNORED,
                            "Not a certificate file type (.pem .crt .cer .der .p12 .pfx .jks)", 0, 0));
                }
            } catch (Exception e) {
                status.add(new CertFileStatus(name, CertFileStatus.Status.FAILED, friendly(e), 0, 0));
            }
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        X509ExtendedTrustManager merged = firstExtended(tmf.getTrustManagers());
        KeyManager[] kms = null;
        if (clientCount > 0) {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(clientKeys, INTERNAL_KEY_PASSWORD);
            kms = kmf.getKeyManagers();
        }

        SSLContext secure = SSLContext.getInstance("TLS");
        secure.init(kms, new TrustManager[]{new CapturingTrustManager(merged, lastChains)}, null);
        SSLContext insecure = SSLContext.getInstance("TLS");
        insecure.init(kms, new TrustManager[]{new TrustAllManager(lastChains)}, null);

        this.secureContext = secure;
        this.insecureContext = insecure;
        this.lastStatus = List.copyOf(status);
        this.addedTrusted = added;
    }

    public SSLContext secureContext() {
        return secureContext;
    }

    public SSLContext insecureContext() {
        return insecureContext;
    }

    public List<CertFileStatus> status() {
        return lastStatus;
    }

    /** Certificates added on top of the system store. */
    public int addedTrustedCount() {
        return addedTrusted;
    }

    public int systemTrustedCount() {
        return systemTrusted;
    }

    public long failedCount() {
        return lastStatus.stream().filter(s -> s.status() == CertFileStatus.Status.FAILED).count();
    }

    /** The chain the given host presented during its most recent handshake, if any. */
    public X509Certificate[] lastChain(String host) {
        return host == null ? null : lastChains.get(host.toLowerCase(Locale.ROOT));
    }

    // ---- saving a trusted certificate ------------------------------------------------------

    /** Writes the certificate as PEM. Fails rather than overwriting an existing file. */
    public Path saveCertificate(X509Certificate cert, Path target) throws IOException, CertificateException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, toPem(cert), StandardCharsets.US_ASCII,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        return target;
    }

    /** {@code dir/name.pem}, or {@code name-2.pem}, {@code name-3.pem}… if taken. */
    public static Path uniquePath(Path dir, String baseName) {
        String safe = baseName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (safe.isEmpty()) safe = "certificate";
        Path p = dir.resolve(safe + ".pem");
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(safe + "-" + i + ".pem");
        return p;
    }

    public static String toPem(X509Certificate cert) throws CertificateException {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    public static String sha256(X509Certificate cert) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(d);
        } catch (Exception e) {
            return "";
        }
    }

    /** Common name from a certificate's subject, falling back to the full DN. */
    public static String commonName(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) return p.substring(3);
        }
        return dn;
    }

    public static boolean isSelfSigned(X509Certificate c) {
        return c.getSubjectX500Principal().equals(c.getIssuerX500Principal());
    }

    // ---- loading helpers -------------------------------------------------------------------

    private List<Path> listFiles() {
        List<Path> out = new ArrayList<>();
        if (certsDir == null || !Files.isDirectory(certsDir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(certsDir)) {
            for (Path p : ds) if (Files.isRegularFile(p)) out.add(p);
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    static List<X509Certificate> readCertificates(Path file) throws IOException, CertificateException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        List<X509Certificate> out = new ArrayList<>();
        if (text.contains("-----BEGIN")) {
            // Only take CERTIFICATE blocks so a PEM bundle with other blocks still loads.
            int from = 0;
            while (true) {
                int b = text.indexOf("-----BEGIN CERTIFICATE-----", from);
                if (b < 0) break;
                int e = text.indexOf("-----END CERTIFICATE-----", b);
                if (e < 0) throw new CertificateException("Unterminated PEM certificate block");
                e += "-----END CERTIFICATE-----".length();
                try (InputStream in = new java.io.ByteArrayInputStream(text.substring(b, e).getBytes(StandardCharsets.ISO_8859_1))) {
                    out.add((X509Certificate) cf.generateCertificate(in));
                }
                from = e;
            }
        } else {
            try (InputStream in = Files.newInputStream(file)) {
                Collection<? extends Certificate> certs = cf.generateCertificates(in);
                for (Certificate c : certs) out.add((X509Certificate) c);
            }
        }
        if (out.isEmpty()) throw new CertificateException("Not a valid X.509 certificate");
        return out;
    }

    private record KeyStoreLoad(KeyStore store, char[] password) {
    }

    private KeyStoreLoad loadKeyStore(Path file, String name) throws GeneralSecurityException, IOException {
        List<String> candidates = new ArrayList<>();
        Map<String, String> pw = passwords == null ? null : passwords.get();
        if (pw != null && pw.get(name) != null) candidates.add(pw.get(name));
        for (String d : DEFAULT_PASSWORDS) if (!candidates.contains(d)) candidates.add(d);

        String lower = name.toLowerCase(Locale.ROOT);
        String type = lower.endsWith(".jks") || lower.endsWith(".keystore") ? "JKS" : "PKCS12";
        Exception last = null;
        for (String candidate : candidates) {
            char[] p = candidate.toCharArray();
            try (InputStream in = Files.newInputStream(file)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, p);
                return new KeyStoreLoad(ks, p);
            } catch (IOException | GeneralSecurityException e) {
                last = e;
            }
        }
        throw new GeneralSecurityException(
                pw != null && pw.get(name) != null ? "Wrong password, or not a " + type + " keystore"
                        : "Needs a password — set it in Settings › Certificates", last);
    }

    private static String describe(List<X509Certificate> certs) {
        X509Certificate c = certs.get(0);
        String kind = isSelfSigned(c) || c.getBasicConstraints() >= 0 ? "CA certificate" : "Certificate";
        String expires = new java.text.SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(c.getNotAfter());
        String more = certs.size() > 1 ? " · +" + (certs.size() - 1) + " more" : "";
        return commonName(c) + " · " + kind + " · expires " + expires + more;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        if (e instanceof CertificateException) return "Skipped — not a valid X.509 certificate";
        if (m != null && m.contains("password")) return "Skipped — " + m;
        return "Skipped — " + (m == null ? e.getClass().getSimpleName() : m);
    }

    private static KeyStore emptyStore() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            return ks;
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager x) return x;
        throw new GeneralSecurityException("No default X509TrustManager");
    }

    private static X509ExtendedTrustManager firstExtended(TrustManager[] tms) throws GeneralSecurityException {
        for (TrustManager tm : tms) if (tm instanceof X509ExtendedTrustManager x) return x;
        throw new GeneralSecurityException("No X509ExtendedTrustManager available");
    }

    // ---- trust managers --------------------------------------------------------------------

    private static String hostOf(SSLEngine engine) {
        return engine == null || engine.getPeerHost() == null ? null : engine.getPeerHost().toLowerCase(Locale.ROOT);
    }

    private static String hostOf(Socket socket) {
        if (socket == null || socket.getInetAddress() == null) return null;
        return socket.getInetAddress().getHostName().toLowerCase(Locale.ROOT);
    }

    /** Delegates to the merged PKIX trust manager (hostname checks included), recording the chain first. */
    static final class CapturingTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final Map<String, X509Certificate[]> chains;

        CapturingTrustManager(X509ExtendedTrustManager delegate, Map<String, X509Certificate[]> chains) {
            this.delegate = delegate;
            this.chains = chains;
        }

        private void record(String host, X509Certificate[] chain) {
            if (host != null && chain != null) chains.put(host, chain.clone());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            record(hostOf(engine), chain);
            delegate.checkServerTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            record(hostOf(socket), chain);
            delegate.checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    /** Accepts any chain for any host. Extending X509ExtendedTrustManager is what disables hostname checks. */
    static final class TrustAllManager extends X509ExtendedTrustManager {
        private final Map<String, X509Certificate[]> chains;

        TrustAllManager(Map<String, X509Certificate[]> chains) {
            this.chains = chains;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            String h = hostOf(engine);
            if (h != null && chain != null) chains.put(h, chain.clone());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
