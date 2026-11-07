package dev.restclient.http;

import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.vars.VariableResolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A request with every variable resolved, auth turned into headers and the body encoded —
 * exactly what goes over the wire. Also produces a masked copy for history.
 */
public final class PreparedRequest {
    /** Headers HttpClient refuses to let callers set. */
    private static final Set<String> RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade");

    public final HttpMethod method;
    public final URI uri;
    public final String url;
    public final List<KeyValue> headers;
    public final String body;
    public final List<String> warnings;
    /** Variable names that were still unresolved. */
    public final Set<String> unresolved;

    private PreparedRequest(HttpMethod method, URI uri, String url, List<KeyValue> headers, String body,
                            List<String> warnings, Set<String> unresolved) {
        this.method = method;
        this.uri = uri;
        this.url = url;
        this.headers = headers;
        this.body = body;
        this.warnings = warnings;
        this.unresolved = unresolved;
    }

    public static final class InvalidRequestException extends Exception {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    public static PreparedRequest prepare(RequestDef r, VariableResolver vars) throws InvalidRequestException {
        return build(r, vars, false, true);
    }

    /** Same as {@link #prepare} but secret variables are masked and the URL is not validated. */
    public static PreparedRequest prepareMasked(RequestDef r, VariableResolver vars) {
        try {
            return build(r, vars, true, false);
        } catch (InvalidRequestException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedRequest build(RequestDef r, VariableResolver vars, boolean mask, boolean validate)
            throws InvalidRequestException {
        if (vars == null) vars = VariableResolver.empty();
        List<String> warnings = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();

        String url = resolve(vars, r.url, mask, unresolved).trim();
        if (url.isEmpty()) throw new InvalidRequestException("Enter a URL to send the request.");
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = "http://" + url;
        URI uri = null;
        if (validate) {
            try {
                uri = new URI(encodeUnsafe(url));
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https"))
                    throw new InvalidRequestException("Only http:// and https:// URLs are supported.");
                if (uri.getHost() == null || uri.getHost().isBlank())
                    throw new InvalidRequestException("The URL has no host: " + url);
            } catch (URISyntaxException e) {
                throw new InvalidRequestException("That URL isn't valid: " + e.getReason());
            }
        }

        List<KeyValue> headers = new ArrayList<>();
        for (KeyValue kv : r.headers) {
            if (!kv.enabled || kv.key == null || kv.key.isBlank()) continue;
            String key = resolve(vars, kv.key, mask, unresolved).trim();
            String value = resolve(vars, kv.value, mask, unresolved);
            if (RESTRICTED.contains(key.toLowerCase(Locale.ROOT))) {
                warnings.add("Header \"" + key + "\" is set automatically and was not sent.");
                continue;
            }
            headers.add(new KeyValue(key, value));
        }

        Auth auth = r.auth == null ? new Auth() : r.auth;
        if (auth.type == Auth.Type.BASIC && !hasHeader(headers, "Authorization")) {
            String user = resolve(vars, auth.username, mask, unresolved);
            String pass = resolve(vars, auth.password, mask, unresolved);
            String encoded = mask && (isMasked(user) || isMasked(pass)) ? VariableResolver.MASK
                    : Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            headers.add(new KeyValue("Authorization", "Basic " + encoded));
        } else if (auth.type == Auth.Type.BEARER && !hasHeader(headers, "Authorization")) {
            headers.add(new KeyValue("Authorization", "Bearer " + resolve(vars, auth.token, mask, unresolved)));
        }

        String body = null;
        BodyType type = r.bodyType == null ? BodyType.NONE : r.bodyType;
        if (type.isRaw()) {
            body = resolve(vars, r.body, mask, unresolved);
        } else if (type == BodyType.FORM) {
            StringBuilder sb = new StringBuilder();
            for (KeyValue kv : r.form) {
                if (!kv.enabled || kv.key == null || kv.key.isBlank()) continue;
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(resolve(vars, kv.key, mask, unresolved), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(resolve(vars, kv.value, mask, unresolved), StandardCharsets.UTF_8));
            }
            body = sb.toString();
        }
        if (body != null && type.contentType != null && !hasHeader(headers, "Content-Type")) {
            headers.add(new KeyValue("Content-Type", type.contentType));
        }
        if (!unresolved.isEmpty()) {
            warnings.add("Not defined: " + String.join(", ", unresolved));
        }
        return new PreparedRequest(r.method == null ? HttpMethod.GET : r.method, uri, url, headers, body, warnings, unresolved);
    }

    private static boolean isMasked(String s) {
        return s != null && s.contains(VariableResolver.MASK);
    }

    private static String resolve(VariableResolver vars, String s, boolean mask, Set<String> unresolved) {
        if (s == null) return "";
        String out = mask ? vars.resolveMasked(s) : vars.resolve(s);
        for (VariableResolver.Token t : VariableResolver.tokens(out)) unresolved.add(t.name());
        return out;
    }

    private static boolean hasHeader(List<KeyValue> headers, String name) {
        for (KeyValue kv : headers) if (kv.key.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Percent-encodes characters that users commonly paste raw (spaces, braces, pipes…). */
    static String encodeUnsafe(String url) {
        StringBuilder sb = new StringBuilder(url.length() + 16);
        byte[] bytes = url.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xff;
            boolean safe = c > 0x20 && c < 0x7f && "\"<>\\^`{|} ".indexOf(c) < 0;
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}
