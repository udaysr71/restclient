package dev.restclient.model;

/** HTTP methods supported by the client. */
public enum HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS;

    /** Short label used in narrow lists (sidebar, history). */
    public String shortLabel() {
        return switch (this) {
            case DELETE -> "DEL";
            case OPTIONS -> "OPT";
            default -> name();
        };
    }

    public static HttpMethod parse(String s) {
        if (s == null) return GET;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
