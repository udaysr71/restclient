package dev.restclient.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** OS detection and the few OS integrations that need shelling out (dark mode). */
public final class Platform {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private Platform() {
    }

    public static boolean isMac() {
        return OS.contains("mac");
    }

    public static boolean isWindows() {
        return OS.contains("win");
    }

    public static boolean isLinux() {
        return !isMac() && !isWindows();
    }

    /** True when the OS is set to a dark appearance. Never throws; falls back to light. */
    public static boolean systemPrefersDark() {
        try {
            if (isMac()) {
                return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
            }
            if (isWindows()) {
                String out = run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                return out.contains("0x0");
            }
            String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme.contains("dark")) return true;
            String theme = run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
            return theme.toLowerCase(Locale.ROOT).contains("dark");
        } catch (Exception e) {
            return false;
        }
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "";
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
