package dev.restclient.ui;

import javax.swing.Icon;
import java.util.HashMap;
import java.util.Map;

/**
 * Icons from {@code /icons/*.svg}, drawn by {@link SvgIcon}. Strokes use a small palette
 * (#6E6E6E grey, #389FD6 blue, #59A869 green, #EDA200 yellow, #DB5860 red) that is mapped to theme colors.
 */
public final class Icons {
    private static final Map<String, Icon> CACHE = new HashMap<>();

    private Icons() {
    }

    public static Icon get(String name) {
        return get(name, 16);
    }

    public static Icon get(String name, int size) {
        return CACHE.computeIfAbsent(name + "@" + size, k -> new SvgIcon("icons/" + name + ".svg", size, size));
    }
}
