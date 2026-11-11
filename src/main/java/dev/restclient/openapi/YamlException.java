package dev.restclient.openapi;

/** A YAML file that couldn't be read; the message says where and why. */
public class YamlException extends Exception {
    public YamlException(String message, int line) {
        super(line > 0 ? "line " + line + ": " + message : message);
    }
}
