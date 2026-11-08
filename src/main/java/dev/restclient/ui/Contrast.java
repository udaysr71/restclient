package dev.restclient.ui;

import dev.restclient.model.HttpMethod;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * WCAG 2 contrast checks for a palette: 4.5:1 for text, 3:1 for outlines and the focus ring. Used by the tests
 * for the built-in themes and by Settings to warn about a user theme (warnings only; the theme still loads).
 */
public final class Contrast {
    private Contrast() {
    }

    /** Readable descriptions of every pair below the minimum; empty when the palette passes. Call on the EDT. */
    public static List<String> check(Theme.Palette palette) {
        Theme.Palette saved = Theme.palette();
        Theme.usePalette(palette);
        try {
            List<String> f = new ArrayList<>();
            String[][] surfaces = {{"panelBg", "the window"}, {"sidebarBg", "the sidebar"}, {"toolbarBg", "the toolbar"},
                    {"fieldBg", "fields"}, {"codeBg", "code"}, {"menuBg", "menus"}, {"buttonBg", "buttons"},
                    {"selectionBg", "selected rows"}};
            for (String[] s : surfaces) {
                Color bg = Theme.colorOf(s[0]);
                need(f, "--text on " + s[1], Theme.text(), bg, 4.5);
                need(f, "--secondary on " + s[1], Theme.secondary(), bg, 4.5);
            }
            need(f, "--link on the window", Theme.link(), Theme.panelBg(), 4.5);
            need(f, "white text on --accent", Theme.onAccent(), Theme.accent(), 4.5);
            need(f, "white text on --accentHover", Theme.onAccent(), Theme.accentHover(), 4.5);
            need(f, "--okFg on --okBg", Theme.okFg(), Theme.okBg(), 4.5);
            need(f, "--errFg on --errBg", Theme.errFg(), Theme.errBg(), 4.5);
            need(f, "--warnFg on --warnBg", Theme.warnFg(), Theme.warnBg(), 4.5);
            need(f, "--infoFg on --infoBg", Theme.infoFg(), Theme.infoBg(), 4.5);
            for (String t : List.of("synKey", "synString", "synNumber", "synLiteral", "synTag", "synAttr")) {
                need(f, "--" + t + " on --codeBg", Theme.colorOf(t), Theme.codeBg(), 4.5);
            }
            for (HttpMethod m : HttpMethod.values()) {
                if (m == HttpMethod.OPTIONS) continue; // same colour as HEAD
                need(f, "--" + ThemeFile.cssNameFor("method." + m) + " on the window", Theme.methodColor(m), Theme.panelBg(), 4.5);
            }
            need(f, "--fieldBorder on --fieldBg", Theme.fieldBorder(), Theme.fieldBg(), 3.0);
            need(f, "--fieldBorder on the window", Theme.fieldBorder(), Theme.panelBg(), 3.0);
            need(f, "--accent (focus outline) on --fieldBg", Theme.accent(), Theme.fieldBg(), 3.0);
            return f;
        } finally {
            Theme.usePalette(saved);
        }
    }

    private static void need(List<String> out, String what, Color fg, Color bg, double min) {
        double r = ratio(fg, bg);
        if (r < min) out.add(String.format("Low contrast: %s is %.1f:1 (needs %.1f:1)", what, r, min));
    }

    public static double ratio(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
