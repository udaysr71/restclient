package dev.restclient.model;

import java.util.Objects;

/** An enable-able key/value row: a header, query parameter or form field. */
public class KeyValue {
    public boolean enabled = true;
    public String key = "";
    public String value = "";

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

    public KeyValue copy() {
        return new KeyValue(key, value, enabled);
    }

    public boolean isBlank() {
        return (key == null || key.isBlank()) && (value == null || value.isBlank());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof KeyValue kv)) return false;
        return enabled == kv.enabled && Objects.equals(key, kv.key) && Objects.equals(value, kv.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, key, value);
    }

    @Override
    public String toString() {
        return (enabled ? "" : "# ") + key + ": " + value;
    }
}
