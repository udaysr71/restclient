package dev.restclient.http;

/** What happened to one file in the certs folder during the last load. */
public record CertFileStatus(String fileName, Status status, String detail, int trustedCerts, int clientKeys) {
    public enum Status { LOADED, FAILED, IGNORED }

    public boolean isKeystore() {
        String n = fileName.toLowerCase();
        return n.endsWith(".p12") || n.endsWith(".pfx") || n.endsWith(".jks") || n.endsWith(".keystore");
    }
}
