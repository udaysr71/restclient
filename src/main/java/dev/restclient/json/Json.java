package dev.restclient.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, dependency-free JSON library (RFC 8259).
 * <ul>
 *   <li>{@link #parse} turns text into a tree of {@code Map<String,Object>} (insertion-ordered),
 *       {@code List<Object>}, {@code String}, {@link Num}, {@code Boolean} and {@code null}.</li>
 *   <li>{@link #write} turns such a tree back into text, optionally pretty-printed.</li>
 *   <li>{@link #reformat} re-indents JSON text without building a tree, so numbers, escapes and
 *       duplicate keys are kept exactly as written.</li>
 * </ul>
 */
public final class Json {
    private static final int MAX_DEPTH = 1000;

    private Json() {
    }

    /** A number kept as its original text, so no precision is lost. */
    public record Num(String raw) {
        public long longValue() {
            return new java.math.BigDecimal(raw).longValue();
        }

        public int intValue() {
            return new java.math.BigDecimal(raw).intValue();
        }

        public double doubleValue() {
            return Double.parseDouble(raw);
        }

        @Override
        public String toString() {
            return raw;
        }
    }

    // ---- parsing ---------------------------------------------------------------------------

    public static Object parse(String text) throws JsonException {
        Parser p = new Parser(text, null);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i < text.length()) throw p.error("Unexpected content after the JSON value");
        return v;
    }

    /** Pretty-prints JSON text (two-space indent), or returns null if it isn't valid JSON. */
    public static String reformat(String text) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder(text.length() + text.length() / 4);
        try {
            Parser p = new Parser(text, out);
            p.ws();
            if (p.i >= text.length()) return null;
            p.value(0);
            p.ws();
            if (p.i < text.length()) return null;
            return out.toString();
        } catch (JsonException e) {
            return null;
        }
    }

    /**
     * Recursive-descent parser. When {@code out} is set it writes a pretty-printed copy of the
     * input as it goes (and the returned values are not needed).
     */
    private static final class Parser {
        private final String s;
        private final StringBuilder out;
        private int i;

        Parser(String s, StringBuilder out) {
            this.s = s;
            this.out = out;
        }

        JsonException error(String message) {
            int line = 1;
            int col = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new JsonException(message + " at line " + line + ", column " + col, i);
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private void indent(int depth) {
            out.append('\n');
            for (int k = 0; k < depth; k++) out.append("  ");
        }

        Object value(int depth) throws JsonException {
            if (depth > MAX_DEPTH) throw error("Too deeply nested");
            if (i >= s.length()) throw error("Unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"': {
                    int start = i;
                    String str = string();
                    if (out != null) out.append(s, start, i);
                    return str;
                }
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("Unexpected character '" + c + "'");
            }
        }

        private Object literal(String word, Object value) throws JsonException {
            if (!s.startsWith(word, i)) throw error("Expected " + word);
            i += word.length();
            if (out != null) out.append(word);
            return value;
        }

        private Num number() throws JsonException {
            int start = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw error("Invalid number");
            if (s.charAt(i) == '0') {
                i++;
            } else if (Character.isDigit(s.charAt(i))) {
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            } else {
                throw error("Invalid number");
            }
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("Invalid number");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("Invalid number");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String raw = s.substring(start, i);
            if (out != null) out.append(raw);
            return new Num(raw);
        }

        private String string() throws JsonException {
            i++; // opening quote
            StringBuilder sb = out == null ? new StringBuilder() : null;
            while (true) {
                if (i >= s.length()) throw error("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c < 0x20) throw error("Control character in string");
                if (c != '\\') {
                    if (sb != null) sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw error("Unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> append(sb, e);
                    case 'b' -> append(sb, '\b');
                    case 'f' -> append(sb, '\f');
                    case 'n' -> append(sb, '\n');
                    case 'r' -> append(sb, '\r');
                    case 't' -> append(sb, '\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("Invalid \\u escape");
                        int cp = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0) throw error("Invalid \\u escape");
                            cp = cp * 16 + d;
                        }
                        i += 4;
                        append(sb, (char) cp);
                    }
                    default -> throw error("Invalid escape \\" + e);
                }
            }
            return sb == null ? null : sb.toString();
        }

        private static void append(StringBuilder sb, char c) {
            if (sb != null) sb.append(c);
        }

        private Map<String, Object> object(int depth) throws JsonException {
            Map<String, Object> map = out == null ? new LinkedHashMap<>() : null;
            i++;
            if (out != null) out.append('{');
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                if (out != null) out.append('}');
                return map;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw error("Expected a property name");
                if (out != null) indent(depth + 1);
                int keyStart = i;
                String key = string();
                if (out != null) out.append(s, keyStart, i).append(": ");
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw error("Expected ':'");
                i++;
                ws();
                Object v = value(depth + 1);
                if (map != null) map.put(key, v);
                ws();
                if (i >= s.length()) throw error("Unterminated object");
                char c = s.charAt(i++);
                if (c == ',') {
                    if (out != null) out.append(',');
                    continue;
                }
                if (c == '}') {
                    if (out != null) {
                        indent(depth);
                        out.append('}');
                    }
                    return map;
                }
                i--;
                throw error("Expected ',' or '}'");
            }
        }

        private List<Object> array(int depth) throws JsonException {
            List<Object> list = out == null ? new ArrayList<>() : null;
            i++;
            if (out != null) out.append('[');
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                if (out != null) out.append(']');
                return list;
            }
            while (true) {
                ws();
                if (out != null) indent(depth + 1);
                Object v = value(depth + 1);
                if (list != null) list.add(v);
                ws();
                if (i >= s.length()) throw error("Unterminated array");
                char c = s.charAt(i++);
                if (c == ',') {
                    if (out != null) out.append(',');
                    continue;
                }
                if (c == ']') {
                    if (out != null) {
                        indent(depth);
                        out.append(']');
                    }
                    return list;
                }
                i--;
                throw error("Expected ',' or ']'");
            }
        }
    }

    // ---- writing ---------------------------------------------------------------------------

    public static String write(Object tree, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, tree, pretty, 0);
        if (pretty) sb.append('\n');
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean || v instanceof Num) {
            sb.append(v);
        } else if (v instanceof Number n) {
            sb.append(numberText(n));
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) newline(sb, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, depth + 1);
            }
            if (pretty) newline(sb, depth);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) sb.append(',');
                if (pretty) newline(sb, depth + 1);
                write(sb, l.get(k), pretty, depth + 1);
            }
            if (pretty) newline(sb, depth);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Not a JSON value: " + v.getClass().getName());
        }
    }

    private static String numberText(Number n) {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())) return "null";
        if (n instanceof Float f && (f.isNaN() || f.isInfinite())) return "null";
        return n.toString();
    }

    private static void newline(StringBuilder sb, int depth) {
        sb.append('\n');
        for (int k = 0; k < depth; k++) sb.append("  ");
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
