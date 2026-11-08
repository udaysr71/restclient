package dev.restclient.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes theme files: a small, CSS-like format for the app's named colours.
 *
 * <pre>
 * &#47;* nord.css *&#47;
 * &#64;theme {
 *   name: "Nord";
 *   base: dark;            &#47;* light, dark, or another theme's id: unset colours come from it *&#47;
 *   toggle: "nord-light";  &#47;* optional: where the light/dark button goes from here *&#47;
 * }
 *
 * :root {
 *   --polar: #2E3440;          &#47;* your own variables are fine *&#47;
 *   --panelBg: var(--polar);
 *   --accent: #5E81AC;
 *   --method-GET: #A3BE8C;
 *   --hoverSubtle: rgba(236, 239, 244, 0.08);
 * }
 * </pre>
 *
 * Colours: {@code #RGB}, {@code #RGBA}, {@code #RRGGBB}, {@code #RRGGBBAA}, {@code rgb(r, g, b)},
 * {@code rgba(r, g, b, a)} with {@code a} from 0 to 1, or {@code var(--name)}. Comments are {@code /* … *&#47;}.
 * Nothing in a file can run code or load anything: it is only parsed for these values.
 * Problems never stop a theme loading; they are collected as {@link Parsed#warnings} and shown in Settings.
 */
public final class ThemeFile {
    /** Every colour a theme can set, grouped as the exported file lists them. Names match {@link Theme}'s methods. */
    public static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
    /** One-line notes written next to each colour in an exported file. */
    public static final Map<String, String> NOTES = new LinkedHashMap<>();

    static {
        group("Accent: primary buttons, selection marks, focus",
                "accent", "Send and other primary buttons, checkboxes, the focus ring",
                "accentHover", "primary button under the pointer",
                "accentPressed", "primary button while pressed",
                "focusRing", "soft glow around a focused field (use some transparency)",
                "link", "links and the active tab underline");
        group("Text",
                "text", "body text",
                "secondary", "labels, hints, metadata",
                "disabledText", "disabled controls",
                "iconColor", "toolbar and sidebar icons");
        group("Surfaces",
                "panelBg", "main window background",
                "editorBg", "request and response editors",
                "codeBg", "read-only code (response body)",
                "sidebarBg", "collections and history sidebar",
                "toolbarBg", "title bar and toolbar",
                "subtleBg", "segmented controls, quiet panels",
                "separator", "hairlines between areas",
                "menuBg", "menus and popups",
                "tooltipBg", "tooltips and variable popovers");
        group("Controls",
                "fieldBg", "text fields",
                "fieldBorder", "text field outline (keep 3:1 against fieldBg)",
                "buttonBg", "secondary buttons",
                "buttonHover", "secondary button under the pointer",
                "buttonPressed", "secondary button while pressed",
                "segmentSelected", "the selected part of a segmented control",
                "hoverSubtle", "row and icon hover (use transparency)",
                "pressedSubtle", "row and icon press (use transparency)",
                "selectionBg", "selected row in lists and trees",
                "textSelection", "selected text",
                "thumb", "scroll bar thumb",
                "thumbHover", "scroll bar thumb under the pointer",
                "findMatch", "find-in-response highlight");
        group("Status badges and banners",
                "okBg", "2xx badge", "okFg", "2xx text",
                "infoBg", "info banner", "infoFg", "info text",
                "warnBg", "warning banner", "warnFg", "warning text",
                "warnIcon", "warning icon", "warnBorder", "warning outline",
                "errBg", "error badge and banner", "errFg", "error text and error outlines");
        group("Variables ({{name}} in fields)",
                "varOkBg", "defined variable", "varOkBorder", "defined variable outline",
                "varMissingBg", "undefined variable", "varMissingLine", "undefined variable underline");
        group("Syntax (JSON, XML, HTML)",
                "synKey", "keys", "synString", "strings", "synNumber", "numbers",
                "synLiteral", "true, false, null", "synPunct", "brackets and commas",
                "synTag", "XML/HTML tags", "synAttr", "attributes", "synComment", "comments");
        group("HTTP methods",
                "method.GET", "GET", "method.POST", "POST", "method.PUT", "PUT",
                "method.PATCH", "PATCH", "method.DELETE", "DELETE", "method.HEAD", "HEAD and OPTIONS");
    }

    private static void group(String title, String... namesAndNotes) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < namesAndNotes.length; i += 2) {
            names.add(namesAndNotes[i]);
            NOTES.put(namesAndNotes[i], namesAndNotes[i + 1]);
        }
        GROUPS.put(title, List.copyOf(names));
    }

    public static Set<String> tokens() {
        return NOTES.keySet();
    }

    // ---- allow-lists and limits: a theme file is untrusted input, so only these shapes are ever accepted

    /** Largest theme file read, in bytes (an exported file with every colour is about 5 KB). */
    public static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_DECLARATIONS = 400;
    private static final int MAX_VAR_DEPTH = 16;
    private static final int MAX_NAME = 48;
    /** Theme ids (file names, base, toggle): lowercase letters, digits, - and _. No dots, slashes or spaces. */
    public static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    /** Custom property names after "--". */
    private static final java.util.regex.Pattern VAR_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})");
    private static final java.util.regex.Pattern RGB = java.util.regex.Pattern.compile(
            "rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*(1(?:\\.0{1,4})?|0(?:\\.\\d{1,4})?|\\.\\d{1,4}|\\d{1,3}%)\\s*)?\\)");
    private static final java.util.regex.Pattern VAR = java.util.regex.Pattern.compile("var\\(\\s*--([A-Za-z][A-Za-z0-9_-]{0,63})\\s*\\)");

    /** Whether a string is a valid theme id. */
    public static boolean isId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    /**
     * A display name made only of letters, digits, spaces and a few punctuation marks, at most 48 characters.
     * Anything else is dropped, so a name can never start Swing's HTML rendering ("&lt;html&gt;…") or carry
     * control characters into menus.
     */
    public static String safeName(String raw) {
        if (raw == null) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length() && b.length() < MAX_NAME; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || " -_.,'()&+!#".indexOf(cp) >= 0) b.appendCodePoint(cp);
        }
        String out = b.toString().strip().replaceAll(" {2,}", " ");
        return out.isEmpty() ? null : out;
    }

    /** What a file says, before its base is resolved. Colours are ARGB. */
    public record Parsed(String name, String base, String toggle, Map<String, Integer> colors, List<String> warnings) {
    }

    private ThemeFile() {
    }

    /** {@code --method-GET} in a file is the token {@code method.GET}. */
    static String tokenFor(String cssName) {
        if (cssName.startsWith("method-")) return "method." + cssName.substring(7).toUpperCase(Locale.ROOT);
        return cssName;
    }

    static String cssNameFor(String token) {
        return token.startsWith("method.") ? "method-" + token.substring(7) : token;
    }

    public static Parsed parse(String text) {
        List<String> warnings = new ArrayList<>();
        if (text.length() > MAX_BYTES) {
            return new Parsed(null, null, null, Map.of(), List.of("The file is larger than " + MAX_BYTES / 1024
                    + " KB, so it wasn't read. Theme files only need colours."));
        }
        String src = stripComments(text, warnings);
        Map<String, String> meta = new LinkedHashMap<>();
        Map<String, String> vars = new LinkedHashMap<>();
        int i = 0;
        boolean sawRoot = false;
        while (true) {
            int open = src.indexOf('{', i);
            if (open < 0) {
                if (!src.substring(i).isBlank()) warnings.add("Ignored text after the last block: \"" + snippet(src.substring(i)) + "\"");
                break;
            }
            int close = src.indexOf('}', open);
            if (close < 0) {
                warnings.add("A block opened with { is never closed.");
                close = src.length();
            }
            String selector = src.substring(i, open).strip();
            String body = src.substring(open + 1, close);
            if (selector.equals("@theme")) {
                declarations(body, warnings, (k, v) -> {
                    String key = k.toLowerCase(Locale.ROOT);
                    if (Set.of("name", "base", "toggle").contains(key)) meta.put(key, unquote(v));
                    else warnings.add("Unknown @theme setting \"" + snippet(k) + "\" (use name, base and toggle).");
                });
            } else if (selector.equals(":root")) {
                sawRoot = true;
                declarations(body, warnings, (k, v) -> {
                    if (!k.startsWith("--")) {
                        warnings.add("\"" + snippet(k) + "\" isn't a colour name: names start with -- (for example --accent).");
                        return;
                    }
                    if (!VAR_NAME.matcher(k.substring(2)).matches()) {
                        warnings.add("\"" + snippet(k) + "\" isn't a valid name (letters, digits, - and _ after --).");
                        return;
                    }
                    if (vars.size() >= MAX_DECLARATIONS) {
                        if (vars.size() == MAX_DECLARATIONS) warnings.add("More than " + MAX_DECLARATIONS + " declarations; the rest were ignored.");
                        vars.put("\u0000overflow", "");
                        return;
                    }
                    vars.put(k.substring(2), v);
                });
            } else {
                warnings.add("Only @theme { … } and :root { … } are read; ignored \"" + snippet(selector) + " { … }\".");
            }
            i = Math.min(src.length(), close + 1);
        }
        if (!sawRoot) warnings.add("No :root { … } block, so this theme sets no colours.");

        vars.remove("\u0000overflow");
        Set<String> known = tokens();
        Map<String, Integer> colors = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String token = tokenFor(e.getKey());
            boolean isToken = known.contains(token);
            Integer argb = resolve(e.getKey(), vars, warnings, new ArrayList<>());
            if (argb == null) continue;
            if (isToken) colors.put(token, argb);
            else if (!usedAsVar(e.getKey(), vars)) {
                // your own names are fine; only flag what looks like a misspelt colour name
                String hint = closest(token, known);
                if (hint != null) warnings.add("--" + e.getKey() + " isn't a colour the app uses. Did you mean --" + cssNameFor(hint) + "?");
            }
        }
        String name = meta.get("name");
        String safe = safeName(name);
        if (name != null && !name.equals(safe)) {
            warnings.add("name: only letters, digits, spaces and - _ . , ' ( ) & + ! # are shown (up to " + MAX_NAME + " characters).");
        }
        String base = id(meta.get("base"), "base", warnings);
        String toggle = id(meta.get("toggle"), "toggle", warnings);
        return new Parsed(safe, base, toggle, colors, warnings);
    }

    /** base and toggle must be theme ids; anything else (paths, dots, spaces) is dropped. */
    private static String id(String raw, String key, List<String> warnings) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.strip().toLowerCase(Locale.ROOT);
        if (isId(v)) return v;
        warnings.add(key + ": \"" + snippet(raw) + "\" isn't a theme name (lowercase letters, digits, - and _), so it was ignored.");
        return null;
    }

    private interface Decl {
        void accept(String key, String value);
    }

    private static void declarations(String body, List<String> warnings, Decl out) {
        for (String part : body.split(";")) {
            if (part.isBlank()) continue;
            int colon = part.indexOf(':');
            if (colon < 0) {
                warnings.add("Missing ':' in \"" + snippet(part) + "\".");
                continue;
            }
            out.accept(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
        }
    }

    private static boolean usedAsVar(String name, Map<String, String> vars) {
        String ref = "var(--" + name + ")";
        for (String v : vars.values()) if (v.replace(" ", "").contains(ref)) return true;
        return false;
    }

    private static Integer resolve(String name, Map<String, String> vars, List<String> warnings, List<String> chain) {
        if (chain.contains(name)) {
            warnings.add("--" + name + " refers to itself through var(): " + String.join(" → ", chain) + " → " + name + ".");
            return null;
        }
        String v = vars.get(name);
        if (v == null) {
            warnings.add("var(--" + name + ") is used but never defined.");
            return null;
        }
        java.util.regex.Matcher ref = VAR.matcher(v.strip());
        if (ref.matches()) {
            if (chain.size() >= MAX_VAR_DEPTH) {
                warnings.add("--" + name + ": var() references nest more than " + MAX_VAR_DEPTH + " deep.");
                return null;
            }
            chain.add(name);
            return resolve(ref.group(1), vars, warnings, chain);
        }
        Integer c = color(v);
        if (c == null) warnings.add("--" + name + ": \"" + snippet(v) + "\" isn't a colour (use #RRGGBB, rgb() or rgba()).");
        return c;
    }

    /** Parses one colour value to ARGB, or null. Only the exact shapes in {@link #HEX} and {@link #RGB} are accepted. */
    static Integer color(String raw) {
        String v = raw.strip().toLowerCase(Locale.ROOT);
        if (HEX.matcher(v).matches()) {
            String h = v.substring(1);
            if (h.length() <= 4) {
                StringBuilder b = new StringBuilder();
                for (char ch : h.toCharArray()) b.append(ch).append(ch);
                h = b.toString();
            }
            long n = Long.parseLong(h, 16);
            return h.length() == 6 ? (int) (0xFF000000L | n) : (int) (((n & 0xFF) << 24) | (n >>> 8));
        }
        java.util.regex.Matcher m = RGB.matcher(v);
        if (!m.matches()) return null;
        int r = Integer.parseInt(m.group(1));
        int g = Integer.parseInt(m.group(2));
        int bl = Integer.parseInt(m.group(3));
        if (r > 255 || g > 255 || bl > 255) return null;
        int a = 255;
        String alpha = m.group(4);
        if (alpha != null) {
            double d = alpha.endsWith("%") ? Integer.parseInt(alpha.substring(0, alpha.length() - 1)) / 100.0 : Double.parseDouble(alpha);
            if (d > 1) return null;
            a = (int) Math.round(d * 255);
        }
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }

    private static String stripComments(String text, List<String> warnings) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("/*", i);
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            int end = text.indexOf("*/", start + 2);
            if (end < 0) {
                warnings.add("A comment opened with /* is never closed.");
                break;
            }
            i = end + 2;
        }
        return out.toString();
    }

    private static String unquote(String v) {
        String s = v.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String snippet(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 40 ? t.substring(0, 39) + "…" : t;
    }

    /** The known name within two edits of a typo, if any (case-insensitive). */
    private static String closest(String name, Set<String> known) {
        String best = null;
        int bestD = 3;
        for (String k : known) {
            int d = distance(name.toLowerCase(Locale.ROOT), k.toLowerCase(Locale.ROOT));
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Writes a complete theme file: every colour, grouped and commented, with the given values. */
    public static String write(String name, boolean dark, java.util.function.Function<String, Integer> valueOf) {
        StringBuilder b = new StringBuilder();
        b.append("/*\n * ").append(name).append(" — a REST Client theme.\n")
                .append(" *\n * Put this file in ~/.restclient/themes/ and pick it in View › Appearance or Settings.\n")
                .append(" * Saving the file updates the app straight away. Delete any line to fall back to the base theme's\n")
                .append(" * colour. Colours: #RGB, #RRGGBB, #RRGGBBAA, rgb(), rgba() or var(--your-own-name).\n")
                .append(" * Settings › General lists anything that can't be read and any text that fails WCAG AA contrast.\n */\n\n");
        b.append("@theme {\n  name: \"").append(name.replace("\"", "'")).append("\";\n  base: ")
                .append(dark ? "dark" : "light").append(";\n}\n\n:root {\n");
        boolean first = true;
        for (Map.Entry<String, List<String>> g : GROUPS.entrySet()) {
            if (!first) b.append('\n');
            first = false;
            b.append("  /* ").append(g.getKey()).append(" */\n");
            for (String t : g.getValue()) {
                String decl = "  --" + cssNameFor(t) + ": " + hex(valueOf.apply(t)) + ";";
                b.append(String.format("%-36s /* %s */", decl, NOTES.get(t))).append('\n');
            }
        }
        b.append("}\n");
        return b.toString();
    }

    static String hex(int argb) {
        int a = argb >>> 24;
        return a == 0xFF ? String.format("#%06X", argb & 0xFFFFFF) : String.format("#%06X%02X", argb & 0xFFFFFF, a);
    }
}
