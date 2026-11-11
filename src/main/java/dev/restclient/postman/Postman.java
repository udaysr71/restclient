package dev.restclient.postman;

import dev.restclient.json.Json;
import dev.restclient.json.JsonException;
import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.Environment;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Postman Collection (v2.0 and v2.1) and Postman environment files, in both directions.
 *
 * <p>Reading: folders, requests, methods, URLs (string or object form, with disabled query params), headers,
 * raw/JSON/XML/text, urlencoded and GraphQL bodies, Basic/Bearer/API-key auth including auth inherited from
 * folders and the collection, collection variables, descriptions-free names, and "SSL verification off". Anything
 * the app can't represent (scripts, file uploads, other auth types, saved examples…) is listed in
 * {@link Result#notes()} rather than dropped silently.
 *
 * <p>Writing produces v2.1 that Postman imports as-is: the URL in object form so Params show up, bodies with
 * their language, auth, collection variables (secret ones as type "secret") and strictSSL for requests that skip
 * verification. {{variables}} use the same syntax in both apps, so they carry over unchanged.
 *
 * <p>Files are untrusted input: they're parsed by the app's own JSON parser (depth-limited), limited in size,
 * and names are cleaned so they can't contain control characters or start Swing's HTML rendering.
 */
public final class Postman {
    public static final String SCHEMA_21 = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";
    /** Largest file read, in bytes. */
    public static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final int MAX_NAME = 200;

    /** What an import produced, plus anything that couldn't be brought across. */
    public record Result(ApiCollection collection, Environment environment, boolean globals, int requests, int folders,
                         List<String> notes) {
        public boolean isCollection() {
            return collection != null;
        }

        /** A Postman globals file: {@link #environment} holds its variables, to merge into the app's Globals. */
        public boolean isGlobals() {
            return globals;
        }
    }

    public static final class ImportException extends Exception {
        public ImportException(String message) {
            super(message);
        }
    }

    private Postman() {
    }

    // ================================================================== reading

    /** Reads a Postman collection or environment file (the kind is detected). */
    public static Result read(String text) throws ImportException {
        if (text.length() > MAX_BYTES) throw new ImportException("The file is larger than " + MAX_BYTES / (1024 * 1024) + " MB.");
        Object root;
        try {
            root = Json.parse(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (JsonException e) {
            throw new ImportException("This isn't valid JSON: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> m)) throw new ImportException("This isn't a Postman file (expected a JSON object).");
        if (m.get("info") instanceof Map<?, ?> && m.get("item") instanceof List<?>) return readCollection(m);
        // a globals file is an environment file marked with this scope (older ones have no name)
        boolean globals = "globals".equals(str(m.get("_postman_variable_scope")));
        if (m.get("values") instanceof List<?> && (globals || m.containsKey("name"))) return readEnvironment(m, globals);
        if (m.containsKey("requests") && m.containsKey("order")) {
            throw new ImportException("This is a Postman v1 collection. Open it in Postman and export it as Collection v2.1, then import that.");
        }
        throw new ImportException("This doesn't look like a Postman collection or environment.");
    }

    private static final class Reader {
        final Set<String> notes = new LinkedHashSet<>();
        int requests;
        int folders;
        int scripts;
        int files;
        int examples;
        int captures;
        int locals;
        final Set<String> unknownDynamic = new LinkedHashSet<>();
        final Map<String, Integer> unsupportedAuth = new LinkedHashMap<>();

        void note(String s) {
            notes.add(s);
        }

        List<String> finish() {
            List<String> out = new ArrayList<>();
            if (scripts > 0) out.add("Pre-request and test scripts aren't supported; " + scripts + " " + plural(scripts, "script was", "scripts were") + " left out.");
            if (files > 0) out.add(files + " " + plural(files, "request uploads a file", "requests upload files") + " from this computer. The paths come from the imported file: check them in the Body tab before sending.");
            if (locals > 0) out.add(locals + " local " + plural(locals, "variable that a pre-request script set was", "variables that pre-request scripts set were") + " added to the requests' Local variables (pm.variables.set with a fixed value). They're under Params › Local variables.");
            if (captures > 0) out.add(captures + " " + plural(captures, "value that a test script saved was", "values that test scripts saved were") + " turned into " + plural(captures, "a Capture rule", "Capture rules") + " (pm.environment.set and similar). Check them in the Capture tab.");
            if (!unknownDynamic.isEmpty()) out.add("These built-in Postman variables aren't supported and are sent as written: " + String.join(", ", unknownDynamic) + ".");
            if (examples > 0) out.add(examples + " saved " + plural(examples, "example response was", "example responses were") + " left out.");
            unsupportedAuth.forEach((type, n) -> out.add("“" + type + "” auth isn't supported (" + n + " " + plural(n, "request", "requests") + "); set it up by hand in the Auth or Headers tab."));
            out.addAll(notes);
            return out;
        }
    }

    private static Result readCollection(Map<?, ?> m) throws ImportException {
        Reader r = new Reader();
        Map<?, ?> info = (Map<?, ?>) m.get("info");
        String schema = str(info.get("schema"));
        if (!schema.isEmpty() && !schema.contains("v2.0.0") && !schema.contains("v2.1.0")) {
            r.note("The file says it's “" + schema + "”; it was read as Collection v2.1.");
        }
        ApiCollection c = new ApiCollection();
        c.name = cleanName(str(info.get("name")), "Imported collection");
        for (Object o : list(m.get("variable"))) {
            if (!(o instanceof Map<?, ?> v)) continue;
            String key = str(v.get("key"));
            if (key.isEmpty()) key = str(v.get("id"));
            if (key.isEmpty()) continue;
            Variable var = new Variable(key, valueString(v.get("value")));
            var.enabled = !bool(v.get("disabled"));
            var.secret = "secret".equals(str(v.get("type")));
            c.variables.add(var);
        }
        Auth collectionAuth = auth(m.get("auth"), r, null);
        List<KeyValue> collectionLocals = new ArrayList<>();
        r.scripts += scopeScripts(m.get("event"), collectionLocals, r);
        c.items.addAll(items(list(m.get("item")), collectionAuth, collectionLocals, r));
        return new Result(c, null, false, r.requests, r.folders, r.finish());
    }

    private static List<CollectionNode> items(List<?> items, Auth inherited, List<KeyValue> inheritedLocals, Reader r) {
        List<CollectionNode> out = new ArrayList<>();
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> it)) continue;
            if (it.get("item") instanceof List<?> children) {
                r.folders++;
                CollectionNode f = CollectionNode.folder(cleanName(str(it.get("name")), "Folder"));
                Auth folderAuth = it.containsKey("auth") ? auth(it.get("auth"), r, inherited) : inherited;
                // a folder's pre-request script runs before every request in it, so its local variables are theirs too
                List<KeyValue> folderLocals = new ArrayList<>();
                for (KeyValue kv : inheritedLocals) folderLocals.add(kv.copy()); // its own copies: a folder's values stay in the folder
                r.scripts += scopeScripts(it.get("event"), folderLocals, r);
                f.children.addAll(items(children, folderAuth, folderLocals, r));
                out.add(f);
            } else if (it.containsKey("request")) {
                r.requests++;
                r.examples += list(it.get("response")).size();
                RequestDef req = request(it.get("request"), inherited, r);
                for (KeyValue kv : inheritedLocals) putLocal(req.locals, kv.key, kv.value);
                r.scripts += requestScripts(it.get("event"), req, r);
                req.name = cleanName(str(it.get("name")), req.url.isEmpty() ? "Request" : req.url);
                if (it.get("protocolProfileBehavior") instanceof Map<?, ?> ppb && Boolean.FALSE.equals(ppb.get("strictSSL"))) {
                    req.insecure = Boolean.TRUE;
                }
                out.add(CollectionNode.request(req));
            }
        }
        return out;
    }

    private static RequestDef request(Object o, Auth inherited, Reader r) {
        RequestDef d = new RequestDef();
        d.id = UUID.randomUUID().toString();
        if (o instanceof String s) { // v2 allows "request": "https://…"
            d.url = s;
            return d;
        }
        if (!(o instanceof Map<?, ?> m)) return d;
        String method = str(m.get("method")).toUpperCase(Locale.ROOT);
        try {
            d.method = method.isEmpty() ? HttpMethod.GET : HttpMethod.valueOf(method);
        } catch (IllegalArgumentException e) {
            r.note("The method “" + method + "” isn't supported; those requests were imported as GET.");
            d.method = HttpMethod.GET;
        }
        url(m.get("url"), d);
        for (Object h : headerList(m.get("header"))) {
            if (h instanceof Map<?, ?> hm && !str(hm.get("key")).isEmpty()) {
                d.headers.add(new KeyValue(str(hm.get("key")), valueString(hm.get("value")), !bool(hm.get("disabled"))));
            }
        }
        body(m.get("body"), d, r);
        Auth a = m.containsKey("auth") ? auth(m.get("auth"), r, inherited) : inherited;
        applyAuth(a, d);
        List<String> texts = new ArrayList<>(List.of(d.url, d.body == null ? "" : d.body));
        for (KeyValue h : d.headers) texts.add(h.value);
        for (KeyValue f : d.form) texts.add(f.value);
        for (KeyValue f : d.multipart) texts.add(f.value);
        for (String t : texts) {
            for (dev.restclient.vars.VariableResolver.Token tok : dev.restclient.vars.VariableResolver.tokens(t)) {
                if (tok.name().startsWith("$") && !dev.restclient.vars.DynamicVars.isDynamic(tok.name()) && r.unknownDynamic.size() < 20) {
                    r.unknownDynamic.add("{{" + tok.name() + "}}");
                }
            }
        }
        return d;
    }

    /** Postman allows "header" as a list of objects or as one "Name: value\n…" string. */
    private static List<?> headerList(Object o) {
        if (o instanceof String s) {
            List<Object> out = new ArrayList<>();
            for (String line : s.split("\\r?\\n")) {
                int c = line.indexOf(':');
                if (c > 0) out.add(Map.of("key", line.substring(0, c).strip(), "value", line.substring(c + 1).strip()));
            }
            return out;
        }
        return list(o);
    }

    private static void url(Object o, RequestDef d) {
        if (o instanceof String s) {
            d.url = s;
            return;
        }
        if (!(o instanceof Map<?, ?> u)) return;
        String raw = str(u.get("raw"));
        List<?> query = list(u.get("query"));
        if (raw.isEmpty()) raw = buildRaw(u, query);
        d.url = raw;
        for (Object q : query) {
            if (q instanceof Map<?, ?> qm && bool(qm.get("disabled"))) {
                d.disabledParams.add(new KeyValue(str(qm.get("key")), valueString(qm.get("value")), false));
            }
        }
        // :name path variables: Postman fills them from url.variable; we turn them into the value directly
        for (Object v : list(u.get("variable"))) {
            if (v instanceof Map<?, ?> vm && !str(vm.get("key")).isEmpty() && vm.get("value") != null) {
                d.url = d.url.replace(":" + str(vm.get("key")), valueString(vm.get("value")));
            }
        }
    }

    private static String buildRaw(Map<?, ?> u, List<?> query) {
        StringBuilder b = new StringBuilder();
        String protocol = str(u.get("protocol"));
        if (!protocol.isEmpty()) b.append(protocol).append("://");
        Object host = u.get("host");
        if (host instanceof List<?> hl) b.append(String.join(".", hl.stream().map(Postman::str).toList()));
        else b.append(str(host));
        String port = str(u.get("port"));
        if (!port.isEmpty()) b.append(':').append(port);
        Object path = u.get("path");
        if (path instanceof List<?> pl) {
            for (Object seg : pl) b.append('/').append(seg instanceof Map<?, ?> sm ? str(sm.get("value")) : str(seg));
        } else if (!str(path).isEmpty()) {
            String p = str(path);
            b.append(p.startsWith("/") ? p : "/" + p);
        }
        String sep = "?";
        for (Object q : query) {
            if (q instanceof Map<?, ?> qm && !bool(qm.get("disabled"))) {
                b.append(sep).append(str(qm.get("key")));
                if (qm.get("value") != null) b.append('=').append(valueString(qm.get("value")));
                sep = "&";
            }
        }
        return b.toString();
    }

    private static void body(Object o, RequestDef d, Reader r) {
        if (!(o instanceof Map<?, ?> b)) return;
        if (bool(b.get("disabled"))) return;
        String mode = str(b.get("mode"));
        switch (mode) {
            case "raw" -> {
                d.body = str(b.get("raw"));
                String lang = "";
                if (b.get("options") instanceof Map<?, ?> opt && opt.get("raw") instanceof Map<?, ?> raw) lang = str(raw.get("language"));
                d.bodyType = switch (lang) {
                    case "json" -> BodyType.JSON;
                    case "xml" -> BodyType.XML;
                    case "text", "html", "javascript" -> BodyType.TEXT;
                    default -> guessRaw(d);
                };
            }
            case "urlencoded" -> {
                d.bodyType = BodyType.FORM;
                for (Object p : list(b.get("urlencoded"))) {
                    if (p instanceof Map<?, ?> pm && !str(pm.get("key")).isEmpty()) {
                        d.form.add(new KeyValue(str(pm.get("key")), valueString(pm.get("value")), !bool(pm.get("disabled"))));
                    }
                }
            }
            case "formdata" -> {
                d.bodyType = BodyType.MULTIPART;
                boolean uploads = false;
                for (Object p : list(b.get("formdata"))) {
                    if (!(p instanceof Map<?, ?> pm) || str(pm.get("key")).isEmpty()) continue;
                    if ("file".equals(str(pm.get("type")))) {
                        // src is a path, or a list of paths for several files under one name
                        List<?> srcs = pm.get("src") instanceof List<?> l ? l : pm.get("src") == null ? List.of("") : List.of(pm.get("src"));
                        if (srcs.isEmpty()) srcs = List.of("");
                        for (Object src : srcs) d.multipart.add(KeyValue.file(str(pm.get("key")), str(src), !bool(pm.get("disabled"))));
                        uploads = true;
                    } else {
                        d.multipart.add(new KeyValue(str(pm.get("key")), valueString(pm.get("value")), !bool(pm.get("disabled"))));
                    }
                }
                if (uploads) r.files++;
            }
            case "graphql" -> {
                // a GraphQL request is a JSON POST of {"query", "variables"}
                if (b.get("graphql") instanceof Map<?, ?> g) {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("query", str(g.get("query")));
                    String vars = str(g.get("variables")).strip();
                    if (!vars.isEmpty()) {
                        try {
                            json.put("variables", Json.parse(vars));
                        } catch (JsonException e) {
                            json.put("variables", vars);
                        }
                    }
                    d.body = Json.write(json, true).strip();
                    d.bodyType = BodyType.JSON;
                }
            }
            case "file" -> {
                d.bodyType = BodyType.BINARY;
                d.bodyFile = b.get("file") instanceof Map<?, ?> fm ? str(fm.get("src")) : "";
                r.files++;
            }
            default -> {
            }
        }
    }

    private static BodyType guessRaw(RequestDef d) {
        for (KeyValue h : d.headers) {
            if (h.key.equalsIgnoreCase("Content-Type")) {
                String v = h.value.toLowerCase(Locale.ROOT);
                if (v.contains("json")) return BodyType.JSON;
                if (v.contains("xml")) return BodyType.XML;
                return BodyType.TEXT;
            }
        }
        String t = d.body.strip();
        if (t.startsWith("{") || t.startsWith("[")) return BodyType.JSON;
        if (t.startsWith("<")) return BodyType.XML;
        return t.isEmpty() ? BodyType.NONE : BodyType.TEXT;
    }

    /**
     * Reads a Postman auth block. Returns the auth to use, or {@code inherited} for "inherit". API-key auth is
     * returned as a pseudo-Auth that {@link #applyAuth} turns into a header or query parameter.
     */
    private static Auth auth(Object o, Reader r, Auth inherited) {
        if (o == null) return inherited;
        if (!(o instanceof Map<?, ?> m)) return inherited;
        String type = str(m.get("type"));
        Map<String, String> p = params(m.get(type));
        Auth a = new Auth();
        switch (type) {
            case "noauth" -> a.type = Auth.Type.NONE;
            case "inherit" -> {
                return inherited;
            }
            case "basic" -> {
                a.type = Auth.Type.BASIC;
                a.username = p.getOrDefault("username", "");
                a.password = p.getOrDefault("password", "");
            }
            case "bearer" -> {
                a.type = Auth.Type.BEARER;
                a.token = p.getOrDefault("token", "");
            }
            case "apikey" -> {
                ApiKey k = new ApiKey();
                k.type = Auth.Type.NONE;
                k.key = p.getOrDefault("key", "");
                k.value = p.getOrDefault("value", "");
                k.inQuery = "query".equals(p.get("in"));
                return k;
            }
            default -> {
                if (!type.isEmpty()) r.unsupportedAuth.merge(type, 1, Integer::sum);
                a.type = Auth.Type.NONE;
            }
        }
        return a;
    }

    /** Postman's API-key auth, carried until it's applied to a request as a header or query parameter. */
    private static final class ApiKey extends Auth {
        String key;
        String value;
        boolean inQuery;
    }

    private static void applyAuth(Auth a, RequestDef d) {
        if (a == null) return;
        if (a instanceof ApiKey k) {
            if (k.key.isEmpty()) return;
            if (k.inQuery) d.url = d.url + (d.url.contains("?") ? "&" : "?") + k.key + "=" + k.value;
            else d.headers.add(new KeyValue(k.key, k.value, true));
            return;
        }
        d.auth = new Auth();
        d.auth.type = a.type;
        d.auth.username = a.username;
        d.auth.password = a.password;
        d.auth.token = a.token;
    }

    /** v2.1 stores auth parameters as [{key, value}], v2.0 as {key: value}. */
    private static Map<String, String> params(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof List<?> l) {
            for (Object x : l) if (x instanceof Map<?, ?> m) out.put(str(m.get("key")), valueString(m.get("value")));
        } else if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(str(k), valueString(v)));
        }
        return out;
    }

    private static final String JS_PATH = "((?:\\.[A-Za-z_$][\\w$]*|\\[\\d+\\]|\\.at\\(-?\\d+\\)|\\[\"[^\"\\\\]*\"\\]|\\['[^'\\\\]*'\\])*)";
    private static final Pattern JS_ALIAS = Pattern.compile(
            "(?:var|let|const)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:pm\\.response\\.json\\(\\)|JSON\\.parse\\(\\s*responseBody\\s*\\))\\s*;?");
    private static final Pattern JS_SET = Pattern.compile(
            "(?:pm\\.(?:environment|globals|collectionVariables|variables)\\.set|postman\\.set(?:Environment|Global)Variable)"
                    + "\\(\\s*(?:\"([^\"\\\\]*)\"|'([^'\\\\]*)')\\s*,\\s*(.+?)\\s*\\)\\s*;?");
    private static final Pattern JS_HEADER = Pattern.compile(
            "pm\\.response\\.headers\\.get\\(\\s*(?:\"([^\"\\\\]*)\"|'([^'\\\\]*)')\\s*\\)");
    private static final Pattern JS_COOKIE = Pattern.compile(
            "pm\\.cookies\\.get\\(\\s*(?:\"([^\"\\\\]*)\"|'([^'\\\\]*)')\\s*\\)");
    private static final Pattern JS_SEGMENT = Pattern.compile(
            "\\.at\\((-?\\d+)\\)|\\.([A-Za-z_$][\\w$]*)|\\[(\\d+)\\]|\\[\"([^\"\\\\]*)\"\\]|\\['([^'\\\\]*)'\\]");

    /**
     * Counts a request's scripts that are left out. A test script is never run; the one thing read out of it is
     * the common "save a value from the response" line (pm.environment.set("token", pm.response.json().token)),
     * which becomes a Capture rule. A test script made only of such lines isn't counted as left out.
     */
    private static int requestScripts(Object events, RequestDef req, Reader r) {
        int left = 0;
        for (Object e : list(events)) {
            if (!(e instanceof Map<?, ?> em) || !(em.get("script") instanceof Map<?, ?> sc)) continue;
            Object exec = sc.get("exec");
            List<String> lines = new ArrayList<>();
            if (exec instanceof List<?> l) for (Object x : l) lines.addAll(List.of(str(x).split("\\r?\\n")));
            else if (exec instanceof String str) lines.addAll(List.of(str.split("\\r?\\n")));
            if (lines.stream().allMatch(String::isBlank)) continue;
            if (lines.size() > 2000) {
                left++;
                continue;
            }
            if ("prerequest".equals(str(em.get("listen")))) {
                if (!localsFrom(lines, req.locals, r)) left++;
                continue;
            }
            if (!"test".equals(str(em.get("listen")))) {
                left++;
                continue;
            }
            boolean all = true;
            java.util.Set<String> aliases = new java.util.HashSet<>();
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("//")) continue;
                if (line.length() > 2000) {
                    all = false;
                    continue;
                }
                Matcher alias = JS_ALIAS.matcher(line);
                if (alias.matches()) {
                    aliases.add(alias.group(1));
                    continue;
                }
                Matcher set = JS_SET.matcher(line);
                String source = set.matches() ? captureSource(set.group(3), aliases) : null;
                String name = set.matches() ? (set.group(1) != null ? set.group(1) : set.group(2)) : null;
                if (source != null && dev.restclient.capture.Capture.validName(name) && req.captures.size() < 200) {
                    req.captures.add(new KeyValue(name, source));
                    r.captures++;
                } else {
                    all = false;
                }
            }
            if (!all) left++;
        }
        return left;
    }

    private static final Pattern JS_LOCAL = Pattern.compile("pm\\.variables\\.set\\(\\s*(\"[^\"\\\\]*\"|'[^'\\\\]*')\\s*,\\s*(.+?)\\s*\\)\\s*;?");
    private static final Pattern JS_GET = Pattern.compile(
            "pm\\.(?:variables|environment|globals|collectionVariables)\\.get\\(\\s*(\"[^\"\\\\]*\"|'[^'\\\\]*')\\s*\\)");
    private static final Pattern JS_REPLACE_IN = Pattern.compile("pm\\.variables\\.replaceIn\\(\\s*(.+?)\\s*\\)");

    /**
     * A folder's or the collection's scripts. A pre-request script that only sets local variables to fixed values
     * is turned into local variables for the requests beneath; any other script is counted as left out.
     */
    private static int scopeScripts(Object events, List<KeyValue> locals, Reader r) {
        int left = 0;
        for (Object e : list(events)) {
            if (!(e instanceof Map<?, ?> em) || !(em.get("script") instanceof Map<?, ?> sc)) continue;
            Object exec = sc.get("exec");
            List<String> lines = new ArrayList<>();
            if (exec instanceof List<?> l) for (Object x : l) lines.addAll(List.of(str(x).split("\\r?\\n")));
            else if (exec instanceof String str) lines.addAll(List.of(str.split("\\r?\\n")));
            if (lines.stream().allMatch(String::isBlank)) continue;
            if (!"prerequest".equals(str(em.get("listen"))) || lines.size() > 2000 || !localsFrom(lines, locals, r)) left++;
        }
        return left;
    }

    /**
     * Reads the lines of a pre-request script that set a local variable to a fixed value:
     * {@code pm.variables.set("name", "text")}, a number, true/false, {@code pm.variables.replaceIn("{{$guid}}")}
     * or another variable ({@code pm.environment.get("host")}). Nothing is run. Returns true when the whole
     * script was such lines (and comments), so nothing of it is lost.
     */
    private static boolean localsFrom(List<String> lines, List<KeyValue> locals, Reader r) {
        boolean all = true;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher m = line.length() > 4000 ? null : JS_LOCAL.matcher(line);
            String name = m != null && m.matches() ? m.group(1).substring(1, m.group(1).length() - 1) : null;
            String value = name == null ? null : localValue(m.group(2));
            if (value != null && dev.restclient.capture.Capture.validName(name) && locals.size() < 200) {
                putLocal(locals, name, value);
                r.locals++;
            } else {
                all = false;
            }
        }
        return all;
    }

    private static void putLocal(List<KeyValue> locals, String name, String value) {
        for (KeyValue kv : locals) {
            if (kv.key.equals(name)) { // a later line, or a request's own script, wins
                kv.value = value;
                return;
            }
        }
        locals.add(new KeyValue(name, value));
    }

    /** The text a local variable gets from a JavaScript expression, or null if the expression isn't a fixed value. */
    private static String localValue(String expr) {
        String e = expr.strip();
        if (e.matches("-?\\d{1,18}(\\.\\d{1,18})?|true|false")) return e;
        Matcher get = JS_GET.matcher(e);
        if (get.matches()) {
            String name = get.group(1).substring(1, get.group(1).length() - 1);
            return dev.restclient.capture.Capture.validName(name) || dev.restclient.vars.DynamicVars.isDynamic(name) ? "{{" + name + "}}" : null;
        }
        Matcher in = JS_REPLACE_IN.matcher(e);
        if (in.matches()) e = in.group(1).strip(); // our variables resolve inside values anyway
        return jsString(e);
    }

    /** Reads a whole single- or double-quoted JavaScript string literal; null for anything else. */
    static String jsString(String lit) {
        if (lit.length() < 2) return null;
        char q = lit.charAt(0);
        if ((q != '"' && q != '\'') || lit.charAt(lit.length() - 1) != q) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < lit.length() - 1; i++) {
            char c = lit.charAt(i);
            if (c == q) return null; // the literal ends early: this is an expression, not one string
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= lit.length() - 1) return null;
            char x = lit.charAt(i);
            switch (x) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '\\', '"', '\'', '/', '`' -> out.append(x);
                case 'u' -> {
                    if (i + 4 >= lit.length() || !lit.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) return null;
                    out.append((char) Integer.parseInt(lit.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> {
                    return null;
                }
            }
        }
        return out.toString();
    }

    /** One line per local variable, in the shape {@link #localsFrom} reads back. Every value is a string literal. */
    private static List<Object> localsScript(List<KeyValue> locals) {
        List<Object> lines = new ArrayList<>();
        for (KeyValue kv : locals) {
            if (kv.key == null || !dev.restclient.capture.Capture.validName(kv.key.trim())) continue;
            StringBuilder lit = new StringBuilder();
            Json.quote(lit, kv.value == null ? "" : kv.value);
            // replaceIn makes Postman fill in {{variables}} inside the value, as this app does
            String value = kv.value != null && kv.value.contains("{{") ? "pm.variables.replaceIn(" + lit + ")" : lit.toString();
            lines.add((kv.enabled ? "" : "// ") + "pm.variables.set(" + js(kv.key.trim()) + ", " + value + ");");
        }
        return lines;
    }

    private static Map<String, Object> event(String listen, List<Object> lines) {
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("type", "text/javascript");
        sc.put("exec", lines);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("listen", listen);
        ev.put("script", sc);
        return ev;
    }

    /** The Capture source for a JavaScript expression a test script saves, or null if it isn't one we know. */
    private static String captureSource(String expr, java.util.Set<String> aliases) {
        String e = expr.strip();
        Matcher wrapped = Pattern.compile("String\\((.*)\\)").matcher(e);
        if (wrapped.matches()) e = wrapped.group(1).strip();
        if (e.equals("pm.response.code") || e.equals("responseCode.code")) return "status";
        if (e.equals("pm.response.text()") || e.equals("responseBody")) return "body";
        Matcher h = JS_HEADER.matcher(e);
        if (h.matches()) return "header." + (h.group(1) != null ? h.group(1) : h.group(2));
        Matcher c = JS_COOKIE.matcher(e);
        if (c.matches()) return "cookie." + (c.group(1) != null ? c.group(1) : c.group(2));
        String rest = null;
        if (e.startsWith("pm.response.json()")) {
            rest = e.substring("pm.response.json()".length());
        } else {
            Matcher id = Pattern.compile("([A-Za-z_$][\\w$]*)(.*)").matcher(e);
            if (id.matches() && aliases.contains(id.group(1))) rest = id.group(2);
        }
        if (rest == null || rest.isEmpty() || !rest.matches(JS_PATH)) return null;
        List<Object> path = new ArrayList<>();
        Matcher seg = JS_SEGMENT.matcher(rest);
        while (seg.find()) {
            try {
                if (seg.group(1) != null) path.add(Integer.parseInt(seg.group(1)));
                else if (seg.group(2) != null) path.add(seg.group(2));
                else if (seg.group(3) != null) path.add(Integer.parseInt(seg.group(3)));
                else path.add(seg.group(4) != null ? seg.group(4) : seg.group(5));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        for (Object o : path) if (o instanceof String k && (k.isEmpty() || k.contains("\"") || k.contains("]"))) return null;
        return dev.restclient.capture.Capture.pathSource(path);
    }

    /** One line of JavaScript per rule, in the shape {@link #requestScripts} reads back. Every value is a literal. */
    private static List<Object> captureScript(List<KeyValue> captures) {
        List<Object> lines = new ArrayList<>();
        for (KeyValue c : captures) {
            if (c.key == null || !dev.restclient.capture.Capture.validName(c.key.trim())) continue;
            String src = c.value == null ? "" : c.value.trim();
            String lower = src.toLowerCase(Locale.ROOT);
            String expr;
            if (lower.equals("status")) expr = "String(pm.response.code)";
            else if (lower.equals("body")) expr = "pm.response.text()";
            else if (lower.matches("header[.:].+")) expr = "pm.response.headers.get(" + js(src.substring(7).trim()) + ")";
            else if (lower.matches("cookie[.:].+")) expr = "pm.cookies.get(" + js(src.substring(7).trim()) + ")";
            else {
                List<Object> path = dev.restclient.capture.Capture.jsonPath(src);
                if (path == null) continue;
                StringBuilder sb = new StringBuilder("pm.response.json()");
                for (Object seg : path) {
                    if (seg instanceof Integer i) sb.append(i < 0 ? ".at(" + i + ")" : "[" + i + "]");
                    else sb.append('[').append(js((String) seg)).append(']');
                }
                expr = sb.toString();
            }
            lines.add((c.enabled ? "" : "// ") + "pm.environment.set(" + js(c.key.trim()) + ", " + expr + ");");
        }
        return lines;
    }

    private static String js(String s) {
        StringBuilder sb = new StringBuilder();
        Json.quote(sb, s.replace("\\", "").replace("\"", "")); // the reader takes neither inside a literal
        return sb.toString();
    }

    private static int countScripts(Object events) {
        int n = 0;
        for (Object e : list(events)) {
            if (e instanceof Map<?, ?> em && em.get("script") instanceof Map<?, ?> s) {
                Object exec = s.get("exec");
                boolean empty = exec == null || (exec instanceof List<?> l && l.stream().allMatch(x -> str(x).isBlank()))
                        || (exec instanceof String str && str.isBlank());
                if (!empty) n++;
            }
        }
        return n;
    }

    private static Result readEnvironment(Map<?, ?> m, boolean globals) {
        Environment e = new Environment();
        e.name = cleanName(str(m.get("name")), "Imported environment");
        for (Object o : list(m.get("values"))) {
            if (!(o instanceof Map<?, ?> v) || str(v.get("key")).isEmpty()) continue;
            Variable var = new Variable(str(v.get("key")), valueString(v.get("value")));
            var.enabled = v.get("enabled") == null || bool(v.get("enabled"));
            var.secret = "secret".equals(str(v.get("type")));
            e.variables.add(var);
        }
        return new Result(null, e, globals, 0, 0, List.of());
    }

    // ================================================================== writing

    /** The collection as Postman Collection v2.1 JSON. */
    public static String writeCollection(ApiCollection c) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("_postman_id", c.id);
        info.put("name", c.name);
        info.put("schema", SCHEMA_21);
        root.put("info", info);
        root.put("item", writeItems(c.items));
        if (!c.variables.isEmpty()) {
            List<Object> vars = new ArrayList<>();
            for (Variable v : c.variables) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", v.key);
                m.put("value", v.value);
                m.put("type", v.secret ? "secret" : "string");
                if (!v.enabled) m.put("disabled", true);
                vars.add(m);
            }
            root.put("variable", vars);
        }
        return Json.write(root, true);
    }

    private static List<Object> writeItems(List<CollectionNode> nodes) {
        List<Object> out = new ArrayList<>();
        for (CollectionNode n : nodes) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("name", n.name);
            if (n.folder) {
                it.put("item", writeItems(n.children));
            } else if (n.request != null) {
                RequestDef r = n.request;
                if (Boolean.TRUE.equals(r.insecure)) it.put("protocolProfileBehavior", Map.of("strictSSL", false));
                List<Object> events = new ArrayList<>();
                List<Object> pre = localsScript(r.locals);
                if (!pre.isEmpty()) events.add(event("prerequest", pre)); // Postman's locals are set by a script
                List<Object> script = captureScript(r.captures);
                if (!script.isEmpty()) events.add(event("test", script));
                if (!events.isEmpty()) it.put("event", events);
                it.put("request", writeRequest(r));
                it.put("response", List.of());
            } else {
                continue;
            }
            out.add(it);
        }
        return out;
    }

    private static Map<String, Object> writeRequest(RequestDef r) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", r.method.name());
        List<Object> headers = new ArrayList<>();
        for (KeyValue h : r.headers) {
            if (h.key.isEmpty()) continue;
            headers.add(kv(h, "text"));
        }
        req.put("header", headers);
        Map<String, Object> body = writeBody(r);
        if (body != null) req.put("body", body);
        req.put("url", writeUrl(r));
        Map<String, Object> auth = writeAuth(r.auth);
        if (auth != null) req.put("auth", auth);
        return req;
    }

    private static Map<String, Object> kv(KeyValue h, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", h.key);
        m.put("value", h.value);
        if (type != null) m.put("type", type);
        if (!h.enabled) m.put("disabled", true);
        return m;
    }

    private static Map<String, Object> writeBody(RequestDef r) {
        Map<String, Object> b = new LinkedHashMap<>();
        switch (r.bodyType) {
            case NONE -> {
                return null;
            }
            case FORM -> {
                b.put("mode", "urlencoded");
                List<Object> items = new ArrayList<>();
                for (KeyValue kv : r.form) if (!kv.key.isEmpty()) items.add(kv(kv, "text"));
                b.put("urlencoded", items);
            }
            case MULTIPART -> {
                b.put("mode", "formdata");
                List<Object> items = new ArrayList<>();
                for (KeyValue kv : r.multipart) {
                    if (kv.key.isEmpty()) continue;
                    if (!kv.file) {
                        items.add(kv(kv, "text"));
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", kv.key);
                    m.put("type", "file");
                    m.put("src", kv.value);
                    if (!kv.enabled) m.put("disabled", true);
                    items.add(m);
                }
                b.put("formdata", items);
            }
            case BINARY -> {
                b.put("mode", "file");
                b.put("file", Map.of("src", r.bodyFile == null ? "" : r.bodyFile));
            }
            default -> {
                b.put("mode", "raw");
                b.put("raw", r.body == null ? "" : r.body);
                String lang = switch (r.bodyType) {
                    case JSON -> "json";
                    case XML -> "xml";
                    default -> "text";
                };
                b.put("options", Map.of("raw", Map.of("language", lang)));
            }
        }
        return b;
    }

    /** The URL in Postman's object form (raw plus parts), so Postman shows Params and path segments. */
    private static Map<String, Object> writeUrl(RequestDef r) {
        Map<String, Object> u = new LinkedHashMap<>();
        String raw = r.url == null ? "" : r.url;
        u.put("raw", raw);
        String rest = raw;
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int scheme = rest.indexOf("://");
        if (scheme > 0 && !rest.substring(0, scheme).contains("{{")) {
            u.put("protocol", rest.substring(0, scheme));
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash + 1) : "";
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.substring(colon + 1).matches("\\d+")) {
            u.put("port", hostPort.substring(colon + 1));
            hostPort = hostPort.substring(0, colon);
        }
        if (!hostPort.isEmpty()) {
            u.put("host", hostPort.contains("{{") ? List.of(hostPort) : List.of(hostPort.split("\\.")));
        }
        if (!path.isEmpty()) u.put("path", List.of(path.split("/", -1)));
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isEmpty()) {
            for (String part : query.split("&")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", eq >= 0 ? part.substring(0, eq) : part);
                m.put("value", eq >= 0 ? part.substring(eq + 1) : null);
                params.add(m);
            }
        }
        for (KeyValue kv : r.disabledParams) params.add(kv(new KeyValue(kv.key, kv.value, false), null));
        if (!params.isEmpty()) u.put("query", params);
        return u;
    }

    private static Map<String, Object> writeAuth(Auth a) {
        if (a == null || a.type == Auth.Type.NONE) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (a.type == Auth.Type.BASIC) {
            m.put("type", "basic");
            m.put("basic", List.of(param("username", a.username), param("password", a.password)));
        } else {
            m.put("type", "bearer");
            m.put("bearer", List.of(param("token", a.token)));
        }
        return m;
    }

    private static Map<String, Object> param(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value == null ? "" : value);
        m.put("type", "string");
        return m;
    }

    /** The environment as a Postman environment file. Secret values are left empty unless {@code includeSecrets}. */
    public static String writeEnvironment(Environment e, boolean includeSecrets) {
        return writeVariables(e, e.name, "environment", includeSecrets);
    }

    /** The app's Globals as a Postman globals file, which Postman imports into its own Globals. */
    public static String writeGlobals(Environment globals, boolean includeSecrets) {
        return writeVariables(globals, "Globals", "globals", includeSecrets);
    }

    private static String writeVariables(Environment e, String name, String scope, boolean includeSecrets) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "globals".equals(scope) ? UUID.nameUUIDFromBytes("restclient-globals".getBytes()).toString() : e.id);
        root.put("name", name);
        List<Object> values = new ArrayList<>();
        for (Variable v : e.variables) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", v.key);
            m.put("value", v.secret && !includeSecrets ? "" : v.value);
            m.put("type", v.secret ? "secret" : "default");
            m.put("enabled", v.enabled);
            values.add(m);
        }
        root.put("values", values);
        root.put("_postman_variable_scope", scope);
        return Json.write(root, true);
    }

    /** A file name like "orders-api.postman_collection.json". */
    public static String fileName(String name, String kind) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return (slug.isEmpty() ? "export" : slug) + ".postman_" + kind + ".json";
    }

    // ================================================================== helpers

    /**
     * Names from a file: control characters removed, at most 200 characters, and never starting with "<html"
     * (which would make Swing render it as HTML, and HTML can load remote images).
     */
    public static String cleanName(String raw, String fallback) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length() && b.length() < MAX_NAME; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) b.appendCodePoint(cp);
            else if (cp == '\t' || cp == '\n') b.append(' ');
        }
        String s = b.toString().strip();
        if (s.toLowerCase(Locale.ROOT).startsWith("<html")) s = s.substring(1);
        return s.isEmpty() ? fallback : s;
    }

    private static String str(Object o) {
        return o == null ? "" : o instanceof String s ? s : String.valueOf(o);
    }

    /** Values can be strings, numbers or booleans in Postman files. */
    private static String valueString(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        if (o instanceof Map<?, ?> || o instanceof List<?>) return Json.write(o, false);
        return String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(o);
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String plural(int n, String one, String many) {
        return n == 1 ? one : many;
    }
}
