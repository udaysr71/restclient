package dev.restclient.http;

import dev.restclient.model.KeyValue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Result of sending a request: either a response or a classified error. */
public final class ResponseData {
    public enum ErrorKind { TLS_UNTRUSTED, TLS_HOSTNAME, TLS_OTHER, TIMEOUT, CONNECT, DNS, PROXY, CANCELLED, INVALID, OTHER }

    public final int status;
    public final List<KeyValue> headers;
    public final byte[] body;
    public final long elapsedMs;
    public final String host;
    public final ErrorKind errorKind;
    public final Throwable error;
    public final String errorMessage;

    private ResponseData(int status, List<KeyValue> headers, byte[] body, long elapsedMs, String host,
                         ErrorKind kind, Throwable error, String message) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.elapsedMs = elapsedMs;
        this.host = host;
        this.errorKind = kind;
        this.error = error;
        this.errorMessage = message;
    }

    public static ResponseData ok(int status, List<KeyValue> headers, byte[] body, long elapsedMs, String host) {
        return new ResponseData(status, headers, body, elapsedMs, host, null, null, null);
    }

    public static ResponseData failed(ErrorKind kind, Throwable error, String message, long elapsedMs, String host) {
        return new ResponseData(0, List.of(), new byte[0], elapsedMs, host, kind, error, message);
    }

    public boolean isError() {
        return errorKind != null;
    }

    public boolean isTls() {
        return errorKind == ErrorKind.TLS_UNTRUSTED || errorKind == ErrorKind.TLS_HOSTNAME || errorKind == ErrorKind.TLS_OTHER;
    }

    public String header(String name) {
        for (KeyValue kv : headers) if (kv.key.equalsIgnoreCase(name)) return kv.value;
        return null;
    }

    public String contentType() {
        String ct = header("Content-Type");
        return ct == null ? "" : ct;
    }

    public Charset charset() {
        String ct = contentType().toLowerCase(Locale.ROOT);
        int i = ct.indexOf("charset=");
        if (i >= 0) {
            String cs = ct.substring(i + 8).split("[;\\s]")[0].replace("\"", "");
            try {
                return Charset.forName(cs);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return StandardCharsets.UTF_8;
    }

    public String bodyText() {
        return new String(body, charset());
    }

    /** True for content we shouldn't try to show as text. */
    public boolean isBinary() {
        String ct = contentType().toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/")
                || ct.contains("octet-stream") || ct.contains("application/pdf") || ct.contains("zip")) return true;
        int n = Math.min(body.length, 1024);
        for (int i = 0; i < n; i++) if (body[i] == 0) return true;
        return false;
    }

    public String reason() {
        return reasonPhrase(status);
    }

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(100, "Continue"), Map.entry(101, "Switching Protocols"),
            Map.entry(200, "OK"), Map.entry(201, "Created"), Map.entry(202, "Accepted"),
            Map.entry(203, "Non-Authoritative Information"), Map.entry(204, "No Content"),
            Map.entry(205, "Reset Content"), Map.entry(206, "Partial Content"),
            Map.entry(300, "Multiple Choices"), Map.entry(301, "Moved Permanently"), Map.entry(302, "Found"),
            Map.entry(303, "See Other"), Map.entry(304, "Not Modified"), Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(402, "Payment Required"),
            Map.entry(403, "Forbidden"), Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"),
            Map.entry(406, "Not Acceptable"), Map.entry(408, "Request Timeout"), Map.entry(409, "Conflict"),
            Map.entry(410, "Gone"), Map.entry(411, "Length Required"), Map.entry(412, "Precondition Failed"),
            Map.entry(413, "Content Too Large"), Map.entry(414, "URI Too Long"),
            Map.entry(415, "Unsupported Media Type"), Map.entry(422, "Unprocessable Content"),
            Map.entry(425, "Too Early"), Map.entry(428, "Precondition Required"),
            Map.entry(429, "Too Many Requests"), Map.entry(431, "Request Header Fields Too Large"),
            Map.entry(500, "Internal Server Error"), Map.entry(501, "Not Implemented"),
            Map.entry(502, "Bad Gateway"), Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"), Map.entry(505, "HTTP Version Not Supported"));

    public static String reasonPhrase(int status) {
        return REASONS.getOrDefault(status, "");
    }
}
