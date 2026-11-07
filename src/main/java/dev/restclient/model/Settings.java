package dev.restclient.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** User preferences, stored in ~/.restclient/settings.json. */
public class Settings {
    public enum ThemeMode {
        SYSTEM("Match System"), LIGHT("Light"), DARK("Dark"), SEPIA("Solarized Sepia"), SOLARIZED_DARK("Solarized Dark");

        public final String label;

        ThemeMode(String label) {
            this.label = label;
        }
    }

    public ThemeMode theme = ThemeMode.SYSTEM;
    /** Text and control size, 1.0 = 100%. Changed with Zoom In / Zoom Out / Actual Size. */
    public double zoom = 1.0;
    /** Font for labels, menus and buttons; null = the platform's (San Francisco, Segoe UI…). */
    public String uiFontFamily;
    /** Font for URLs, bodies and code; null = the platform's monospaced font. */
    public String codeFontFamily;
    /** Turns off slides, fades and cross-fades. */
    public boolean reduceMotion;
    /** Default for requests that don't override it. Skips certificate AND hostname checks. */
    public boolean insecureByDefault;
    /** Show the orange badge next to Send while verification is off. */
    public boolean showInsecureBadge = true;
    public int defaultTimeoutSeconds = 30;
    public int connectTimeoutSeconds = 15;
    public boolean followRedirects = true;
    public boolean useSystemProxy = true;
    public int historyLimit = 200;
    public boolean restoreTabs = true;
    public String activeEnvironmentId;
    /** Keystore/truststore passwords by file name in the certs folder. */
    public Map<String, String> keystorePasswords = new LinkedHashMap<>();
}
