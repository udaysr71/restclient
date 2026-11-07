package dev.restclient.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One sent request: the editable original plus a snapshot of what went over the wire. */
public class HistoryEntry {
    public String id = UUID.randomUUID().toString();
    /** Epoch millis. */
    public long timestamp;
    /** The request as edited, with {@code {{variables}}} intact. */
    public RequestDef request;
    /** What was sent, with variables resolved and secret values masked. */
    public HttpMethod sentMethod;
    public String sentUrl;
    public List<KeyValue> sentHeaders = new ArrayList<>();
    public String sentBody;
    public String environmentName;
    /** Name of the saved request it came from, if any. */
    public String sourceName;

    /** 0 when the request failed before a response arrived. */
    public int status;
    public String errorKind;
    public String errorMessage;
    public long elapsedMs;
    public long sizeBytes;
    public List<KeyValue> responseHeaders = new ArrayList<>();
    /** Response body, cut to a size limit so history.json stays small. */
    public String responseBody;
    public boolean responseTruncated;
    public String contentType;
}
