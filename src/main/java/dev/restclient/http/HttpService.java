package dev.restclient.http;

import dev.restclient.model.KeyValue;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Sends prepared requests with java.net.http.HttpClient, using verified or skip-verification TLS. */
public final class HttpService {
    public record Options(int connectTimeoutSeconds, boolean followRedirects, boolean useSystemProxy) {
    }

    private final TlsManager tls;
    private volatile Options options;
    private volatile HttpClient secureClient;
    private volatile HttpClient insecureClient;

    public HttpService(TlsManager tls, Options options) {
        this.tls = tls;
        this.options = options;
        rebuild();
    }

    /** Call after the certs folder is reloaded or network settings change. */
    public synchronized void rebuild() {
        secureClient = build(tls.secureContext());
        insecureClient = build(tls.insecureContext());
    }

    public void setOptions(Options options) {
        this.options = options;
        rebuild();
    }

    private HttpClient build(javax.net.ssl.SSLContext ctx) {
        Options o = options;
        HttpClient.Builder b = HttpClient.newBuilder()
                .sslContext(ctx)
                .connectTimeout(Duration.ofSeconds(Math.max(1, o.connectTimeoutSeconds())))
                .followRedirects(o.followRedirects() ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
        if (o.useSystemProxy()) b.proxy(ProxySelector.getDefault());
        return b.build();
    }

    /** An in-flight request. {@link #cancel()} aborts the exchange; the result then reports CANCELLED. */
    public record Call(CompletableFuture<ResponseData> result, Runnable canceller) {
        public void cancel() {
            canceller.run();
        }
    }

    public Call send(PreparedRequest p, boolean insecure, Duration timeout) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(p.uri).timeout(timeout);
        for (KeyValue kv : p.headers) rb.header(kv.key, kv.value);
        HttpRequest.BodyPublisher pub = p.body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(p.body);
        rb.method(p.method.name(), pub);

        HttpRequest request;
        String host = p.uri.getHost();
        long start = System.nanoTime();
        try {
            request = rb.build();
        } catch (IllegalArgumentException e) {
            return new Call(CompletableFuture.completedFuture(
                    ResponseData.failed(ResponseData.ErrorKind.INVALID, e, e.getMessage(), 0, host)), () -> { });
        }
        HttpClient client = insecure ? insecureClient : secureClient;
        CompletableFuture<HttpResponse<byte[]>> raw = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        CompletableFuture<ResponseData> result = raw
                .handle((resp, err) -> {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    if (err != null) return classify(err, ms, host);
                    List<KeyValue> headers = new ArrayList<>();
                    for (Map.Entry<String, List<String>> e : resp.headers().map().entrySet()) {
                        if (e.getKey().startsWith(":")) continue; // HTTP/2 pseudo headers
                        for (String v : e.getValue()) headers.add(new KeyValue(e.getKey(), v));
                    }
                    byte[] body = decode(resp.body(), resp.headers().firstValue("Content-Encoding").orElse(""));
                    return ResponseData.ok(resp.statusCode(), headers, body, ms, host);
                });
        return new Call(result, () -> raw.cancel(true));
    }

    /** Responses are decompressed when the server sent gzip or deflate. */
    static byte[] decode(byte[] body, String encoding) {
        if (body == null) return new byte[0];
        String enc = encoding.toLowerCase(Locale.ROOT).trim();
        try {
            if (enc.contains("gzip")) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    return in.readAllBytes();
                }
            } else if (enc.contains("deflate")) {
                try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(body))) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            return body; // show what we got
        }
        return body;
    }

    static ResponseData classify(Throwable err, long ms, String host) {
        Throwable t = err;
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof CancellationException) {
            return ResponseData.failed(ResponseData.ErrorKind.CANCELLED, t, "Request cancelled", ms, host);
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage()).toLowerCase(Locale.ROOT);
            if (c instanceof SSLHandshakeException || c instanceof SSLPeerUnverifiedException || c instanceof CertificateException) {
                if (m.contains("subject alternative") || m.contains("no name matching") || m.contains("hostname")) {
                    return ResponseData.failed(ResponseData.ErrorKind.TLS_HOSTNAME, t, c.getMessage(), ms, host);
                }
                if (m.contains("pkix") || m.contains("certification path") || m.contains("trust") || c instanceof CertificateException) {
                    return ResponseData.failed(ResponseData.ErrorKind.TLS_UNTRUSTED, t, c.getMessage(), ms, host);
                }
            }
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SSLException) {
                return ResponseData.failed(ResponseData.ErrorKind.TLS_OTHER, t, c.getMessage(), ms, host);
            }
            if (c instanceof HttpConnectTimeoutException) {
                return ResponseData.failed(ResponseData.ErrorKind.TIMEOUT, t, "Couldn't connect in time", ms, host);
            }
            if (c instanceof HttpTimeoutException) {
                return ResponseData.failed(ResponseData.ErrorKind.TIMEOUT, t, "No response before the timeout", ms, host);
            }
            if (c instanceof UnresolvedAddressException || c instanceof UnknownHostException) {
                return ResponseData.failed(ResponseData.ErrorKind.DNS, t, "Couldn't find host " + host, ms, host);
            }
            if (c instanceof ConnectException) {
                return ResponseData.failed(ResponseData.ErrorKind.CONNECT, t, "Connection refused by " + host, ms, host);
            }
        }
        return ResponseData.failed(ResponseData.ErrorKind.OTHER, t,
                t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), ms, host);
    }
}
