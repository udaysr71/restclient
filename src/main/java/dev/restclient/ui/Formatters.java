package dev.restclient.ui;


import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.util.Locale;

/** Pretty-printing for JSON/XML and human-friendly sizes and durations. */
public final class Formatters {
    private Formatters() {
    }

    public static boolean looksLikeJson(String contentType, String body) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("json")) return true;
        String t = body == null ? "" : body.stripLeading();
        return ct.isEmpty() && (t.startsWith("{") || t.startsWith("["));
    }

    public static boolean looksLikeXml(String contentType, String body) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("xml")) return true;
        String t = body == null ? "" : body.stripLeading();
        return ct.isEmpty() && t.startsWith("<?xml");
    }

    public static boolean looksLikeHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html");
    }

    /** Pretty JSON with two-space indent, or null if it doesn't parse. Numbers and escapes are kept as written. */
    public static String prettyJson(String text) {
        return dev.restclient.json.Json.reformat(text);
    }

    /** Pretty XML, or null if it doesn't parse. External entities and DTDs are never fetched. */
    public static String prettyXml(String text) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            if (!text.stripLeading().startsWith("<?xml")) t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            t.transform(new StreamSource(new StringReader(text.trim())), new StreamResult(out));
            // The transformer ends lines with the platform separator (\r\n on Windows); editors here use \n.
            String s = out.toString().replace("\r\n", "\n").replace('\r', '\n');
            return s.replaceFirst("^(<\\?xml[^>]*\\?>)\\s*", "$1\n").replaceAll("(?m)^[ \t]*\n", "");
        } catch (Exception e) {
            return null;
        }
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        DecimalFormat f = new DecimalFormat("0.#");
        if (bytes < 1024 * 1024) return f.format(bytes / 1024.0) + " KB";
        return f.format(bytes / (1024.0 * 1024.0)) + " MB";
    }

    public static String duration(long ms) {
        if (ms < 1000) return ms + " ms";
        return new DecimalFormat("0.0").format(ms / 1000.0) + " s";
    }
}