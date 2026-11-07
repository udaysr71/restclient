package dev.restclient.ui;

import dev.restclient.testkit.AfterEach;
import dev.restclient.testkit.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static dev.restclient.testkit.Assertions.assertTrue;

/**
 * WCAG 2.1 AA contrast for the palette, in both themes: 4.5:1 for text (including text on the accent colour and
 * status badges) and 3:1 for the outlines that show where an input is (non-text contrast, 1.4.11).
 */
class ContrastTest {
    @AfterEach
    void reset() {
        Theme.usePalette(false);
    }

    @Test
    void lightPaletteMeetsAA() {
        check(Theme.Palette.LIGHT);
    }

    @Test
    void darkPaletteMeetsAA() {
        check(Theme.Palette.DARK);
    }

    @Test
    void solarizedSepiaMeetsAA() {
        check(Theme.Palette.SEPIA);
    }

    @Test
    void solarizedDarkMeetsAA() {
        check(Theme.Palette.SOLARIZED_DARK);
    }

    /** Every Solarized override names a real colour token (a typo would silently fall back to Light/Dark). */
    @Test
    void solarizedOverridesNameRealTokens() throws Exception {
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : Theme.class.getDeclaredMethods()) {
            if (m.getReturnType() == Color.class && m.getParameterCount() == 0) tokens.add(m.getName());
        }
        for (dev.restclient.model.HttpMethod hm : dev.restclient.model.HttpMethod.values()) tokens.add("method." + hm.name());
        for (Theme.Palette p : Theme.Palette.values()) {
            for (String k : p.overrides.keySet()) assertTrue(tokens.contains(k), p + " overrides unknown token " + k);
        }
    }

    private static void check(Theme.Palette palette) {
        Theme.usePalette(palette);
        List<String> failures = new ArrayList<>();
        // text on every surface it appears on
        for (Supplier<Color> bg : List.<Supplier<Color>>of(Theme::panelBg, Theme::sidebarBg, Theme::toolbarBg, Theme::fieldBg,
                Theme::codeBg, Theme::menuBg, Theme::buttonBg, Theme::selectionBg)) {
            need(failures, "text", Theme.text(), bg.get(), 4.5);
            need(failures, "secondary text", Theme.secondary(), bg.get(), 4.5);
        }
        need(failures, "link", Theme.link(), Theme.panelBg(), 4.5);
        need(failures, "white on accent button", Theme.onAccent(), Theme.accent(), 4.5);
        need(failures, "white on accent hover", Theme.onAccent(), Theme.accentHover(), 4.5);
        need(failures, "ok badge", Theme.okFg(), Theme.okBg(), 4.5);
        need(failures, "error badge", Theme.errFg(), Theme.errBg(), 4.5);
        need(failures, "warning badge", Theme.warnFg(), Theme.warnBg(), 4.5);
        need(failures, "info badge", Theme.infoFg(), Theme.infoBg(), 4.5);
        // syntax and method colours on the code and panel backgrounds
        for (Color c : List.of(Theme.synKey(), Theme.synString(), Theme.synNumber(), Theme.synLiteral(), Theme.synTag(),
                Theme.synAttr())) {
            need(failures, "syntax colour", c, Theme.codeBg(), 4.5);
        }
        for (dev.restclient.model.HttpMethod m : dev.restclient.model.HttpMethod.values()) {
            need(failures, "method " + m, Theme.methodColor(m), Theme.panelBg(), 4.5);
        }
        // input outlines (non-text)
        need(failures, "field outline", Theme.fieldBorder(), Theme.fieldBg(), 3.0);
        need(failures, "field outline on panel", Theme.fieldBorder(), Theme.panelBg(), 3.0);
        need(failures, "focus ring", Theme.accent(), Theme.fieldBg(), 3.0);
        assertTrue(failures.isEmpty(), palette + " palette: " + failures);
    }

    private static void need(List<String> failures, String what, Color fg, Color bg, double min) {
        double r = ratio(fg, bg);
        if (r < min) failures.add(String.format("%s %.2f:1 < %.1f:1 (%s on %s)", what, r, min, hex(fg), hex(bg)));
    }

    static double ratio(Color a, Color b) {
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

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }
}
