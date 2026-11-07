package dev.restclient.curl;

import dev.restclient.model.Auth;
import dev.restclient.model.BodyType;
import dev.restclient.model.HttpMethod;
import dev.restclient.model.KeyValue;
import dev.restclient.model.RequestDef;
import dev.restclient.vars.VariableResolver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Builds a copy-pasteable, POSIX-shell-safe curl command for a request. */
public final class CurlExporter {
    private CurlExporter() {
    }

    /**
     * @param resolver when non-null, variables are replaced with their values; when null, {@code {{vars}}} are kept
     * @param insecure adds {@code -k}
     */
    public static String export(RequestDef r, VariableResolver resolver, boolean insecure) {
        // First line: curl [-k] [-X METHOD] 'url'
        StringBuilder first = new StringBuilder("curl");
        if (insecure) first.append(" -k");
        HttpMethod m = r.method == null ? HttpMethod.GET : r.method;
        if (m == HttpMethod.HEAD) first.append(" --head");
        else if (m != HttpMethod.GET) first.append(" -X ").append(m.name());
        first.append(' ').append(quote(v(resolver, r.url)));

        List<String> options = new ArrayList<>();
        boolean hasContentType = false;
        boolean hasAuthorization = false;
        for (KeyValue h : r.headers) {
            if (!h.enabled || h.key == null || h.key.isBlank()) continue;
            String key = v(resolver, h.key.trim());
            if (key.equalsIgnoreCase("Content-Type")) hasContentType = true;
            if (key.equalsIgnoreCase("Authorization")) hasAuthorization = true;
            String value = v(resolver, h.value);
            // "Name;" is curl's way to send a header with an empty value.
            options.add("-H " + quote(key + (value.isEmpty() ? ";" : ": " + value)));
        }

        Auth a = r.auth;
        if (a != null && !hasAuthorization) {
            if (a.type == Auth.Type.BASIC) {
                options.add("-u " + quote(v(resolver, a.username) + ":" + v(resolver, a.password)));
            } else if (a.type == Auth.Type.BEARER) {
                options.add("-H " + quote("Authorization: Bearer " + v(resolver, a.token)));
            }
        }

        BodyType bt = r.bodyType == null ? BodyType.NONE : r.bodyType;
        if (bt.isRaw()) {
            if (!hasContentType) options.add("-H " + quote("Content-Type: " + bt.contentType));
            options.add("--data-raw " + quote(v(resolver, r.body)));
        } else if (bt == BodyType.FORM) {
            for (KeyValue f : r.form) {
                if (!f.enabled || f.key == null || f.key.isBlank()) continue;
                String key = URLEncoder.encode(v(resolver, f.key), StandardCharsets.UTF_8);
                options.add("--data-urlencode " + quote(key + "=" + v(resolver, f.value)));
            }
        }
        if (r.timeoutSeconds > 0) options.add("--max-time " + r.timeoutSeconds);

        StringBuilder sb = new StringBuilder(first);
        for (String o : options) sb.append(" \\\n  ").append(o);
        return sb.toString();
    }

    private static String v(VariableResolver resolver, String s) {
        if (s == null) return "";
        return resolver == null ? s : resolver.resolve(s);
    }

    /** Single-quotes for a POSIX shell: ' becomes '\''. */
    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
