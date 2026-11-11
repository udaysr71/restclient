package dev.restclient.codegen;

import dev.restclient.http.MediaTypes;
import dev.restclient.http.PreparedRequest;
import dev.restclient.json.Json;
import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.vars.VariableResolver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a request into a short program that sends it, in a few common languages. Variables are replaced with
 * their values (the code has to run on its own), so secrets end up in the text, exactly as with "Copy as cURL".
 *
 * <p>Everything from the request is written as a string literal with the language's own escaping; nothing is
 * ever pasted in as code.
 */
public final class CodeGen {
    public enum Language {
        JAVA("Java (HttpClient)"), PYTHON("Python (requests)"), JAVASCRIPT("JavaScript (fetch)"), POWERSHELL("PowerShell");

        public final String label;

        Language(String label) {
            this.label = label;
        }
    }

    /** Headers the JDK's HttpClient won't accept. */
    private static final Set<String> JAVA_RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade");

    private enum Kind { NONE, TEXT, FORM, MULTIPART, FILE }

    /** The request with variables resolved and auth turned into a header. */
    private record Flat(String method, String url, List<KeyValue> headers, Kind kind, String text, List<KeyValue> fields,
                        String file, int timeout, boolean insecure) {
        String header(String name) {
            for (KeyValue h : headers) if (h.key.equalsIgnoreCase(name)) return h.value;
            return null;
        }
    }

    private CodeGen() {
    }

    public static String generate(Language lang, RequestDef r, VariableResolver vars, boolean insecure) {
        Flat f = flatten(r, vars == null ? VariableResolver.empty() : vars, insecure);
        return switch (lang) {
            case JAVA -> java(f);
            case PYTHON -> python(f);
            case JAVASCRIPT -> javascript(f);
            case POWERSHELL -> powershell(f);
        };
    }

    private static Flat flatten(RequestDef r, VariableResolver vars, boolean insecure) {
        String url = v(vars, r.url).trim();
        if (!url.isEmpty() && !url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = "http://" + url;
        url = PreparedRequest.encodeUnsafe(url);
        List<KeyValue> headers = new ArrayList<>();
        for (KeyValue h : r.headers) {
            if (!h.enabled || h.key == null || h.key.isBlank()) continue;
            headers.add(new KeyValue(v(vars, h.key).trim(), v(vars, h.value)));
        }
        Auth a = r.auth;
        if (a != null && !has(headers, "Authorization")) {
            if (a.type == Auth.Type.BASIC) {
                String pair = v(vars, a.username) + ":" + v(vars, a.password);
                headers.add(new KeyValue("Authorization", "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8))));
            } else if (a.type == Auth.Type.BEARER) {
                headers.add(new KeyValue("Authorization", "Bearer " + v(vars, a.token)));
            }
        }
        BodyType bt = r.bodyType == null ? BodyType.NONE : r.bodyType;
        Kind kind = Kind.NONE;
        String text = null;
        String file = null;
        List<KeyValue> fields = new ArrayList<>();
        if (bt.isRaw()) {
            kind = Kind.TEXT;
            text = v(vars, r.body);
            if (!has(headers, "Content-Type")) headers.add(new KeyValue("Content-Type", bt.contentType));
        } else if (bt == BodyType.FORM) {
            kind = Kind.FORM;
            for (KeyValue kv : r.form) {
                if (kv.enabled && kv.key != null && !kv.key.isBlank()) fields.add(new KeyValue(v(vars, kv.key), v(vars, kv.value)));
            }
            if (!has(headers, "Content-Type")) headers.add(new KeyValue("Content-Type", bt.contentType));
        } else if (bt == BodyType.MULTIPART) {
            kind = Kind.MULTIPART;
            for (KeyValue kv : r.multipart) {
                if (!kv.enabled || kv.key == null || kv.key.isBlank()) continue;
                KeyValue part = new KeyValue(v(vars, kv.key), kv.file ? v(vars, kv.value).trim() : v(vars, kv.value));
                part.file = kv.file;
                fields.add(part);
            }
            // every language's own multipart support writes the Content-Type with its boundary
            headers.removeIf(h -> h.key.equalsIgnoreCase("Content-Type"));
        } else if (bt == BodyType.BINARY) {
            kind = Kind.FILE;
            file = v(vars, r.bodyFile).trim();
            if (!has(headers, "Content-Type")) headers.add(new KeyValue("Content-Type", MediaTypes.forFile(file)));
        }
        HttpMethod m = r.method == null ? HttpMethod.GET : r.method;
        return new Flat(m.name(), url, headers, kind, text, fields, file, Math.max(0, r.timeoutSeconds), insecure);
    }

    private static String v(VariableResolver vars, String s) {
        return s == null ? "" : vars.resolve(s);
    }

    private static boolean has(List<KeyValue> headers, String name) {
        for (KeyValue h : headers) if (h.key.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** A double-quoted literal that Java, JavaScript and Python all read the same way (JSON escaping). */
    static String q(String s) {
        StringBuilder sb = new StringBuilder();
        Json.quote(sb, s == null ? "" : s);
        return sb.toString();
    }

    private static String formBody(List<KeyValue> fields) {
        StringBuilder sb = new StringBuilder();
        for (KeyValue kv : fields) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv.key, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(kv.value, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String fileName(String path) {
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String n = sep >= 0 ? path.substring(sep + 1) : path;
        return n.isEmpty() ? "file" : n;
    }

    // ---- Java ------------------------------------------------------------------------------

    private static String java(Flat f) {
        StringBuilder sb = new StringBuilder();
        sb.append("import java.net.URI;\nimport java.net.http.HttpClient;\nimport java.net.http.HttpRequest;\nimport java.net.http.HttpResponse;\n");
        if (f.kind == Kind.MULTIPART) sb.append("import java.io.ByteArrayOutputStream;\nimport java.nio.charset.StandardCharsets;\n");
        boolean readsFiles = f.kind == Kind.FILE || f.fields.stream().anyMatch(p -> p.file);
        if (readsFiles && f.kind == Kind.MULTIPART) sb.append("import java.nio.file.Files;\n");
        if (readsFiles) sb.append("import java.nio.file.Path;\n");
        if (f.timeout > 0) sb.append("import java.time.Duration;\n");
        sb.append("\npublic class Main {\n    public static void main(String[] args) throws Exception {\n");
        if (f.insecure) sb.append("        // This request skips TLS verification in REST Client. The code below verifies certificates as usual.\n");
        String publisher;
        if (f.kind == Kind.TEXT) {
            publisher = "HttpRequest.BodyPublishers.ofString(" + q(f.text) + ")";
        } else if (f.kind == Kind.FORM) {
            publisher = "HttpRequest.BodyPublishers.ofString(" + q(formBody(f.fields)) + ")";
        } else if (f.kind == Kind.FILE) {
            publisher = "HttpRequest.BodyPublishers.ofFile(Path.of(" + q(f.file) + "))";
        } else if (f.kind == Kind.MULTIPART) {
            sb.append("        String boundary = \"----Boundary\" + System.nanoTime();\n");
            sb.append("        ByteArrayOutputStream body = new ByteArrayOutputStream();\n");
            for (KeyValue p : f.fields) {
                String head = "Content-Disposition: form-data; name=\"" + partName(p.key) + "\"";
                if (p.file) {
                    head += "; filename=\"" + partName(fileName(p.value)) + "\"\r\nContent-Type: " + MediaTypes.forFile(p.value);
                    sb.append("        body.writeBytes((\"--\" + boundary + ").append(q("\r\n" + head + "\r\n\r\n")).append(").getBytes(StandardCharsets.UTF_8));\n");
                    sb.append("        body.writeBytes(Files.readAllBytes(Path.of(").append(q(p.value)).append(")));\n");
                    sb.append("        body.writeBytes(\"\\r\\n\".getBytes(StandardCharsets.UTF_8));\n");
                } else {
                    sb.append("        body.writeBytes((\"--\" + boundary + ").append(q("\r\n" + head + "\r\n\r\n" + p.value + "\r\n"))
                            .append(").getBytes(StandardCharsets.UTF_8));\n");
                }
            }
            sb.append("        body.writeBytes((\"--\" + boundary + \"--\\r\\n\").getBytes(StandardCharsets.UTF_8));\n\n");
            publisher = "HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())";
        } else {
            publisher = "HttpRequest.BodyPublishers.noBody()";
        }
        sb.append("        HttpClient client = HttpClient.newHttpClient();\n");
        sb.append("        HttpRequest request = HttpRequest.newBuilder(URI.create(").append(q(f.url)).append("))\n");
        for (KeyValue h : f.headers) {
            if (JAVA_RESTRICTED.contains(h.key.toLowerCase(Locale.ROOT))) continue;
            sb.append("                .header(").append(q(h.key)).append(", ").append(q(h.value)).append(")\n");
        }
        if (f.kind == Kind.MULTIPART) sb.append("                .header(\"Content-Type\", \"multipart/form-data; boundary=\" + boundary)\n");
        if (f.timeout > 0) sb.append("                .timeout(Duration.ofSeconds(").append(f.timeout).append("))\n");
        sb.append("                .method(").append(q(f.method)).append(", ").append(publisher).append(")\n");
        sb.append("                .build();\n\n");
        sb.append("        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());\n");
        sb.append("        System.out.println(response.statusCode());\n        System.out.println(response.body());\n    }\n}\n");
        return sb.toString();
    }

    private static String partName(String s) {
        return s.replace("\\", "\\\\").replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    // ---- Python ----------------------------------------------------------------------------

    private static String python(Flat f) {
        StringBuilder sb = new StringBuilder("import requests\n\n");
        List<String> args = new ArrayList<>();
        args.add(q(f.method));
        args.add(q(f.url));
        if (!f.headers.isEmpty()) {
            // requests takes a dict; a repeated header is sent as one comma-separated value, which means the same
            Map<String, String> merged = new LinkedHashMap<>();
            for (KeyValue h : f.headers) merged.merge(h.key, h.value, (x, y) -> x + ", " + y);
            StringBuilder d = new StringBuilder("headers={\n");
            merged.forEach((k, val) -> d.append("        ").append(q(k)).append(": ").append(q(val)).append(",\n"));
            args.add(d.append("    }").toString());
        }
        if (f.kind == Kind.TEXT) {
            args.add("data=" + q(f.text) + ".encode(\"utf-8\")");
        } else if (f.kind == Kind.FORM) {
            StringBuilder d = new StringBuilder("data=[\n");
            for (KeyValue kv : f.fields) d.append("        (").append(q(kv.key)).append(", ").append(q(kv.value)).append("),\n");
            args.add(d.append("    ]").toString());
        } else if (f.kind == Kind.MULTIPART) {
            StringBuilder d = new StringBuilder("files=[\n");
            for (KeyValue p : f.fields) {
                d.append("        (").append(q(p.key)).append(", ");
                if (p.file) {
                    d.append('(').append(q(fileName(p.value))).append(", open(").append(q(p.value)).append(", \"rb\"), ")
                            .append(q(MediaTypes.forFile(p.value))).append(')');
                } else {
                    d.append("(None, ").append(q(p.value)).append(')');
                }
                d.append("),\n");
            }
            args.add(d.append("    ]").toString());
        } else if (f.kind == Kind.FILE) {
            args.add("data=open(" + q(f.file) + ", \"rb\")");
        }
        if (f.timeout > 0) args.add("timeout=" + f.timeout);
        if (f.insecure) args.add("verify=False,  # skips TLS verification, as the request does in REST Client");
        sb.append("response = requests.request(\n");
        for (String a : args) sb.append("    ").append(a).append(a.contains("# ") ? "" : ",").append('\n');
        sb.append(")\nprint(response.status_code)\nprint(response.text)\n");
        return sb.toString();
    }

    // ---- JavaScript ------------------------------------------------------------------------

    private static String javascript(Flat f) {
        StringBuilder sb = new StringBuilder();
        boolean readsFiles = f.kind == Kind.FILE || f.fields.stream().anyMatch(p -> p.file);
        if (readsFiles) {
            sb.append("// Runs in Node.js 20 or newer. In a browser, use a File from an <input type=\"file\"> instead of openAsBlob.\n");
            sb.append("import { openAsBlob } from \"node:fs\";\n\n");
        }
        if (f.insecure) sb.append("// This request skips TLS verification in REST Client. fetch always verifies certificates.\n");
        String body = null;
        if (f.kind == Kind.TEXT) {
            body = q(f.text);
        } else if (f.kind == Kind.FORM) {
            StringBuilder d = new StringBuilder("new URLSearchParams([\n");
            for (KeyValue kv : f.fields) d.append("    [").append(q(kv.key)).append(", ").append(q(kv.value)).append("],\n");
            body = d.append("  ])").toString();
        } else if (f.kind == Kind.MULTIPART) {
            sb.append("const form = new FormData();\n");
            for (KeyValue p : f.fields) {
                if (p.file) {
                    sb.append("form.append(").append(q(p.key)).append(", await openAsBlob(").append(q(p.value)).append(", { type: ")
                            .append(q(MediaTypes.forFile(p.value))).append(" }), ").append(q(fileName(p.value))).append(");\n");
                } else {
                    sb.append("form.append(").append(q(p.key)).append(", ").append(q(p.value)).append(");\n");
                }
            }
            sb.append('\n');
            body = "form";
        } else if (f.kind == Kind.FILE) {
            body = "await openAsBlob(" + q(f.file) + ")";
        }
        sb.append("const response = await fetch(").append(q(f.url)).append(", {\n");
        sb.append("  method: ").append(q(f.method)).append(",\n");
        List<KeyValue> headers = new ArrayList<>(f.headers);
        // URLSearchParams sets its own Content-Type
        if (f.kind == Kind.FORM) headers.removeIf(h -> h.key.equalsIgnoreCase("Content-Type") && h.value.equals(BodyType.FORM.contentType));
        if (!headers.isEmpty()) {
            sb.append("  headers: [\n");
            for (KeyValue h : headers) sb.append("    [").append(q(h.key)).append(", ").append(q(h.value)).append("],\n");
            sb.append("  ],\n");
        }
        if (body != null) sb.append("  body: ").append(body).append(",\n");
        if (f.timeout > 0) sb.append("  signal: AbortSignal.timeout(").append(f.timeout * 1000L).append("),\n");
        sb.append("});\nconsole.log(response.status);\nconsole.log(await response.text());\n");
        return sb.toString();
    }

    // ---- PowerShell ------------------------------------------------------------------------

    /** A single-quoted PowerShell literal: nothing inside is expanded; quotes (straight or curly) are doubled. */
    static String ps(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < (s == null ? 0 : s.length()); i++) {
            char c = s.charAt(i);
            // PowerShell treats the curly single quotes as quote characters too
            if (c == '\'' || c == '‘' || c == '’' || c == '‚' || c == '‛') sb.append(c);
            sb.append(c);
        }
        return sb.append('\'').toString();
    }

    private static String powershell(Flat f) {
        StringBuilder sb = new StringBuilder();
        String contentType = null;
        Map<String, String> merged = new LinkedHashMap<>();
        for (KeyValue h : f.headers) {
            // Windows PowerShell 5 rejects Content-Type in -Headers: it has its own parameter
            if (h.key.equalsIgnoreCase("Content-Type")) contentType = h.value;
            else merged.merge(h.key, h.value, (x, y) -> x + ", " + y);
        }
        if (!merged.isEmpty()) {
            sb.append("$headers = @{\n");
            merged.forEach((k, val) -> sb.append("    ").append(ps(k)).append(" = ").append(ps(val)).append('\n'));
            sb.append("}\n");
        }
        if (f.kind == Kind.MULTIPART) {
            sb.append("# -Form needs PowerShell 6.1 or newer\n$form = @{\n");
            for (KeyValue p : f.fields) {
                sb.append("    ").append(ps(p.key)).append(" = ").append(p.file ? "Get-Item -LiteralPath " + ps(p.value) : ps(p.value)).append('\n');
            }
            sb.append("}\n");
        }
        List<String> args = new ArrayList<>();
        args.add("-Uri " + ps(f.url));
        args.add("-Method " + f.method);
        if (!merged.isEmpty()) args.add("-Headers $headers");
        if (contentType != null) args.add("-ContentType " + ps(contentType));
        if (f.kind == Kind.TEXT) args.add("-Body " + ps(f.text));
        else if (f.kind == Kind.FORM) args.add("-Body " + ps(formBody(f.fields)));
        else if (f.kind == Kind.MULTIPART) args.add("-Form $form");
        else if (f.kind == Kind.FILE) args.add("-InFile " + ps(f.file));
        if (f.timeout > 0) args.add("-TimeoutSec " + f.timeout);
        if (f.insecure) args.add("-SkipCertificateCheck"); // PowerShell 6+
        if (f.insecure) sb.append("# -SkipCertificateCheck needs PowerShell 6 or newer\n");
        sb.append("$response = Invoke-WebRequest");
        for (String a : args) sb.append(" `\n    ").append(a);
        sb.append("\n$response.StatusCode\n$response.Content\n");
        return sb.toString();
    }
}
