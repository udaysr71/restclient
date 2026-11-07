package dev.restclient.json;

import java.io.IOException;

/** Invalid JSON, or JSON that doesn't fit the target type. */
public class JsonException extends IOException {
    private final int offset;

    public JsonException(String message, int offset) {
        super(message);
        this.offset = offset;
    }

    public JsonException(String message) {
        this(message, -1);
    }

    /** Character offset of the problem in the input, or -1. */
    public int offset() {
        return offset;
    }
}
