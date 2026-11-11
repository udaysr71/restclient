package dev.restclient.openapi;

import dev.restclient.json.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small YAML reader, written for OpenAPI files so the app needs no YAML library. It reads the YAML people
 * actually write by hand: nested mappings and lists, plain and quoted strings, {@code |} and {@code >} text
 * blocks, inline {@code [a, b]} and {@code {a: b}}, comments, and anchors with aliases ({@code &a}, {@code *a},
 * {@code <<}). It doesn't read complex keys ({@code ? }), tags are ignored, and only the first document of a
 * multi-document file is read.
 *
 * <p>The result uses the same types as {@link Json#parse}: LinkedHashMap, ArrayList, String, Boolean,
 * {@link Json.Num} and null. Mapping keys are always strings.
 *
 * <p>The input is untrusted. Nesting depth, the number of values and the growth caused by aliases are all
 * capped, so a hostile file fails with an error instead of exhausting memory.
 */
public final class Yaml {
    public static final int MAX_DEPTH = 200;
    public static final int MAX_NODES = 2_000_000;

    private final String[] raw;
    private final int[] ind;
    private final String[] txt;
    private int n;
    private int pos;
    private int nodes;
    private final Map<String, Object> anchors = new HashMap<>();

    private Yaml(String text) throws YamlException {
        if (text.startsWith("﻿")) text = text.substring(1);
        raw = text.split("\r\n|\r|\n", -1);
        n = raw.length;
        ind = new int[n];
        txt = new String[n];
        for (int i = 0; i < n; i++) {
            int k = 0;
            while (k < raw[i].length() && raw[i].charAt(k) == ' ') k++;
            ind[i] = k;
            txt[i] = raw[i].substring(k);
        }
    }

    public static Object parse(String text) throws YamlException {
        Yaml y = new Yaml(text == null ? "" : text);
        try {
            return y.document();
        } catch (StackOverflowError e) {
            throw new YamlException("the file is nested too deeply", 0);
        }
    }

    private Object document() throws YamlException {
        // directives and the first document marker
        while (pos < n && (blank(pos) || txt[pos].startsWith("%"))) pos++;
        if (pos < n && ind[pos] == 0 && (txt[pos].equals("---") || txt[pos].startsWith("--- "))) {
            String rest = txt[pos].substring(3).strip();
            if (rest.isEmpty() || rest.startsWith("#")) pos++;
            else {
                ind[pos] = 4;
                txt[pos] = rest;
            }
        }
        // only the first document: stop at the next marker
        for (int i = pos; i < n; i++) {
            if (ind[i] == 0 && (raw[i].equals("...") || raw[i].equals("---") || raw[i].startsWith("--- "))) {
                if (i == pos) continue;
                n = i;
                break;
            }
        }
        Object v = node(-1, 0);
        skip();
        if (pos < n) throw new YamlException("didn't expect “" + clip(txt[pos]) + "” here (check the indentation)", pos + 1);
        return v;
    }

    // ---- lines -----------------------------------------------------------------------------

    private boolean blank(int i) {
        String t = txt[i];
        return t.isBlank() || t.startsWith("#");
    }

    /** Moves to the next line that has content. */
    private void skip() throws YamlException {
        while (pos < n && blank(pos)) pos++;
        if (pos < n && txt[pos].startsWith("\t")) {
            throw new YamlException("a tab is used for indentation; YAML needs spaces", pos + 1);
        }
    }

    private static boolean dash(String t) {
        return t.equals("-") || t.startsWith("- ");
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private void count() throws YamlException {
        if (++nodes > MAX_NODES) throw new YamlException("the file has too many values", pos + 1);
    }

    // ---- structure -------------------------------------------------------------------------

    /** The value that starts at the current line, if that line is indented more than {@code parentIndent}. */
    private Object node(int parentIndent, int depth) throws YamlException {
        if (depth > MAX_DEPTH) throw new YamlException("the file is nested too deeply", pos + 1);
        skip();
        if (pos >= n || ind[pos] <= parentIndent) return null;
        String t = txt[pos];
        if (dash(t)) return sequence(ind[pos], depth);
        if (t.startsWith("? ") || t.equals("?")) throw new YamlException("complex keys (“? ”) aren't supported", pos + 1);
        if (keyColon(t) >= 0) return mapping(ind[pos], depth);
        return inline(t, parentIndent, depth, false);
    }

    private Object mapping(int indent, int depth) throws YamlException {
        Map<String, Object> map = new LinkedHashMap<>();
        count();
        while (true) {
            skip();
            if (pos >= n || ind[pos] < indent) break;
            if (ind[pos] > indent) throw new YamlException("“" + clip(txt[pos]) + "” is indented more than the lines above it", pos + 1);
            String t = txt[pos];
            if (dash(t)) break;
            int colon = keyColon(t);
            if (colon < 0) throw new YamlException("expected “name: value” but found “" + clip(t) + "”", pos + 1);
            String key = keyText(t.substring(0, colon).strip());
            Object value = inline(t.substring(colon + 1), indent, depth, true);
            if (key.equals("<<")) merge(map, value);
            else map.put(key, value);
        }
        return map;
    }

    private void merge(Map<String, Object> into, Object value) {
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> into.putIfAbsent(String.valueOf(k), v));
        } else if (value instanceof List<?> l) {
            for (Object o : l) merge(into, o);
        }
    }

    private Object sequence(int indent, int depth) throws YamlException {
        List<Object> list = new ArrayList<>();
        count();
        while (true) {
            skip();
            if (pos >= n || ind[pos] < indent) break;
            if (ind[pos] > indent) throw new YamlException("“" + clip(txt[pos]) + "” is indented more than the list it's in", pos + 1);
            String t = txt[pos];
            if (!dash(t)) break;
            String rest = t.substring(1);
            int lead = 0;
            while (lead < rest.length() && rest.charAt(lead) == ' ') lead++;
            String content = rest.substring(lead);
            if (content.isEmpty() || content.startsWith("#")) {
                pos++;
                list.add(node(indent, depth + 1));
            } else {
                // read the rest of the line as if it were a line of its own, indented to where it starts
                ind[pos] = indent + 1 + lead;
                txt[pos] = content;
                list.add(node(indent, depth + 1));
            }
        }
        return list;
    }

    /**
     * Where the colon that ends a mapping key is, or -1 if the line isn't "key: value". A colon counts when it's
     * followed by a space or ends the line.
     */
    private static int keyColon(String t) {
        if (t.isEmpty()) return -1;
        char first = t.charAt(0);
        if (first == '[' || first == '{' || first == '|' || first == '>' || first == '#') return -1;
        int i = 0;
        if (first == '"' || first == '\'') {
            i = 1;
            while (i < t.length()) {
                char c = t.charAt(i);
                if (first == '"' && c == '\\') i++;
                else if (c == first) {
                    if (first == '\'' && i + 1 < t.length() && t.charAt(i + 1) == '\'') i++;
                    else break;
                }
                i++;
            }
            if (i >= t.length()) return -1;
            i++;
            while (i < t.length() && t.charAt(i) == ' ') i++;
            return i < t.length() && t.charAt(i) == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ') ? i : -1;
        }
        for (; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '#' && i > 0 && t.charAt(i - 1) == ' ') return -1;
            if (c == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ')) return i;
        }
        return -1;
    }

    private String keyText(String k) throws YamlException {
        if (k.length() >= 2 && (k.charAt(0) == '"' || k.charAt(0) == '\'')) {
            Flow f = new Flow(k, pos + 1);
            return f.quoted();
        }
        return k;
    }

    // ---- values ----------------------------------------------------------------------------

    /**
     * The value whose text starts with {@code v} on the current line; consumes that line and any lines the value
     * continues onto.
     *
     * @param afterKey the value follows "key:", so a list at the key's own indentation belongs to it
     */
    private Object inline(String v, int parentIndent, int depth, boolean afterKey) throws YamlException {
        int line = pos + 1;
        v = v.strip();
        String anchor = null;
        while (true) {
            if (v.startsWith("&")) {
                int sp = v.indexOf(' ');
                anchor = sp < 0 ? v.substring(1) : v.substring(1, sp);
                v = sp < 0 ? "" : v.substring(sp).strip();
            } else if (v.startsWith("!") ) {
                int sp = v.indexOf(' ');
                v = sp < 0 ? "" : v.substring(sp).strip();
            } else {
                break;
            }
        }
        Object result;
        if (v.isEmpty() || v.startsWith("#")) {
            pos++;
            skip();
            if (pos < n && ind[pos] > parentIndent) result = node(parentIndent, depth + 1);
            else if (afterKey && pos < n && ind[pos] == parentIndent && dash(txt[pos])) result = sequence(parentIndent, depth + 1);
            else result = null;
        } else if (v.startsWith("*")) {
            String name = v.substring(1).split("[ #]", 2)[0];
            if (!anchors.containsKey(name)) throw new YamlException("“*" + name + "” refers to an anchor that isn't defined above it", line);
            pos++;
            result = copy(anchors.get(name), 0);
        } else if (v.charAt(0) == '|' || v.charAt(0) == '>') {
            result = block(v, parentIndent);
        } else if (v.charAt(0) == '[' || v.charAt(0) == '{') {
            result = flow(v, depth);
        } else if (v.charAt(0) == '"' || v.charAt(0) == '\'') {
            result = quotedLines(v);
        } else {
            result = plain(v, parentIndent);
        }
        count();
        if (anchor != null && !anchor.isEmpty()) anchors.put(anchor, result);
        return result;
    }

    /** Aliases are copied, so the size they add is counted against the limit like anything else. */
    private Object copy(Object v, int depth) throws YamlException {
        count();
        if (depth > MAX_DEPTH) throw new YamlException("aliases are nested too deeply", pos);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), copy(e.getValue(), depth + 1));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(copy(o, depth + 1));
            return out;
        }
        return v;
    }

    private static String stripComment(String s) {
        if (s.startsWith("#")) return "";
        int i = s.indexOf(" #");
        return (i < 0 ? s : s.substring(0, i)).stripTrailing();
    }

    /** A plain (unquoted) value; it may continue on following lines that are indented more than its parent. */
    private Object plain(String v, int parentIndent) {
        String first = stripComment(v);
        pos++;
        StringBuilder sb = null;
        while (pos < n) {
            int look = pos;
            int blanks = 0;
            while (look < n && txt[look].isBlank()) {
                look++;
                blanks++;
            }
            if (look >= n || ind[look] <= parentIndent || txt[look].startsWith("#")) break;
            if (sb == null) sb = new StringBuilder(first);
            if (blanks > 0) sb.append("\n".repeat(blanks));
            else sb.append(' ');
            sb.append(stripComment(txt[look]));
            pos = look + 1;
        }
        return sb == null ? typed(first) : sb.toString();
    }

    /** null, true/false and numbers written without quotes; everything else is text. */
    static Object typed(String s) {
        switch (s) {
            case "", "~", "null", "Null", "NULL" -> {
                return null;
            }
            case "true", "True", "TRUE" -> {
                return Boolean.TRUE;
            }
            case "false", "False", "FALSE" -> {
                return Boolean.FALSE;
            }
            default -> {
            }
        }
        if (s.length() <= 40 && s.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) return new Json.Num(s);
        return s;
    }

    /** A quoted value, which may run over several lines (line breaks inside become spaces). */
    private Object quotedLines(String v) throws YamlException {
        int startLine = pos + 1;
        char q = v.charAt(0);
        StringBuilder out = new StringBuilder();
        String s = v;
        int i = 1;
        while (true) {
            if (i >= s.length()) {
                // the value continues on the next line
                pos++;
                int blanks = 0;
                while (pos < n && txt[pos].isBlank()) {
                    pos++;
                    blanks++;
                }
                if (pos >= n) throw new YamlException("a quoted value is never closed", startLine);
                while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
                out.append(blanks > 0 ? "\n".repeat(blanks) : " ");
                s = txt[pos].stripLeading();
                i = 0;
                if (out.length() > 4_000_000) throw new YamlException("a quoted value is too long", startLine);
                continue;
            }
            char c = s.charAt(i);
            if (q == '\'' && c == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i += 2;
                    continue;
                }
                break;
            }
            if (q == '"' && c == '"') break;
            if (q == '"' && c == '\\') {
                if (i + 1 >= s.length()) { // a backslash at the end of a line joins the lines with no space
                    pos++;
                    if (pos >= n) throw new YamlException("a quoted value is never closed", startLine);
                    s = txt[pos].stripLeading();
                    i = 0;
                    continue;
                }
                i = escape(s, i + 1, out, pos + 1);
                continue;
            }
            out.append(c);
            i++;
        }
        String rest = s.substring(i + 1).strip();
        if (!rest.isEmpty() && !rest.startsWith("#")) throw new YamlException("didn't expect “" + clip(rest) + "” after the closing quote", pos + 1);
        pos++;
        return out.toString();
    }

    /** Reads the escape whose letter is at {@code i}; returns the index after it. */
    private static int escape(String s, int i, StringBuilder out, int line) throws YamlException {
        char e = s.charAt(i);
        int hex = switch (e) {
            case 'x' -> 2;
            case 'u' -> 4;
            case 'U' -> 8;
            default -> 0;
        };
        if (hex > 0) {
            if (i + hex >= s.length()) throw new YamlException("an escape (\\" + e + ") is cut short", line);
            try {
                int cp = Integer.parseUnsignedInt(s.substring(i + 1, i + 1 + hex), 16);
                if (!Character.isValidCodePoint(cp)) throw new NumberFormatException();
                out.appendCodePoint(cp);
            } catch (NumberFormatException ex) {
                throw new YamlException("“\\" + s.substring(i, i + 1 + hex) + "” isn't a valid escape", line);
            }
            return i + 1 + hex;
        }
        switch (e) {
            case 'n' -> out.append('\n');
            case 't', '\t' -> out.append('\t');
            case 'r' -> out.append('\r');
            case '0' -> out.append('\0');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'e' -> out.append('\u001b');
            case ' ', '"', '/', '\\' -> out.append(e);
            case 'N' -> out.append('\u0085');
            case '_' -> out.append(' ');
            default -> throw new YamlException("“\\" + e + "” isn't a valid escape", line);
        }
        return i + 1;
    }

    /** A {@code |} (keep line breaks) or {@code >} (join lines) block of text. */
    private Object block(String header, int parentIndent) throws YamlException {
        boolean folded = header.charAt(0) == '>';
        char chomp = 'c';
        int explicit = 0;
        String h = stripComment(header.substring(1)).strip();
        for (char c : h.toCharArray()) {
            if (c == '-' || c == '+') chomp = c;
            else if (c >= '1' && c <= '9') explicit = c - '0';
            else throw new YamlException("didn't expect “" + clip(h) + "” after " + header.charAt(0), pos + 1);
        }
        pos++;
        List<String> lines = new ArrayList<>();
        int blockIndent = explicit > 0 ? Math.max(0, parentIndent) + explicit : -1;
        while (pos < n) {
            String r = raw[pos];
            if (r.isBlank()) {
                lines.add(blockIndent >= 0 && r.length() > blockIndent ? r.substring(blockIndent) : "");
                pos++;
                continue;
            }
            int k = 0;
            while (k < r.length() && r.charAt(k) == ' ') k++;
            if (blockIndent < 0) {
                if (k <= parentIndent) break;
                blockIndent = k;
            }
            if (k < blockIndent) break;
            lines.add(r.substring(blockIndent));
            pos++;
        }
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) end--;
        int trailing = lines.size() - end;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            String l = lines.get(i);
            if (!folded) {
                if (i > 0) sb.append('\n');
                sb.append(l);
            } else if (l.isBlank()) {
                sb.append('\n'); // in a folded block, each empty line is one line break
            } else {
                String prev = i > 0 ? lines.get(i - 1) : null;
                if (prev != null && !prev.isBlank()) {
                    // neighbouring lines join with a space, unless one is indented further (kept as written)
                    sb.append(l.startsWith(" ") || prev.startsWith(" ") ? '\n' : ' ');
                }
                sb.append(l);
            }
        }
        if (end > 0) {
            if (chomp == 'c') sb.append('\n');
            else if (chomp == '+') sb.append("\n".repeat(trailing + 1));
        }
        if (sb.length() > 8_000_000) throw new YamlException("a text block is too long", pos);
        return sb.toString();
    }

    // ---- inline [lists] and {maps} ---------------------------------------------------------

    /** Collects an inline collection, which may run over several lines, then parses it. */
    private Object flow(String v, int depth) throws YamlException {
        int startLine = pos + 1;
        StringBuilder buf = new StringBuilder();
        String s = v;
        int open = 0;
        char quote = 0;
        while (true) {
            int end = -1;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quote != 0) {
                    if (quote == '"' && c == '\\') i++;
                    else if (c == quote) quote = 0;
                    continue;
                }
                if (c == '"' || c == '\'') quote = c;
                else if (c == '#' && (i == 0 || s.charAt(i - 1) == ' ')) {
                    s = s.substring(0, i);
                    break;
                } else if (c == '[' || c == '{') open++;
                else if (c == ']' || c == '}') {
                    open--;
                    if (open == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end >= 0) {
                String rest = s.substring(end + 1).strip();
                if (!rest.isEmpty() && !rest.startsWith("#")) throw new YamlException("didn't expect “" + clip(rest) + "” after the closing bracket", pos + 1);
                buf.append(s, 0, end + 1);
                pos++;
                break;
            }
            buf.append(s).append(' ');
            if (buf.length() > 4_000_000) throw new YamlException("an inline list or map is too long", startLine);
            pos++;
            if (pos >= n) throw new YamlException("a [ or { is never closed", startLine);
            s = txt[pos];
        }
        Flow f = new Flow(buf.toString(), startLine);
        Object out = f.value(depth);
        return out;
    }

    private final class Flow {
        final String s;
        final int line;
        int i;

        Flow(String s, int line) {
            this.s = s;
            this.line = line;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        YamlException bad(String what) {
            return new YamlException(what, line);
        }

        Object value(int depth) throws YamlException {
            if (depth > MAX_DEPTH) throw bad("the file is nested too deeply");
            count();
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '[') {
                i++;
                List<Object> list = new ArrayList<>();
                while (true) {
                    ws();
                    if (i >= s.length()) throw bad("a [ is never closed");
                    if (s.charAt(i) == ']') {
                        i++;
                        return list;
                    }
                    list.add(value(depth + 1));
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') i++;
                    else if (i >= s.length() || s.charAt(i) != ']') throw bad("expected , or ] in an inline list");
                }
            }
            if (c == '{') {
                i++;
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    ws();
                    if (i >= s.length()) throw bad("a { is never closed");
                    if (s.charAt(i) == '}') {
                        i++;
                        return map;
                    }
                    String key;
                    if (s.charAt(i) == '"' || s.charAt(i) == '\'') {
                        key = quoted();
                    } else {
                        int start = i;
                        while (i < s.length() && ",}".indexOf(s.charAt(i)) < 0
                                && !(s.charAt(i) == ':' && (i + 1 >= s.length() || " ,}".indexOf(s.charAt(i + 1)) >= 0))) i++;
                        key = s.substring(start, i).strip();
                    }
                    ws();
                    Object val = null;
                    if (i < s.length() && s.charAt(i) == ':') {
                        i++;
                        ws();
                        if (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != '}') val = value(depth + 1);
                    }
                    map.put(key, val);
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') i++;
                    else if (i >= s.length() || s.charAt(i) != '}') throw bad("expected , or } in an inline map");
                }
            }
            if (c == '"' || c == '\'') return quoted();
            int start = i;
            while (i < s.length() && ",]}".indexOf(s.charAt(i)) < 0) i++;
            String text = s.substring(start, i).strip();
            if (text.startsWith("*")) {
                if (!anchors.containsKey(text.substring(1))) throw bad("“" + clip(text) + "” refers to an anchor that isn't defined above it");
                return copy(anchors.get(text.substring(1)), 0);
            }
            return typed(text);
        }

        String quoted() throws YamlException {
            char q = s.charAt(i++);
            StringBuilder out = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw bad("a quoted value is never closed");
                char c = s.charAt(i);
                if (q == '\'' && c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i += 2;
                        continue;
                    }
                    i++;
                    return out.toString();
                }
                if (q == '"' && c == '"') {
                    i++;
                    return out.toString();
                }
                if (q == '"' && c == '\\') {
                    if (i + 1 >= s.length()) throw bad("a quoted value is never closed");
                    i = escape(s, i + 1, out, line);
                    continue;
                }
                out.append(c);
                i++;
            }
        }
    }
}
