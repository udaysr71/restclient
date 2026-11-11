package dev.restclient.openapi;

import dev.restclient.capture.Capture;
import dev.restclient.json.Json;
import dev.restclient.json.JsonException;
import dev.restclient.model.ApiCollection;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.CollectionNode;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.model.Variable;
import dev.restclient.postman.Postman;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an OpenAPI 3.x or Swagger 2.0 description (JSON or YAML) into a collection: one request per operation,
 * grouped into folders by tag, with example bodies and a {@code baseUrl} variable.
 *
 * <p>The file is untrusted input. Only local references ({@code #/…}) are followed, never files or URLs;
 * reference chains, example sizes and the number of operations are capped; names are cleaned the same way
 * imported Postman names are.
 */
public final class OpenApi {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    public static final int MAX_OPERATIONS = 5000;
    private static final int MAX_EXAMPLE_DEPTH = 8;
    private static final int MAX_EXAMPLE_NODES = 3000;
    private static final String[] METHODS = {"get", "post", "put", "patch", "delete", "head", "options"};

    public record Result(ApiCollection collection, int requests, int folders, List<String> notes) {
    }

    public static final class ImportException extends Exception {
        public ImportException(String message) {
            super(message);
        }
    }

    private final Map<?, ?> root;
    private final boolean v2;
    private final Set<String> notes = new LinkedHashSet<>();
    private final ApiCollection collection = new ApiCollection();
    private final Map<String, CollectionNode> folders = new LinkedHashMap<>();
    private final List<CollectionNode> untagged = new ArrayList<>();
    private int requests;
    private int cookies;
    private int budget;

    private OpenApi(Map<?, ?> root, boolean v2) {
        this.root = root;
        this.v2 = v2;
    }

    /** True when the text looks like an OpenAPI or Swagger file rather than, say, a Postman one. */
    public static boolean looksLike(String text) {
        String head = text.length() > 4000 ? text.substring(0, 4000) : text;
        return head.matches("(?s).*(^|[\\s{,])[\"']?(openapi|swagger)[\"']?\\s*:.*");
    }

    public static Result read(String text) throws ImportException {
        if (text.length() > MAX_BYTES) throw new ImportException("The file is larger than " + MAX_BYTES / (1024 * 1024) + " MB.");
        if (text.startsWith("﻿")) text = text.substring(1);
        Object tree;
        String t = text.stripLeading();
        try {
            if (t.startsWith("{")) tree = Json.parse(text);
            else tree = Yaml.parse(text);
        } catch (JsonException e) {
            throw new ImportException("This isn't valid JSON: " + e.getMessage());
        } catch (YamlException e) {
            throw new ImportException("This YAML couldn't be read (" + e.getMessage() + ").");
        }
        if (!(tree instanceof Map<?, ?> m)) throw new ImportException("This isn't an OpenAPI file (expected an object at the top).");
        String openapi = str(m.get("openapi"));
        String swagger = str(m.get("swagger"));
        if (openapi.isEmpty() && swagger.isEmpty()) {
            throw new ImportException("This doesn't look like an OpenAPI or Swagger file: there's no “openapi” or “swagger” version at the top.");
        }
        if (!openapi.isEmpty() && !openapi.startsWith("3.")) throw new ImportException("OpenAPI " + openapi + " isn't supported (3.0 and 3.1 are).");
        if (openapi.isEmpty() && !swagger.startsWith("2")) throw new ImportException("Swagger " + swagger + " isn't supported (2.0 is). Convert it to OpenAPI 3 first.");
        if (!(m.get("paths") instanceof Map<?, ?>)) throw new ImportException("The file describes no paths, so there are no requests to import.");
        try {
            return new OpenApi(m, openapi.isEmpty()).convert();
        } catch (StackOverflowError e) {
            throw new ImportException("The file is nested too deeply to read.");
        }
    }

    // ---- conversion ------------------------------------------------------------------------

    private Result convert() {
        Map<?, ?> info = map(root.get("info"));
        collection.name = Postman.cleanName(str(info.get("title")), "Imported API");
        collection.variables.add(new Variable("baseUrl", baseUrl()));

        // folders in the order the file lists its tags, then in the order they're first used
        for (Object t : list(root.get("tags"))) {
            String name = t instanceof Map<?, ?> tm ? str(tm.get("name")) : str(t);
            if (!name.isBlank()) folder(name);
        }
        Object defaultSecurity = root.get("security");
        outer:
        for (Map.Entry<?, ?> pe : map(root.get("paths")).entrySet()) {
            String path = str(pe.getKey());
            Map<?, ?> item = deref(pe.getValue());
            if (!path.startsWith("/")) continue; // x- extensions
            for (String method : METHODS) {
                if (!(item.get(method) instanceof Map<?, ?> op)) continue;
                if (requests >= MAX_OPERATIONS) {
                    notes.add("Only the first " + MAX_OPERATIONS + " operations were imported.");
                    break outer;
                }
                requests++;
                RequestDef r = operation(method, path, item, op, defaultSecurity);
                List<?> tags = list(op.get("tags"));
                String tag = tags.isEmpty() ? "" : str(tags.get(0));
                if (tag.isBlank()) untagged.add(CollectionNode.request(r));
                else folder(tag).children.add(CollectionNode.request(r));
            }
            if (item.get("trace") != null) notes.add("TRACE operations were left out (the method isn't supported).");
        }
        int used = 0;
        for (CollectionNode f : folders.values()) {
            if (f.children.isEmpty()) continue; // a tag nothing uses
            collection.items.add(f);
            used++;
        }
        collection.items.addAll(untagged);
        if (cookies > 0) notes.add(cookies + " cookie " + (cookies == 1 ? "parameter was" : "parameters were") + " left out; add a Cookie header if the API needs one.");
        return new Result(collection, requests, used, new ArrayList<>(notes));
    }

    private CollectionNode folder(String tag) {
        String name = Postman.cleanName(tag, "Folder");
        return folders.computeIfAbsent(name, CollectionNode::folder);
    }

    private String baseUrl() {
        if (v2) {
            String host = str(root.get("host"));
            if (host.isEmpty()) {
                notes.add("The file names no host. Set baseUrl in the collection's variables before sending.");
                return "";
            }
            List<?> schemes = list(root.get("schemes"));
            String scheme = schemes.contains("https") || schemes.isEmpty() ? "https" : str(schemes.get(0));
            String base = str(root.get("basePath"));
            return scheme + "://" + host + (base.equals("/") ? "" : base);
        }
        List<?> servers = list(root.get("servers"));
        if (servers.isEmpty() || !(servers.get(0) instanceof Map<?, ?> s)) {
            notes.add("The file lists no servers. Set baseUrl in the collection's variables before sending.");
            return "";
        }
        String url = str(s.get("url"));
        for (Map.Entry<?, ?> v : map(s.get("variables")).entrySet()) {
            url = url.replace("{" + v.getKey() + "}", str(map(v.getValue()).get("default")));
        }
        while (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.startsWith("/") || url.isEmpty()) {
            notes.add("The server URL is relative (“" + url + "”). Put the full address in baseUrl, in the collection's variables.");
        }
        if (servers.size() > 1) notes.add("The file lists " + servers.size() + " servers; baseUrl uses the first.");
        return url;
    }

    private RequestDef operation(String method, String path, Map<?, ?> item, Map<?, ?> op, Object defaultSecurity) {
        RequestDef r = new RequestDef();
        r.method = HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
        String name = str(op.get("summary"));
        if (name.isBlank()) name = str(op.get("operationId"));
        r.name = Postman.cleanName(name, r.method.name() + " " + path);

        // parameters: the operation's override the path's when both name the same one
        Map<String, Map<?, ?>> params = new LinkedHashMap<>();
        for (Object p : list(item.get("parameters"))) addParam(params, p);
        for (Object p : list(op.get("parameters"))) addParam(params, p);

        StringBuilder url = new StringBuilder("{{baseUrl}}");
        // {id} becomes {{id}}, a collection variable to fill in
        int i = 0;
        while (i < path.length()) {
            int open = path.indexOf('{', i);
            int close = open < 0 ? -1 : path.indexOf('}', open);
            if (open < 0 || close < 0) {
                url.append(path, i, path.length());
                break;
            }
            String var = path.substring(open + 1, close);
            url.append(path, i, open);
            if (Capture.validName(var)) {
                url.append("{{").append(var).append("}}");
                Map<?, ?> p = params.get("path:" + var);
                ensureVariable(var, p == null ? "" : text(paramExample(p)), false);
            } else {
                url.append(path, open, close + 1);
            }
            i = close + 1;
        }

        List<KeyValue> formFields = new ArrayList<>();
        boolean formHasFile = false;
        Map<?, ?> bodyParam = null;
        String sep = "?";
        for (Map<?, ?> p : params.values()) {
            String in = str(p.get("in"));
            String pname = str(p.get("name"));
            if (pname.isEmpty()) continue;
            boolean required = Boolean.TRUE.equals(p.get("required"));
            String value = text(paramExample(p));
            switch (in) {
                case "query" -> {
                    if (required) {
                        url.append(sep).append(queryPart(pname)).append(value.isEmpty() ? "" : "=" + queryPart(value));
                        sep = "&";
                    } else {
                        r.disabledParams.add(new KeyValue(queryPart(pname), queryPart(value), false));
                    }
                }
                case "header" -> {
                    String l = pname.toLowerCase(Locale.ROOT);
                    if (!l.equals("accept") && !l.equals("content-type") && !l.equals("authorization")) {
                        r.headers.add(new KeyValue(headerName(pname), oneLine(value), required));
                    }
                }
                case "cookie" -> cookies++;
                case "body" -> bodyParam = p;
                case "formData" -> {
                    boolean file = "file".equals(str(p.get("type")));
                    formHasFile |= file;
                    KeyValue kv = new KeyValue(pname, file ? "" : value, true);
                    kv.file = file;
                    formFields.add(kv);
                }
                default -> {
                }
            }
        }

        if (v2) {
            List<?> consumes = op.get("consumes") instanceof List<?> l ? l : list(root.get("consumes"));
            if (bodyParam != null) {
                rawBody(r, mediaFrom(consumes, "application/json"), example(bodyParam.get("schema"), bodyParam.get("x-example")));
            } else if (!formFields.isEmpty()) {
                if (formHasFile || consumes.contains("multipart/form-data")) {
                    r.bodyType = BodyType.MULTIPART;
                    r.multipart = formFields;
                } else {
                    r.bodyType = BodyType.FORM;
                    r.form = formFields;
                }
            }
        } else if (op.get("requestBody") != null) {
            requestBody(r, deref(op.get("requestBody")));
        }

        r.url = url.toString();
        security(r, op.containsKey("security") ? op.get("security") : defaultSecurity);
        return r;
    }

    private void addParam(Map<String, Map<?, ?>> params, Object p) {
        Map<?, ?> m = deref(p);
        if (m.isEmpty() || params.size() >= 200) return;
        params.put(str(m.get("in")) + ":" + str(m.get("name")), m);
    }

    private Object paramExample(Map<?, ?> p) {
        if (p.get("example") != null) return p.get("example");
        if (p.get("x-example") != null) return p.get("x-example");
        for (Object ex : map(p.get("examples")).values()) {
            Object v = deref(ex).get("value");
            if (v != null) return v;
        }
        Map<?, ?> schema = v2 && p.get("schema") == null ? p : deref(p.get("schema"));
        for (String k : new String[]{"example", "default"}) if (schema.get(k) != null) return schema.get(k);
        List<?> en = list(schema.get("enum"));
        if (!en.isEmpty()) return en.get(0);
        Map<?, ?> items = deref(schema.get("items"));
        List<?> itemEnum = list(items.get("enum"));
        return itemEnum.isEmpty() ? null : itemEnum.get(0);
    }

    // ---- bodies ----------------------------------------------------------------------------

    private void requestBody(RequestDef r, Map<?, ?> body) {
        Map<?, ?> content = map(body.get("content"));
        if (content.isEmpty()) return;
        String chosen = null;
        String[] preference = {"json", "x-www-form-urlencoded", "multipart/", "xml", "text/", "octet-stream"};
        for (String want : preference) {
            for (Object k : content.keySet()) {
                if (chosen == null && str(k).toLowerCase(Locale.ROOT).contains(want)) chosen = str(k);
            }
            if (chosen != null) break;
        }
        if (chosen == null) chosen = str(content.keySet().iterator().next());
        Map<?, ?> media = map(content.get(chosen));
        Object given = media.get("example");
        if (given == null) {
            for (Object ex : map(media.get("examples")).values()) {
                given = deref(ex).get("value");
                if (given != null) break;
            }
        }
        String type = chosen.toLowerCase(Locale.ROOT);
        if (type.contains("x-www-form-urlencoded") || type.startsWith("multipart/")) {
            boolean multipart = type.startsWith("multipart/");
            Map<?, ?> schema = flatten(media.get("schema"), new HashSet<>(), 0);
            Map<?, ?> exampleMap = given instanceof Map<?, ?> gm ? gm : Map.of();
            List<KeyValue> rows = new ArrayList<>();
            for (Map.Entry<?, ?> prop : map(schema.get("properties")).entrySet()) {
                if (rows.size() >= 200) break;
                Map<?, ?> ps = deref(prop.getValue());
                String format = str(ps.get("format"));
                boolean file = multipart && (format.equals("binary") || format.equals("base64"));
                Object v = exampleMap.containsKey(prop.getKey()) ? exampleMap.get(prop.getKey()) : example(ps, null);
                KeyValue kv = new KeyValue(str(prop.getKey()), file ? "" : text(v), true);
                kv.file = file;
                rows.add(kv);
            }
            r.bodyType = multipart ? BodyType.MULTIPART : BodyType.FORM;
            if (multipart) r.multipart = rows;
            else r.form = rows;
        } else if (type.contains("octet-stream")) {
            r.bodyType = BodyType.BINARY;
        } else {
            rawBody(r, chosen, example(media.get("schema"), given));
        }
    }

    private void rawBody(RequestDef r, String mediaType, Object example) {
        String type = mediaType.toLowerCase(Locale.ROOT);
        if (type.contains("json")) {
            r.bodyType = BodyType.JSON;
            r.body = example == null ? "" : Json.write(example, true).strip();
            if (!type.equals("application/json")) r.headers.add(new KeyValue("Content-Type", oneLine(mediaType)));
        } else {
            r.bodyType = type.contains("xml") ? BodyType.XML : BodyType.TEXT;
            r.body = example instanceof String s ? s : "";
            if (!type.equals(r.bodyType.contentType)) r.headers.add(new KeyValue("Content-Type", oneLine(mediaType)));
        }
    }

    private static String mediaFrom(List<?> consumes, String fallback) {
        for (Object c : consumes) if (str(c).toLowerCase(Locale.ROOT).contains("json")) return str(c);
        return consumes.isEmpty() ? fallback : str(consumes.get(0));
    }

    /** An example value for a schema: the one the file gives, or one made up from the schema's shape. */
    private Object example(Object schema, Object given) {
        if (given != null) return given;
        budget = MAX_EXAMPLE_NODES;
        return make(schema, new HashSet<>(), 0);
    }

    private Object make(Object schemaOrRef, Set<String> refs, int depth) {
        if (--budget < 0 || depth > MAX_EXAMPLE_DEPTH) return null;
        String ref = schemaOrRef instanceof Map<?, ?> m ? str(m.get("$ref")) : "";
        if (!ref.isEmpty() && !refs.add(ref)) return null; // a schema that contains itself
        try {
            Map<?, ?> s = deref(schemaOrRef);
            if (s.get("example") != null) return s.get("example");
            if (s.get("examples") instanceof List<?> l && !l.isEmpty()) return l.get(0);
            if (s.get("default") != null) return s.get("default");
            if (s.get("const") != null) return s.get("const");
            List<?> en = list(s.get("enum"));
            if (!en.isEmpty()) return en.get(0);
            for (String alt : new String[]{"oneOf", "anyOf"}) {
                List<?> options = list(s.get(alt));
                if (!options.isEmpty()) return make(options.get(0), refs, depth + 1);
            }
            String type = s.get("type") instanceof List<?> tl
                    ? str(tl.stream().filter(x -> !"null".equals(x)).findFirst().map(Object.class::cast).orElse(null)) : str(s.get("type"));
            if (type.equals("object") || s.get("properties") != null || s.get("allOf") != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Object part : list(s.get("allOf"))) {
                    if (make(part, refs, depth + 1) instanceof Map<?, ?> pm) pm.forEach((k, v) -> out.put(str(k), v));
                }
                for (Map.Entry<?, ?> p : map(s.get("properties")).entrySet()) {
                    if (out.size() >= 100 || budget < 0) break;
                    if (Boolean.TRUE.equals(deref(p.getValue()).get("readOnly"))) continue; // the server fills these in
                    out.put(str(p.getKey()), make(p.getValue(), refs, depth + 1));
                }
                return out;
            }
            if (type.equals("array") || s.get("items") != null) {
                List<Object> out = new ArrayList<>();
                if (s.get("items") != null) out.add(make(s.get("items"), refs, depth + 1));
                return out;
            }
            return switch (type) {
                case "integer", "number" -> new Json.Num("0");
                case "boolean" -> Boolean.FALSE;
                case "string" -> switch (str(s.get("format"))) {
                    case "date-time" -> "2024-01-01T00:00:00Z";
                    case "date" -> "2024-01-01";
                    case "email" -> "user@example.com";
                    case "uuid" -> "00000000-0000-0000-0000-000000000000";
                    case "uri", "url" -> "https://example.com";
                    default -> "string";
                };
                default -> null;
            };
        } finally {
            if (!ref.isEmpty()) refs.remove(ref);
        }
    }

    /** A schema with its allOf parts' properties folded in (for form fields). */
    private Map<?, ?> flatten(Object schema, Set<String> refs, int depth) {
        Map<?, ?> s = deref(schema);
        if (depth > 4 || list(s.get("allOf")).isEmpty()) return s;
        Map<String, Object> props = new LinkedHashMap<>();
        for (Object part : list(s.get("allOf"))) {
            map(flatten(part, refs, depth + 1).get("properties")).forEach((k, v) -> props.put(str(k), v));
        }
        map(s.get("properties")).forEach((k, v) -> props.put(str(k), v));
        return Map.of("properties", props);
    }

    // ---- security --------------------------------------------------------------------------

    private void security(RequestDef r, Object requirements) {
        List<?> reqs = list(requirements);
        if (reqs.isEmpty() || !(reqs.get(0) instanceof Map<?, ?> first) || first.isEmpty()) return;
        Map<?, ?> schemes = v2 ? map(root.get("securityDefinitions")) : map(map(root.get("components")).get("securitySchemes"));
        for (Object schemeName : first.keySet()) {
            Map<?, ?> s = deref(schemes.get(schemeName));
            String type = str(s.get("type"));
            String httpScheme = str(s.get("scheme")).toLowerCase(Locale.ROOT);
            if (type.equals("basic") || (type.equals("http") && httpScheme.equals("basic"))) {
                r.auth = new Auth();
                r.auth.type = Auth.Type.BASIC;
                r.auth.username = "{{username}}";
                r.auth.password = "{{password}}";
                ensureVariable("username", "", false);
                ensureVariable("password", "", true);
            } else if (type.equals("http") || type.equals("oauth2") || type.equals("openIdConnect")) {
                r.auth = new Auth();
                r.auth.type = Auth.Type.BEARER;
                r.auth.token = "{{token}}";
                ensureVariable("token", "", true);
                if (!type.equals("http")) {
                    notes.add("OAuth 2 sign-in isn't run for you. Those requests send “Bearer {{token}}”: paste an access token into the collection's token variable.");
                } else if (!httpScheme.equals("bearer")) {
                    notes.add("“" + Postman.cleanName(httpScheme, "?") + "” auth isn't supported; those requests were set up with a bearer token instead.");
                }
            } else if (type.equals("apiKey")) {
                String name = str(s.get("name"));
                String in = str(s.get("in"));
                if (name.isEmpty()) continue;
                ensureVariable("apiKey", "", true);
                if (in.equals("header")) r.headers.add(new KeyValue(headerName(name), "{{apiKey}}"));
                else if (in.equals("query")) r.url += (r.url.contains("?") ? "&" : "?") + queryPart(name) + "={{apiKey}}";
                else cookies++;
            }
        }
    }

    private void ensureVariable(String name, String value, boolean secret) {
        for (Variable v : collection.variables) if (v.key.equals(name)) return;
        if (collection.variables.size() < 500) collection.variables.add(new Variable(name, value, secret));
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Follows local {@code $ref}s (a few hops at most). Anything else, including file and URL references, is empty. */
    private Map<?, ?> deref(Object o) {
        for (int hop = 0; hop < 20; hop++) {
            if (!(o instanceof Map<?, ?> m)) return Map.of();
            Object ref = m.get("$ref");
            if (!(ref instanceof String pointer)) return m;
            if (!pointer.startsWith("#/")) {
                notes.add("References to other files or URLs aren't followed (for example “" + Postman.cleanName(pointer, "?") + "”); those parts are missing.");
                return Map.of();
            }
            Object at = root;
            for (String seg : pointer.substring(2).split("/")) {
                String key = seg.replace("~1", "/").replace("~0", "~");
                if (key.contains("%")) {
                    try {
                        key = java.net.URLDecoder.decode(key.replace("+", "%2B"), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException ignored) {
                        // keep it as written
                    }
                }
                at = at instanceof Map<?, ?> am ? am.get(key) : null;
            }
            o = at;
        }
        return Map.of();
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : o instanceof String s ? s : o instanceof Map<?, ?> || o instanceof List<?> ? "" : String.valueOf(o);
    }

    /** An example value as the text that goes in a field. */
    private static String text(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (Object o : l) {
                if (o instanceof Map<?, ?> || o instanceof List<?>) return Json.write(v, false);
                sb.append(sb.length() == 0 ? "" : ",").append(str(o));
            }
            return sb.toString();
        }
        if (v instanceof Map<?, ?>) return Json.write(v, false);
        return String.valueOf(v);
    }

    /** Query names and values go into the URL as written unless they'd break it; {{variables}} are left alone. */
    private static String queryPart(String s) {
        if (s.contains("{{") || s.matches("[A-Za-z0-9._~,:@/\\[\\]*$!'()-]*")) return s;
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String headerName(String s) {
        String cleaned = s.replaceAll("[^A-Za-z0-9!#$%&'*+.^_`|~-]", "");
        return cleaned.isEmpty() ? "X-Header" : cleaned;
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ");
    }
}
