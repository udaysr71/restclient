package dev.restclient.ui;

import dev.restclient.http.HttpService;
import dev.restclient.http.TlsManager;
import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.Environment;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Settings;
import dev.restclient.store.Repository;
import dev.restclient.vars.VariableResolver;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared services for the UI. */
public final class AppContext {
    private static final Logger LOG = Logger.getLogger(AppContext.class.getName());

    public final Repository repo;
    public final TlsManager tls;
    public final HttpService http;
    private MainFrame frame;

    public AppContext(Repository repo) {
        this.repo = repo;
        this.tls = new TlsManager(repo.paths().certs(), () -> repo.settings().keystorePasswords);
        try {
            tls.reload();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "TLS setup failed", e);
            throw new IllegalStateException("Couldn't set up TLS: " + e.getMessage(), e);
        }
        this.http = new HttpService(tls, httpOptions());
    }

    void setFrame(MainFrame frame) {
        this.frame = frame;
    }

    public MainFrame frame() {
        return frame;
    }

    public Settings settings() {
        return repo.settings();
    }

    public HttpService.Options httpOptions() {
        Settings s = repo.settings();
        return new HttpService.Options(s.connectTimeoutSeconds, s.followRedirects, s.useSystemProxy);
    }

    /** Re-reads the certs folder and rebuilds HTTP clients. Returns an error message or null. */
    public String reloadTls() {
        try {
            tls.reload();
            http.rebuild();
            if (frame != null) frame.refreshStatus();
            return null;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "TLS reload failed", e);
            return e.getMessage();
        }
    }

    public Environment activeEnvironment() {
        return repo.activeEnvironment();
    }

    /** Variables for a request, given the collection it belongs to (may be null). */
    public VariableResolver resolverFor(String collectionId) {
        ApiCollection c = repo.collection(collectionId);
        return new VariableResolver(repo.activeEnvironment(), c, repo.globals());
    }

    public boolean effectiveInsecure(RequestDef r) {
        return r.insecure != null ? r.insecure : repo.settings().insecureByDefault;
    }

    public Duration timeoutFor(RequestDef r) {
        int s = r.timeoutSeconds > 0 ? r.timeoutSeconds : repo.settings().defaultTimeoutSeconds;
        return Duration.ofSeconds(Math.max(1, s));
    }

    /** How many saved requests reference {@code {{name}}}. */
    public int usageCount(String name) {
        int[] n = {0};
        for (ApiCollection c : repo.collections()) {
            c.forEachRequest(node -> {
                if (uses(node.request, name)) n[0]++;
            });
        }
        return n[0];
    }

    private static boolean uses(RequestDef r, String name) {
        if (VariableResolver.references(r.url, name) || VariableResolver.references(r.body, name)) return true;
        for (KeyValue kv : r.headers) if (VariableResolver.references(kv.key, name) || VariableResolver.references(kv.value, name)) return true;
        for (KeyValue kv : r.form) if (VariableResolver.references(kv.key, name) || VariableResolver.references(kv.value, name)) return true;
        for (KeyValue kv : r.multipart) if (VariableResolver.references(kv.key, name) || VariableResolver.references(kv.value, name)) return true;
        if (VariableResolver.references(r.bodyFile, name)) return true;
        Auth a = r.auth;
        return a != null && (VariableResolver.references(a.username, name) || VariableResolver.references(a.password, name)
                || VariableResolver.references(a.token, name));
    }
}
