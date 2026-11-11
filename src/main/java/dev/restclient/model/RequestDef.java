package dev.restclient.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A request as the user edits it. Values may contain {@code {{variables}}}; they are
 * resolved only when the request is sent or exported.
 */
public class RequestDef {
    public String id = UUID.randomUUID().toString();
    public String name = "New Request";
    public HttpMethod method = HttpMethod.GET;
    /** Full URL including the query string. The Params table is derived from it. */
    public String url = "";
    /** Query params the user switched off; they are kept here so they are not lost. */
    public List<KeyValue> disabledParams = new ArrayList<>();
    public List<KeyValue> headers = new ArrayList<>();
    public BodyType bodyType = BodyType.NONE;
    public String body = "";
    public List<KeyValue> form = new ArrayList<>();
    /** Parts of a multipart/form-data body; a row with {@code file} set uploads the file its value points to. */
    public List<KeyValue> multipart = new ArrayList<>();
    /** Path of the file sent as the whole body ({@link BodyType#BINARY}). */
    public String bodyFile = "";
    /**
     * Values to keep from the response: key = variable name, value = where to take it from
     * (see {@code dev.restclient.capture.Capture}).
     */
    public List<KeyValue> captures = new ArrayList<>();
    /**
     * Local variables: {{name}} values that apply to this request only and win over the environment, the
     * collection and Globals.
     */
    public List<KeyValue> locals = new ArrayList<>();
    public Auth auth = new Auth();
    /** {@code null} = use the global default from Settings. */
    public Boolean insecure;
    /** Seconds; 0 = use the global default. */
    public int timeoutSeconds;

    public RequestDef copy() {
        RequestDef r = new RequestDef();
        r.id = id;
        r.name = name;
        r.method = method;
        r.url = url;
        r.disabledParams = copyList(disabledParams);
        r.headers = copyList(headers);
        r.bodyType = bodyType;
        r.body = body;
        r.form = copyList(form);
        r.multipart = copyList(multipart);
        r.bodyFile = bodyFile;
        r.captures = copyList(captures);
        r.locals = copyList(locals);
        r.auth = auth == null ? new Auth() : auth.copy();
        r.insecure = insecure;
        r.timeoutSeconds = timeoutSeconds;
        return r;
    }

    /** A copy with a fresh id, e.g. for "Duplicate". */
    public RequestDef duplicate() {
        RequestDef r = copy();
        r.id = UUID.randomUUID().toString();
        return r;
    }

    public String header(String name) {
        for (KeyValue kv : headers) {
            if (kv.enabled && kv.key != null && kv.key.trim().equalsIgnoreCase(name)) return kv.value;
        }
        return null;
    }

    public boolean hasHeader(String name) {
        return header(name) != null;
    }

    private static List<KeyValue> copyList(List<KeyValue> in) {
        List<KeyValue> out = new ArrayList<>();
        if (in != null) for (KeyValue kv : in) out.add(kv.copy());
        return out;
    }

    /** Equality on content (not id), used for the unsaved-changes dot. */
    public boolean sameContent(RequestDef o) {
        if (o == null) return false;
        return Objects.equals(name, o.name) && method == o.method && Objects.equals(url, o.url)
                && Objects.equals(disabledParams, o.disabledParams) && Objects.equals(headers, o.headers)
                && bodyType == o.bodyType && Objects.equals(body, o.body) && Objects.equals(form, o.form)
                && Objects.equals(multipart, o.multipart) && Objects.equals(bodyFile == null ? "" : bodyFile, o.bodyFile == null ? "" : o.bodyFile)
                && Objects.equals(captures, o.captures) && Objects.equals(locals, o.locals)
                && Objects.equals(auth, o.auth) && Objects.equals(insecure, o.insecure)
                && timeoutSeconds == o.timeoutSeconds;
    }
}
