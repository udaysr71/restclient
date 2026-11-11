package dev.restclient.model;

import java.util.Objects;

/** An enable-able key/value row: a header, query parameter or form field. */
public class KeyValue {
    public boolean enabled = true;
    public String key = "";
    public String value = "";
    /** Multipart form rows only: the value is the path of a file to upload rather than text. */
    public boolean file;

    public KeyValue() {
    }

    public KeyValue(String key, String value) {
        this(key, value, true);
    }

    public KeyValue(String key, String value, boolean enabled) {
        this.key = key == null ? "" : key;
        this.value = value == null ? "" : value;
        this.enabled = enabled;
    }

    /** A multipart file part: {@code path} is the file to upload. */
    public static KeyValue file(String key, String path, boolean enabled) {
        KeyValue kv = new KeyValue(key, path, enabled);
        kv.file = true;
        return kv;
    }

    public KeyValue copy() {
        KeyValue kv = new KeyValue(key, value, enabled);
        kv.file = file;
        return kv;
    }

    public boolean isBlank() {
        return (key == null || key.isBlank()) && (value == null || value.isBlank());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof KeyValue kv)) return false;
        return enabled == kv.enabled && file == kv.file && Objects.equals(key, kv.key) && Objects.equals(value, kv.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, key, value, file);
    }

    @Override
    public String toString() {
        return (enabled ? "" : "# ") + key + ": " + value;
    }
}
