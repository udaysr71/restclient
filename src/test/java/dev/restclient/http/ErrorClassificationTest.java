package dev.restclient.http;

import dev.restclient.testkit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.UnresolvedAddressException;

import static dev.restclient.testkit.Assertions.assertEquals;

/** Failures are sorted into kinds the response panel can explain in plain words. */
class ErrorClassificationTest {
    @Test
    void unknownHostsRefusedConnectionsAndProxies() {
        ConnectException dns = new ConnectException();
        dns.initCause(new UnresolvedAddressException());
        assertEquals(ResponseData.ErrorKind.DNS, HttpService.classify(dns, 5, "nope.invalid").errorKind);
        assertEquals(ResponseData.ErrorKind.CONNECT, HttpService.classify(new ConnectException(), 5, "h").errorKind);
        ResponseData proxy = HttpService.classify(new IOException("Tunnel failed, got: 403"), 5, "blocked.example");
        assertEquals(ResponseData.ErrorKind.PROXY, proxy.errorKind);
        assertEquals("The proxy answered 403", proxy.errorMessage);
        assertEquals(ResponseData.ErrorKind.OTHER, HttpService.classify(new IOException("weird"), 5, "h").errorKind);
    }
}
