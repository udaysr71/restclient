package dev.restclient.model;

/** A {@code {{name}}} variable defined in an environment, a collection or globals. */
public class Variable {
    public String key = "";
    public String value = "";
    public boolean enabled = true;
    /** Secret values are masked in the UI and in history. */
    public boolean secret;

    public Variable() {
    }

    public Variable(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public Variable(String key, String value, boolean secret) {
        this(key, value);
        this.secret = secret;
    }

    public Variable copy() {
        Variable v = new Variable(key, value, secret);
        v.enabled = enabled;
        return v;
    }
}
