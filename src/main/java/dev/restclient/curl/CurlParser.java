package dev.restclient.curl;

import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns a curl command line into a {@link RequestDef}. Unsupported options are reported, not dropped silently. */
public final class CurlParser {

    /** The parsed request plus what the user should know about. */
    public record Result(RequestDef request, boolean insecure, List<String> ignored, List<String> warnings) {
    }

    /** Short options that take a value, mapped to their long names. */
    private static final Map<Character, String> SHORT_WITH_VALUE = Map.ofEntries(
            Map.entry('X', "--request"), Map.entry('H', "--header"), Map.entry('d', "--data"),
            Map.entry('u', "--user"), Map.entry('A', "--user-agent"), Map.entry('e', "--referer"),
            Map.entry('b', "--cookie"), Map.entry('F', "--form"), Map.entry('o', "--output"),
            Map.entry('m', "--max-time"), Map.entry('x', "--proxy"), Map.entry('w', "--write-out"),
            Map.entry('c', "--cookie-jar"), Map.entry('E', "--cert"), Map.entry('T', "--upload-file"),
            Map.entry('r', "--range"), Map.entry('C', "--continue-at"), Map.entry('U', "--proxy-user"),
            Map.entry('K', "--config"), Map.entry('Y', "--speed-limit"), Map.entry('y', "--speed-time"),
            Map.entry('z', "--time-cond"));

    /** Boolean short options we understand or knowingly ignore. */
    private static final Map<Character, String> SHORT_FLAGS = Map.ofEntries(
            Map.entry('k', "--insecure"), Map.entry('G', "--get"), Map.entry('I', "--head"),
            Map.entry('L', "--location"), Map.entry('s', "--silent"), Map.entry('S', "--show-error"),
            Map.entry('v', "--verbose"), Map.entry('i', "--include"), Map.entry('f', "--fail"),
            Map.entry('N', "--no-buffer"), Map.entry('O', "--remote-name"), Map.entry('g', "--globoff"),
            Map.entry('#', "--progress-bar"), Map.entry('q', "--disable"), Map.entry('4', "--ipv4"),
            Map.entry('6', "--ipv6"), Map.entry('0', "--http1.0"), Map.entry('j', "--junk-session-cookies"),
            Map.entry('n', "--netrc"), Map.entry('l', "--list-only"), Map.entry('R', "--remote-time"));

    /** Long options that take a value but have no effect here. */
    private static final Set<String> IGNORED_WITH_VALUE = Set.of(
            "--output", "--write-out", "--cookie-jar", "--connect-timeout", "--retry", "--retry-delay",
            "--retry-max-time", "--proxy", "--proxy-user", "--cacert", "--capath", "--cert", "--key",
            "--cert-type", "--key-type", "--pass", "--resolve", "--interface", "--limit-rate", "--range",
            "--continue-at", "--config", "--speed-limit", "--speed-time", "--time-cond", "--upload-file",
            "--max-redirs", "--dns-servers", "--trace", "--trace-ascii", "--stderr", "--user-agent-file",
            "--aws-sigv4", "--oauth2-bearer-file", "--unix-socket", "--abstract-unix-socket", "--ciphers",
            "--tls-max", "--form-string", "--request-target", "--keepalive-time", "--local-port", "--happy-eyeballs-timeout-ms");

    /** Boolean long options that are irrelevant (no warning beyond "ignored"). */
    private static final Set<String> HARMLESS = Set.of(
            "--location", "--silent", "--show-error", "--verbose", "--include", "--fail", "--no-buffer",
            "--globoff", "--progress-bar", "--disable", "--http1.0", "--http1.1", "--http2", "--http2-prior-knowledge",
            "--http3", "--ipv4", "--ipv6", "--tlsv1", "--tlsv1.0", "--tlsv1.1", "--tlsv1.2", "--tlsv1.3",
            "--no-keepalive", "--tcp-nodelay", "--path-as-is", "--fail-with-body", "--no-progress-meter",
            "--location-trusted", "--remote-name", "--remote-time", "--junk-session-cookies", "--netrc",
            "--list-only", "--styled-output", "--no-styled-output", "--raw", "--tr-encoding", "--ssl", "--ssl-reqd",
            "--no-alpn", "--no-npn", "--no-sessionid", "--suppress-connect-headers");

    private CurlParser() {
    }

    public static boolean looksLikeCurl(String text) {
        if (text == null) return false;
        String t = text.stripLeading();
        return t.regionMatches(true, 0, "curl ", 0, 5) || t.regionMatches(true, 0, "curl\t", 0, 5)
                || t.regionMatches(true, 0, "curl\\", 0, 5);
    }

    public static Result parse(String command) throws CurlParseException {
        if (command == null || command.isBlank()) throw new CurlParseException("Paste a curl command", "", 0);
        String src = command;
        List<ShellTokenizer.Word> words = ShellTokenizer.tokenize(src);
        int i = 0;
        if (!words.isEmpty() && words.get(0).text().equalsIgnoreCase("curl")) i = 1;

        RequestDef r = new RequestDef();
        r.name = "Imported request";
        String method = null;
        String url = null;
        List<String> data = new ArrayList<>();
        boolean dataGiven = false;
        boolean getMode = false;
        boolean head = false;
        boolean insecure = false;
        boolean jsonFlag = false;
        List<String> ignored = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean endOfOptions = false;

        while (i < words.size()) {
            ShellTokenizer.Word w = words.get(i++);
            String t = w.text();
            String opt;
            String inlineValue = null;

            if (endOfOptions || !t.startsWith("-") || t.equals("-")) {
                if (url == null) url = t;
                else warnings.add("Extra URL \"" + t + "\" ignored (only one URL per request).");
                continue;
            }
            if (t.equals("--")) {
                endOfOptions = true;
                continue;
            }
            if (t.startsWith("--")) {
                int eq = t.indexOf('=');
                // curl itself doesn't accept --opt=value, but people write it; tolerate it for known options.
                if (eq > 2 && takesValue(t.substring(0, eq))) {
                    opt = t.substring(0, eq);
                    inlineValue = t.substring(eq + 1);
                } else {
                    opt = t;
                }
            } else {
                // short option cluster: -sSLk, -XPOST, -HAccept:x
                char first = t.charAt(1);
                if (SHORT_WITH_VALUE.containsKey(first)) {
                    opt = SHORT_WITH_VALUE.get(first);
                    if (t.length() > 2) inlineValue = t.substring(2);
                } else {
                    // all booleans, except possibly the last char taking a value: -sX POST
                    opt = null;
                    for (int k = 1; k < t.length(); k++) {
                        char ch = t.charAt(k);
                        if (SHORT_WITH_VALUE.containsKey(ch)) {
                            opt = SHORT_WITH_VALUE.get(ch);
                            if (k + 1 < t.length()) inlineValue = t.substring(k + 1);
                            break;
                        }
                        String longName = SHORT_FLAGS.get(ch);
                        if (longName == null) {
                            ignored.add("-" + ch);
                            continue;
                        }
                        switch (longName) {
                            case "--insecure" -> insecure = true;
                            case "--get" -> getMode = true;
                            case "--head" -> head = true;
                            default -> noteIgnored(longName, "-" + ch, ignored);
                        }
                    }
                    if (opt == null) continue;
                }
            }

            String value = null;
            if (takesValue(opt)) {
                if (inlineValue != null) {
                    value = inlineValue;
                } else if (i < words.size()) {
                    value = words.get(i++).text();
                } else {
                    throw new CurlParseException("Option " + opt + " needs a value", src, w.offset());
                }
            }

            switch (opt) {
                case "--request" -> method = value;
                case "--url" -> {
                    if (url == null) url = value;
                    else warnings.add("Extra URL \"" + value + "\" ignored (only one URL per request).");
                }
                case "--header" -> addHeader(r, value);
                case "--data", "--data-ascii", "--data-binary", "--data-raw" -> {
                    dataGiven = true;
                    if (!opt.equals("--data-raw") && value.startsWith("@")) {
                        warnings.add("File data (" + value + ") can't be read; the reference was kept as text.");
                    }
                    String v = opt.equals("--data") || opt.equals("--data-ascii") ? value.replace("\r", "").replace("\n", "") : value;
                    data.add(v);
                }
                case "--data-urlencode" -> {
                    dataGiven = true;
                    data.add(urlencodeData(value, warnings));
                }
                case "--json" -> {
                    dataGiven = true;
                    jsonFlag = true;
                    data.add(value);
                }
                case "--user" -> {
                    int colon = value.indexOf(':');
                    r.auth = new Auth();
                    r.auth.type = Auth.Type.BASIC;
                    r.auth.username = colon < 0 ? value : value.substring(0, colon);
                    r.auth.password = colon < 0 ? "" : value.substring(colon + 1);
                }
                case "--oauth2-bearer" -> {
                    r.auth = new Auth();
                    r.auth.type = Auth.Type.BEARER;
                    r.auth.token = value;
                }
                case "--user-agent" -> r.headers.add(new KeyValue("User-Agent", value));
                case "--referer" -> r.headers.add(new KeyValue("Referer", value));
                case "--cookie" -> {
                    if (value.contains("=")) r.headers.add(new KeyValue("Cookie", value));
                    else warnings.add("Cookie file \"" + value + "\" can't be read; it was ignored.");
                }
                case "--max-time" -> {
                    try {
                        r.timeoutSeconds = (int) Math.ceil(Double.parseDouble(value));
                    } catch (NumberFormatException e) {
                        warnings.add("Invalid --max-time \"" + value + "\" ignored.");
                    }
                }
                case "--insecure" -> insecure = true;
                case "--get" -> getMode = true;
                case "--head" -> head = true;
                case "--compressed" -> ignored.add("--compressed (responses are decompressed automatically)");
                case "--form" -> ignored.add("-F/--form \"" + value + "\" (multipart forms aren't supported)");
                default -> {
                    if (IGNORED_WITH_VALUE.contains(opt)) {
                        ignored.add(opt + " " + value);
                    } else if (HARMLESS.contains(opt)) {
                        noteIgnored(opt, opt, ignored);
                    } else {
                        ignored.add(opt + " (unsupported option)");
                    }
                }
            }
        }

        if (url == null || url.isBlank()) throw new CurlParseException("No URL found in the command", src, src.length());

        String joined = String.join("&", data);
        HttpMethod m;
        if (method != null) {
            m = HttpMethod.parse(method);
            if (m == null) throw new CurlParseException("Unsupported method \"" + method + "\"", src, src.indexOf(method));
        } else if (head) {
            m = HttpMethod.HEAD;
        } else if (getMode) {
            m = HttpMethod.GET;
        } else if (dataGiven) {
            m = HttpMethod.POST;
        } else {
            m = HttpMethod.GET;
        }
        r.method = m;

        if (getMode && dataGiven) {
            url = url + (url.contains("?") ? "&" : "?") + joined;
            dataGiven = false;
        }
        r.url = url;

        if (dataGiven) {
            if (jsonFlag) {
                if (!r.hasHeader("Content-Type")) r.headers.add(new KeyValue("Content-Type", "application/json"));
                if (!r.hasHeader("Accept")) r.headers.add(new KeyValue("Accept", "application/json"));
            }
            applyBody(r, joined);
        }
        return new Result(r, insecure, ignored, warnings);
    }

    private static void noteIgnored(String longName, String shown, List<String> ignored) {
        if (longName.equals("--location") || longName.equals("--location-trusted")) {
            ignored.add(shown + " (redirects are followed by default)");
        } else {
            ignored.add(shown);
        }
    }

    private static boolean takesValue(String opt) {
        return switch (opt) {
            case "--request", "--url", "--header", "--data", "--data-ascii", "--data-binary", "--data-raw",
                 "--data-urlencode", "--json", "--user", "--oauth2-bearer", "--user-agent", "--referer",
                 "--cookie", "--max-time", "--form" -> true;
            default -> IGNORED_WITH_VALUE.contains(opt);
        };
    }

    private static void addHeader(RequestDef r, String raw) {
        int colon = raw.indexOf(':');
        if (colon < 0) {
            // "Name;" sends an empty header in curl; "Name" alone removes a default header.
            String name = raw.endsWith(";") ? raw.substring(0, raw.length() - 1) : raw;
            if (raw.endsWith(";")) r.headers.add(new KeyValue(name.trim(), ""));
            return;
        }
        String name = raw.substring(0, colon).trim();
        String value = raw.substring(colon + 1).trim();
        if (value.isEmpty()) return; // "Name:" removes a header in curl
        r.headers.add(new KeyValue(name, value));
    }

    /** --data-urlencode forms: "content", "=content", "name=content", "@file", "name@file". */
    private static String urlencodeData(String v, List<String> warnings) {
        int eq = v.indexOf('=');
        int at = v.indexOf('@');
        if (at >= 0 && (eq < 0 || at < eq)) {
            warnings.add("File data (" + v + ") can't be read; the reference was kept as text.");
            return v;
        }
        if (eq < 0) return enc(v);
        if (eq == 0) return enc(v.substring(1));
        return v.substring(0, eq) + "=" + enc(v.substring(eq + 1));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void applyBody(RequestDef r, String data) {
        String ct = r.header("Content-Type");
        String lct = ct == null ? "" : ct.toLowerCase(Locale.ROOT);
        String trimmed = data.trim();
        if (lct.contains("json") || ((lct.isEmpty()) && (trimmed.startsWith("{") || trimmed.startsWith("[")))) {
            r.bodyType = BodyType.JSON;
            r.body = data;
        } else if (lct.contains("xml") || (lct.isEmpty() && trimmed.startsWith("<"))) {
            r.bodyType = BodyType.XML;
            r.body = data;
        } else if ((lct.isEmpty() || lct.contains("x-www-form-urlencoded")) && parseForm(data, r)) {
            r.bodyType = BodyType.FORM;
            // curl's implicit Content-Type matches what FORM sends; drop an explicit duplicate.
            r.headers.removeIf(h -> h.key.equalsIgnoreCase("Content-Type") && h.value.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded"));
        } else {
            r.bodyType = BodyType.TEXT;
            r.body = data;
            if (lct.isEmpty()) r.headers.add(new KeyValue("Content-Type", "application/x-www-form-urlencoded"));
        }
    }

    /** Parses a=b&c=d into form rows. Returns false if the data isn't clean form encoding. */
    private static boolean parseForm(String data, RequestDef r) {
        if (data.isEmpty()) return false;
        List<KeyValue> rows = new ArrayList<>();
        for (String pair : data.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0) return false;
            try {
                rows.add(new KeyValue(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (rows.isEmpty()) return false;
        r.form = rows;
        return true;
    }
}
