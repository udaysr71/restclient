package dev.restclient.capture;

import dev.restclient.http.ResponseData;
import dev.restclient.json.Json;
import dev.restclient.json.JsonException;
import dev.restclient.model.KeyValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Takes values out of a response so they can be kept in variables: the usual "log in, then use the token in the
 * next request" without a scripting engine. A rule is a variable name and a source:
 * <ul>
 *   <li>{@code status}: the status code</li>
 *   <li>{@code header.Location}: a response header (the name isn't case-sensitive)</li>
 *   <li>{@code cookie.session}: a cookie the response sets</li>
 *   <li>{@code body}: the whole body as text</li>
 *   <li>{@code body.data.token}, {@code $.items[0].id} or just {@code data.token}: a value in a JSON body</li>
 * </ul>
 * A JSON path is names separated by dots, with {@code [0]} for array items ({@code [-1]} is the last one) and
 * {@code ["odd name"]} for names with dots or spaces.
 */
public final class Capture {
    /** Captured values longer than this are cut: variables are for tokens and ids, not whole documents. */
    public static final int MAX_VALUE_CHARS = 64 * 1024;

    /** What a rule produced: a value, or why there isn't one. */
    public record Outcome(String variable, String source, String value, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    private Capture() {
    }

    /** True when {@code name} can be a variable name (what {@code {{name}}} accepts, spaces between words included; no $). */
    public static boolean validName(String name) {
        return dev.restclient.vars.VariableResolver.isUserName(name);
    }

    /** Runs every enabled rule against a response. */
    public static List<Outcome> run(List<KeyValue> rules, ResponseData res) {
        List<Outcome> out = new ArrayList<>();
        if (rules == null || res == null || res.isError()) return out;
        Object[] json = {null, null}; // parsed body (or the parse problem), read at most once
        for (KeyValue rule : rules) {
            if (!rule.enabled || rule.key == null || rule.key.isBlank()) continue;
            String name = rule.key.trim();
            String source = rule.value == null ? "" : rule.value.trim();
            if (!validName(name)) {
                out.add(new Outcome(name, source, null, "“" + name + "” can't be a variable name (use letters, digits, spaces, _ . -)"));
                continue;
            }
            try {
                String v = evaluate(source, res, json);
                if (v.length() > MAX_VALUE_CHARS) v = v.substring(0, MAX_VALUE_CHARS);
                out.add(new Outcome(name, source, v, null));
            } catch (Miss m) {
                out.add(new Outcome(name, source, null, m.getMessage()));
            }
        }
        return out;
    }

    /** One source evaluated on its own; throws nothing, returns null when there's no value. */
    public static String evaluate(String source, ResponseData res) {
        try {
            return evaluate(source == null ? "" : source.trim(), res, new Object[2]);
        } catch (Miss m) {
            return null;
        }
    }

    private static final class Miss extends Exception {
        Miss(String message) {
            super(message, null, false, false);
        }
    }

    private static String evaluate(String source, ResponseData res, Object[] json) throws Miss {
        if (source.isEmpty()) throw new Miss("no source given (try status, header.Name or body.path)");
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.equals("status")) return Integer.toString(res.status);
        if (lower.equals("body")) return res.bodyText();
        if (lower.startsWith("header.") || lower.startsWith("header:")) {
            String name = source.substring(7).trim();
            for (KeyValue h : res.headers) if (h.key.equalsIgnoreCase(name)) return h.value;
            throw new Miss("the response has no “" + name + "” header");
        }
        if (lower.startsWith("cookie.") || lower.startsWith("cookie:")) {
            String name = source.substring(7).trim();
            for (KeyValue h : res.headers) {
                if (!h.key.equalsIgnoreCase("Set-Cookie")) continue;
                String pair = h.value.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).trim().equals(name)) return pair.substring(eq + 1).trim();
            }
            throw new Miss("the response sets no “" + name + "” cookie");
        }
        String path = source;
        if (lower.startsWith("body.") || lower.startsWith("body[")) path = source.substring(4);
        else if (source.startsWith("$")) path = source.substring(1);
        if (json[0] == null && json[1] == null) {
            try {
                if (res.isBinary()) throw new JsonException("binary");
                json[0] = new Object[]{Json.parse(res.bodyText())};
            } catch (JsonException | StackOverflowError e) {
                json[1] = "the response body isn't JSON";
            }
        }
        if (json[1] != null) throw new Miss((String) json[1]);
        Object v = ((Object[]) json[0])[0];
        StringBuilder walked = new StringBuilder();
        for (Object seg : segments(path)) {
            if (seg instanceof Integer i) {
                if (!(v instanceof List<?> l)) throw new Miss(at(walked) + " isn't a list, so there's no [" + i + "]");
                int idx = i < 0 ? l.size() + i : i;
                if (idx < 0 || idx >= l.size()) throw new Miss(at(walked) + " has " + l.size() + " " + (l.size() == 1 ? "item" : "items") + ", so there's no [" + i + "]");
                v = l.get(idx);
                walked.append('[').append(i).append(']');
            } else {
                String key = (String) seg;
                if (!(v instanceof Map<?, ?> m) || !m.containsKey(key)) throw new Miss("there's no “" + key + "” " + (walked.length() == 0 ? "in the response body" : "in " + walked));
                v = m.get(key);
                walked.append(walked.length() == 0 ? "" : ".").append(key);
            }
        }
        return text(v);
    }

    private static String at(StringBuilder walked) {
        return walked.length() == 0 ? "The response body" : walked.toString();
    }

    private static String text(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return s;
        if (v instanceof Map<?, ?> || v instanceof List<?>) return Json.write(v, false);
        return v.toString();
    }

    /** Splits {@code .a.b[0]["c d"]} into "a", "b", 0, "c d". */
    static List<Object> segments(String path) throws Miss {
        List<Object> out = new ArrayList<>();
        int i = 0;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
            } else if (c == '[') {
                int close;
                if (i + 1 < n && (path.charAt(i + 1) == '"' || path.charAt(i + 1) == '\'')) {
                    char q = path.charAt(i + 1);
                    int endQuote = path.indexOf(q, i + 2);
                    close = endQuote < 0 ? -1 : path.indexOf(']', endQuote);
                    if (close != endQuote + 1 || endQuote < 0) throw new Miss("“" + path + "” isn't a path this understands (check the brackets)");
                    out.add(path.substring(i + 2, endQuote));
                } else {
                    close = path.indexOf(']', i);
                    if (close < 0) throw new Miss("“" + path + "” isn't a path this understands (a [ is never closed)");
                    String inside = path.substring(i + 1, close).trim();
                    try {
                        out.add(Integer.parseInt(inside));
                    } catch (NumberFormatException e) {
                        throw new Miss("[" + inside + "] isn't a position; use a number such as [0], or [\"name\"]");
                    }
                }
                i = close + 1;
            } else {
                int end = i;
                while (end < n && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
                out.add(path.substring(i, end).trim());
                i = end;
            }
        }
        return out;
    }

    /**
     * The JSON path a source names, as keys (String) and positions (Integer); null when the source is something
     * else (status, a header, a cookie, the whole body) or isn't a valid path.
     */
    public static List<Object> jsonPath(String source) {
        String src = source == null ? "" : source.trim();
        String lower = src.toLowerCase(Locale.ROOT);
        if (src.isEmpty() || lower.equals("status") || lower.equals("body") || lower.matches("(header|cookie)[.:].*")) return null;
        String path = lower.startsWith("body.") || lower.startsWith("body[") ? src.substring(4) : src.startsWith("$") ? src.substring(1) : src;
        try {
            return segments(path);
        } catch (Miss m) {
            return null;
        }
    }

    /** The source text for a JSON path: {@code body.data.items[0]["odd name"]}. */
    public static String pathSource(List<Object> path) {
        StringBuilder sb = new StringBuilder("body");
        for (Object seg : path) {
            if (seg instanceof Integer i) sb.append('[').append(i).append(']');
            else if (((String) seg).matches("[A-Za-z0-9_\\-$]+")) sb.append('.').append(seg);
            else sb.append("[\"").append(seg).append("\"]");
        }
        return sb.toString();
    }

    /** Sources this response offers, for autocomplete: status, its headers and the values in a JSON body. */
    public static List<String> suggestions(ResponseData res) {
        Set<String> out = new LinkedHashSet<>();
        if (res == null || res.isError()) return List.of("status", "body", "header.");
        if (!res.isBinary()) {
            try {
                paths(Json.parse(res.bodyText()), "body", 0, out);
            } catch (JsonException | StackOverflowError ignored) {
                // not JSON: no paths to offer
            }
        }
        out.add("body");
        out.add("status");
        for (KeyValue h : res.headers) {
            if (h.key.equalsIgnoreCase("Set-Cookie")) {
                String pair = h.value.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) out.add("cookie." + pair.substring(0, eq).trim());
            } else {
                out.add("header." + h.key);
            }
        }
        return new ArrayList<>(out);
    }

    private static void paths(Object v, String at, int depth, Set<String> out) {
        if (out.size() >= 300 || depth > 8) return;
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                String next = k.matches("[A-Za-z0-9_\\-$]+") ? at + "." + k : at + "[\"" + k + "\"]";
                if (k.contains("\"")) continue; // can't be written as a path
                paths(e.getValue(), next, depth + 1, out);
            }
        } else if (v instanceof List<?> l) {
            if (!l.isEmpty()) paths(l.get(0), at + "[0]", depth + 1, out);
        } else if (!at.equals("body")) {
            out.add(at);
        }
    }
}
