package dev.restclient.http;

import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.vars.VariableResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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
    /** The body as text. For multipart and file bodies this is a readable outline; {@link #bodyBytes} is what's sent. */
    public final String body;
    /** Exactly the bytes to send, or null when there's no body. Null in masked copies. */
    public final byte[] bodyBytes;
    /** The multipart boundary, if this is a multipart request. */
    public final String boundary;
    public final List<String> warnings;
    /** Variable names that were still unresolved. */
    public final Set<String> unresolved;

    private PreparedRequest(HttpMethod method, URI uri, String url, List<KeyValue> headers, String body,
                            byte[] bodyBytes, String boundary, List<String> warnings, Set<String> unresolved) {
        this.method = method;
        this.uri = uri;
        this.url = url;
        this.headers = headers;
        this.body = body;
        this.bodyBytes = bodyBytes;
        this.boundary = boundary;
        this.warnings = warnings;
        this.unresolved = unresolved;
    }

    public static final class InvalidRequestException extends Exception {
        /** True when the URL is what's wrong (as opposed to, say, a file that can't be read). */
        public final boolean urlProblem;

        public InvalidRequestException(String message) {
            this(message, true);
        }

        public InvalidRequestException(String message, boolean urlProblem) {
            super(message);
            this.urlProblem = urlProblem;
        }
    }

    /** Files larger than this aren't uploaded: bodies are built in memory. */
    public static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

    public static PreparedRequest prepare(RequestDef r, VariableResolver vars) throws InvalidRequestException {
        return build(r, vars, false, true, null);
    }

    /**
     * Same as {@link #prepare} but for display and history: secret variables are masked, the URL is not validated
     * and files are named rather than read.
     */
    public static PreparedRequest prepareMasked(RequestDef r, VariableResolver vars) {
        return prepareMasked(r, vars, null);
    }

    /** As {@link #prepareMasked(RequestDef, VariableResolver)}, showing the multipart boundary {@code sent} used. */
    public static PreparedRequest prepareMasked(RequestDef r, VariableResolver vars, PreparedRequest sent) {
        try {
            return build(r, vars, true, false, sent == null ? null : sent.boundary);
        } catch (InvalidRequestException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedRequest build(RequestDef r, VariableResolver vars, boolean mask, boolean validate, String boundary)
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
        byte[] bytes = body == null || !validate ? null : body.getBytes(StandardCharsets.UTF_8);
        String usedBoundary = null;
        if (type == BodyType.BINARY) {
            String path = resolve(vars, r.bodyFile, mask, unresolved).trim();
            if (path.isEmpty()) {
                if (validate) throw new InvalidRequestException("Choose a file to send in the Body tab.", false);
                body = "";
            } else {
                Path file = Path.of(path);
                if (validate) bytes = readUpload(file);
                body = "(contents of " + fileLabel(file, bytes) + ")";
                if (!hasHeader(headers, "Content-Type")) headers.add(new KeyValue("Content-Type", MediaTypes.forFile(path)));
            }
        } else if (type == BodyType.MULTIPART) {
            // a Content-Type the user typed can't know the boundary, so ours replaces it
            String typed = null;
            for (int i = headers.size() - 1; i >= 0; i--) {
                if (headers.get(i).key.equalsIgnoreCase("Content-Type")) typed = headers.remove(i).value;
            }
            String subtype = typed != null && typed.toLowerCase(Locale.ROOT).startsWith("multipart/")
                    ? typed.split(";")[0].trim() : "multipart/form-data";
            usedBoundary = boundary != null ? boundary : newBoundary();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StringBuilder shown = new StringBuilder();
            for (KeyValue kv : r.multipart) {
                if (!kv.enabled || kv.key == null || kv.key.isBlank()) continue;
                String name = resolve(vars, kv.key, mask, unresolved);
                String value = resolve(vars, kv.value, mask, unresolved);
                StringBuilder head = new StringBuilder("--").append(usedBoundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"").append(quoted(name)).append('"');
                byte[] content;
                String contentShown;
                if (kv.file) {
                    String path = value.trim();
                    if (path.isEmpty()) {
                        if (validate) throw new InvalidRequestException("Choose a file for the “" + name + "” field in the Body tab.", false);
                        continue;
                    }
                    Path file = Path.of(path);
                    content = validate ? readUpload(file) : new byte[0];
                    contentShown = "(contents of " + fileLabel(file, validate ? content : null) + ")";
                    String fileName = file.getFileName() == null ? "file" : file.getFileName().toString();
                    head.append("; filename=\"").append(quoted(fileName)).append("\"\r\nContent-Type: ").append(MediaTypes.forFile(path));
                } else {
                    content = value.getBytes(StandardCharsets.UTF_8);
                    contentShown = value;
                }
                head.append("\r\n\r\n");
                out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
                out.writeBytes(content);
                out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
                shown.append(head.toString().replace("\r\n", "\n")).append(contentShown).append('\n');
            }
            String end = "--" + usedBoundary + "--\r\n";
            out.writeBytes(end.getBytes(StandardCharsets.UTF_8));
            shown.append(end.strip());
            if (validate) bytes = out.toByteArray();
            body = shown.toString();
            headers.add(new KeyValue("Content-Type", subtype + "; boundary=" + usedBoundary));
        }
        if (body != null && type.contentType != null && !hasHeader(headers, "Content-Type")) {
            headers.add(new KeyValue("Content-Type", type.contentType));
        }
        if (!unresolved.isEmpty()) {
            warnings.add("Not defined: " + String.join(", ", unresolved));
        }
        return new PreparedRequest(r.method == null ? HttpMethod.GET : r.method, uri, url, headers, body, bytes,
                usedBoundary, warnings, unresolved);
    }

    private static String newBoundary() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("----RestClientBoundary");
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    /** Field and file names go inside quotes in a part header: escape what would end the quotes or the line. */
    private static String quoted(String s) {
        return s.replace("\\", "\\\\").replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    private static byte[] readUpload(Path file) throws InvalidRequestException {
        try {
            if (!Files.isRegularFile(file)) throw new InvalidRequestException("There's no file at " + file + ".", false);
            long size = Files.size(file);
            if (size > MAX_UPLOAD_BYTES) {
                throw new InvalidRequestException(file.getFileName() + " is " + (size / (1024 * 1024))
                        + " MB; files over " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB can't be uploaded.", false);
            }
            return Files.readAllBytes(file);
        } catch (IOException | SecurityException e) {
            throw new InvalidRequestException("Couldn't read " + file + ": " + e.getMessage(), false);
        }
    }

    private static String fileLabel(Path file, byte[] content) {
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        if (content == null) return name;
        long n = content.length;
        String size = n < 1024 ? n + " B" : n < 1024 * 1024 ? String.format(Locale.ROOT, "%.1f KB", n / 1024.0)
                : String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        return name + ", " + size;
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
    public static String encodeUnsafe(String url) {
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
