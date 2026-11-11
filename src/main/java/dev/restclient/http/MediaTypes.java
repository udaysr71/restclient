package dev.restclient.http;

import java.util.Locale;
import java.util.Map;

/** Content types for uploaded files, guessed from the file name. */
public final class MediaTypes {
    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
            Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"), Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"), Map.entry("md", "text/markdown"), Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"), Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"), Map.entry("mp3", "audio/mpeg"), Map.entry("mp4", "video/mp4"),
            Map.entry("wav", "audio/wav"));

    private MediaTypes() {
    }

    /** The type for a file name or path; {@code application/octet-stream} when the extension isn't known. */
    public static String forFile(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        int sep = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        String ext = dot > sep ? n.substring(dot + 1) : "";
        return BY_EXTENSION.getOrDefault(ext, "application/octet-stream");
    }
}
