package dev.restclient.model;

/** Kind of request body. */
public enum BodyType {
    NONE("None", null),
    JSON("JSON", "application/json"),
    XML("XML", "application/xml"),
    TEXT("Text", "text/plain"),
    FORM("Form", "application/x-www-form-urlencoded"),
    /** multipart/form-data: text fields and file uploads. The Content-Type carries a boundary, so it's built when sending. */
    MULTIPART("Multipart", null),
    /** One file sent as the whole body. */
    BINARY("File", null);

    public final String label;
    /** Content-Type sent automatically when the user has not set one. */
    public final String contentType;

    BodyType(String label, String contentType) {
        this.label = label;
        this.contentType = contentType;
    }

    public boolean isRaw() {
        return this == JSON || this == XML || this == TEXT;
    }
}
