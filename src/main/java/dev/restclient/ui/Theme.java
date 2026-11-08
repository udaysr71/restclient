package dev.restclient.ui;

import dev.restclient.model.HttpMethod;
import dev.restclient.model.Settings;
import dev.restclient.ui.laf.RcLookAndFeel;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Colors, fonts and theme switching. Every color is looked up when something paints,
 * so switching between light and dark applies immediately.
 */
public final class Theme {
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static String monoFamily;
    private static Font uiFont;
    private static Palette palette = Palette.LIGHT;
    /** User-chosen font families (null = the platform default). */
    private static String uiFamilyChoice;
    private static String codeFamilyChoice;
    private static boolean installed;
    private static float zoom = 1f;
    /** Zoom levels, as in Safari: Zoom In and Zoom Out step through these. */
    public static final float[] ZOOM_STEPS = {0.75f, 0.85f, 1f, 1.1f, 1.25f, 1.4f, 1.6f, 1.8f, 2f};

    private Theme() {
    }

    // ---- look and feel ---------------------------------------------------------------------

    /** Installs the app's look and feel for the given mode. Call before creating windows, and again to switch live. */
    public static void apply(Settings.ThemeMode mode, boolean live) {
        apply(mode, null, live);
    }

    /** As {@link #apply(Settings.ThemeMode, boolean)}; {@code custom} names the user theme for {@code CUSTOM}. */
    public static void apply(Settings.ThemeMode mode, String custom, boolean live) {
        Palette want = paletteFor(mode, custom);
        if (installed && want == palette) return;
        palette = want;
        try {
            UIManager.setLookAndFeel(new RcLookAndFeel());
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't install the look and feel", e);
        }
        installed = true;
        if (live) refreshAll();
    }

    private static void refreshAll() {
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
            reapply(w);
            w.repaint();
        }
        for (Runnable r : new ArrayList<>(LISTENERS)) r.run();
    }

    private static final String BG = "rc.themeBg";
    private static final String FG = "rc.themeFg";

    /** Sets a background that follows theme switches (a plain setBackground(Theme.x()) would keep the old colour). */
    public static void bg(javax.swing.JComponent c, java.util.function.Supplier<Color> color) {
        c.putClientProperty(BG, color);
        c.setBackground(color.get());
    }

    /** Sets a foreground that follows theme switches. */
    public static void fg(javax.swing.JComponent c, java.util.function.Supplier<Color> color) {
        c.putClientProperty(FG, color);
        c.setForeground(color.get());
    }

    @SuppressWarnings("unchecked")
    private static void reapply(java.awt.Component c) {
        if (c instanceof javax.swing.JComponent j) {
            if (j.getClientProperty(BG) instanceof java.util.function.Supplier<?> s) j.setBackground((Color) s.get());
            if (j.getClientProperty(FG) instanceof java.util.function.Supplier<?> s) j.setForeground((Color) s.get());
        }
        if (c instanceof java.awt.Container k) for (java.awt.Component child : k.getComponents()) reapply(child);
    }

    public static float zoom() {
        return zoom;
    }

    /** A pixel size at the current zoom. */
    public static int scaled(int px) {
        return Math.round(px * zoom);
    }

    /** The next zoom step in the given direction (+1 in, -1 out), or 1.0 for 0. */
    public static float zoomStep(int direction) {
        if (direction == 0) return 1f;
        if (direction > 0) {
            for (float s : ZOOM_STEPS) if (s > zoom + 0.001f) return s;
            return ZOOM_STEPS[ZOOM_STEPS.length - 1];
        }
        for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) if (ZOOM_STEPS[i] < zoom - 0.001f) return ZOOM_STEPS[i];
        return ZOOM_STEPS[0];
    }

    /**
     * Sets the zoom. Before windows exist pass {@code live=false}; afterwards the look and feel is reinstalled and
     * fonts and row heights that components set themselves are rescaled, so open windows resize in place.
     */
    public static void setZoom(float z, boolean live) {
        z = Math.max(ZOOM_STEPS[0], Math.min(ZOOM_STEPS[ZOOM_STEPS.length - 1], z));
        float old = zoom;
        if (Math.abs(old - z) < 0.001f) return;
        zoom = z;
        if (!live || !installed) return;
        float ratio = z / old;
        reinstall(f -> f.deriveFont(f.getSize2D() * ratio), ratio);
    }

    /**
     * Sets the UI and code font families ({@code null} or blank = the platform default). With {@code live}, open
     * windows switch over in place: fonts that components set themselves keep their size and style and only
     * change family.
     */
    public static void setFonts(String uiFamily, String codeFamily, boolean live) {
        String ui = blankToNull(uiFamily);
        String code = blankToNull(codeFamily);
        if (java.util.Objects.equals(ui, uiFamilyChoice) && java.util.Objects.equals(code, codeFamilyChoice)) return;
        Font oldUiFont = uiFont();
        Set<String> oldUi = new java.util.HashSet<>(List.of(oldUiFont.getFamily(), oldUiFont.getName()));
        String oldMono = monoFamily();
        uiFamilyChoice = ui;
        codeFamilyChoice = code;
        uiFont = null;
        monoFamily = null;
        if (!live || !installed) return;
        String newUi = uiFont().getName();
        String newMono = monoFamily();
        reinstall(f -> {
            String to = oldMono.equals(f.getFamily()) || oldMono.equals(f.getName()) ? newMono
                    : oldUi.contains(f.getFamily()) || oldUi.contains(f.getName()) ? newUi : null;
            return to == null ? f : new Font(to, f.getStyle(), f.getSize()).deriveFont(f.getSize2D());
        }, 1f);
    }

    public static String uiFontChoice() {
        return uiFamilyChoice;
    }

    public static String codeFontChoice() {
        return codeFamilyChoice;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Reinstalls the look and feel, then maps fonts components set themselves and scales their row heights. */
    private static void reinstall(java.util.function.UnaryOperator<Font> fontMap, float ratio) {
        java.util.Map<java.awt.Component, Font> fonts = new java.util.IdentityHashMap<>();
        java.util.Map<java.awt.Component, Integer> rows = new java.util.IdentityHashMap<>();
        for (Window w : Window.getWindows()) record(w, fonts, rows);
        try {
            UIManager.setLookAndFeel(new RcLookAndFeel());
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't install the look and feel", e);
        }
        for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w);
        // Fonts and row heights the look and feel just replaced are already at the new size; the ones a component
        // set itself survived updateUI unchanged and are scaled here.
        fonts.forEach((c, f) -> {
            if (c.getFont() == f) c.setFont(fontMap.apply(f));
        });
        if (ratio != 1f) rows.forEach((c, h) -> {
            int nh = Math.max(1, Math.round(h * ratio));
            if (c instanceof javax.swing.JTable t && t.getRowHeight() == h) t.setRowHeight(nh);
            else if (c instanceof javax.swing.JTree t && t.getRowHeight() == h) t.setRowHeight(nh);
            else if (c instanceof javax.swing.JList<?> l && l.getFixedCellHeight() == h) l.setFixedCellHeight(nh);
        });
        for (Runnable r : new ArrayList<>(LISTENERS)) r.run();
        for (Window w : Window.getWindows()) {
            w.validate();
            w.repaint();
        }
    }

    private static void record(java.awt.Component c, java.util.Map<java.awt.Component, Font> fonts,
                               java.util.Map<java.awt.Component, Integer> rows) {
        if (c.isFontSet() && c.getFont() != null && !(c.getFont() instanceof javax.swing.plaf.UIResource)) {
            fonts.put(c, c.getFont());
        }
        if (c instanceof javax.swing.JTable t) rows.put(c, t.getRowHeight());
        else if (c instanceof javax.swing.JTree t && t.getRowHeight() > 0) rows.put(c, t.getRowHeight());
        else if (c instanceof javax.swing.JList<?> l && l.getFixedCellHeight() > 0) rows.put(c, l.getFixedCellHeight());
        if (c instanceof java.awt.Container k) for (java.awt.Component child : k.getComponents()) record(child, fonts, rows);
        if (c instanceof javax.swing.JMenu m) record(m.getPopupMenu(), fonts, rows);
    }

    /** The palette a theme setting resolves to right now ({@code SYSTEM} follows the OS's light/dark choice). */
    public static Palette paletteFor(Settings.ThemeMode mode) {
        return paletteFor(mode, null);
    }

    /** With {@code CUSTOM}, the user theme {@code custom}; Light if that file is gone. */
    public static Palette paletteFor(Settings.ThemeMode mode, String custom) {
        return switch (mode) {
            case CUSTOM -> {
                Palette p = Themes.find(custom);
                yield p != null ? p : Palette.LIGHT;
            }
            case DARK -> Palette.DARK;
            case LIGHT -> Palette.LIGHT;
            case SEPIA -> Palette.SEPIA;
            case SOLARIZED_DARK -> Palette.SOLARIZED_DARK;
            case SYSTEM -> Platform.systemPrefersDark() ? Palette.DARK : Palette.LIGHT;
        };
    }

    public static Palette palette() {
        return palette;
    }

    /** For tests: switches the palette without installing the look and feel. */
    static void usePalette(boolean darkPalette) {
        palette = darkPalette ? Palette.DARK : Palette.LIGHT;
    }

    static void usePalette(Palette p) {
        palette = p;
    }

    /** Accent used as text (links): on dark backgrounds a lighter tint keeps 4.5:1 contrast. */
    public static Color link() { return pick("link", 0x3A52CF, 0x8C9DFF); }

    /** Whether the current palette is a dark one (Dark or Solarized Dark). */
    public static boolean dark() {
        return palette.dark;
    }

    /** Runs after a live theme switch (to refresh custom colors). */
    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    // ---- fonts -----------------------------------------------------------------------------

    /** The platform's UI font: San Francisco on macOS, Segoe UI on Windows, the desktop default on Linux. */
    public static Font uiFont() {
        if (uiFont == null) {
            Set<String> available = installedFamilies();
            String family;
            if (uiFamilyChoice != null && available.contains(uiFamilyChoice)) {
                family = uiFamilyChoice;
            } else if (Platform.isMac()) {
                family = ".AppleSystemUIFont"; // resolves to San Francisco on JDK 11+
            } else {
                family = Font.SANS_SERIF;
                String[] preferred = Platform.isWindows()
                        // Not "Segoe UI Variable": Java can't render variable fonts well.
                        ? new String[]{"Segoe UI"}
                        : new String[]{"Segoe UI", "Inter", "Cantarell", "Ubuntu", "Noto Sans", "Open Sans", "DejaVu Sans",
                        "Liberation Sans"};
                for (String p : preferred) {
                    if (available.contains(p)) {
                        family = p;
                        break;
                    }
                }
            }
            uiFont = new Font(family, Font.PLAIN, 13);
        }
        return zoom == 1f ? uiFont : uiFont.deriveFont(13f * zoom);
    }

    public static Font mono(float size) {
        return new Font(monoFamily(), Font.PLAIN, Math.round(size * zoom));
    }

    /** The family used for code, URLs and bodies: the user's choice if installed, otherwise the platform's. */
    public static String monoFamily() {
        if (monoFamily == null) {
            Set<String> available = installedFamilies();
            if (codeFamilyChoice != null && available.contains(codeFamilyChoice)) return monoFamily = codeFamilyChoice;
            monoFamily = defaultMonoFamily(available);
        }
        return monoFamily;
    }

    /** What the code font is when the user hasn't picked one. */
    public static String defaultMonoFamily() {
        return defaultMonoFamily(installedFamilies());
    }

    private static String defaultMonoFamily(Set<String> available) {
        String[] preferred = {"SF Mono", "Menlo", "JetBrains Mono", "Cascadia Mono", "Consolas", "DejaVu Sans Mono",
                "Ubuntu Mono", "Liberation Mono"};
        for (String p : preferred) if (available.contains(p)) return p;
        return Font.MONOSPACED;
    }

    /** What the UI font is when the user hasn't picked one (shown as "Default (…)" in Settings). */
    public static String defaultUiFamily() {
        String saved = uiFamilyChoice;
        Font cached = uiFont;
        uiFamilyChoice = null;
        uiFont = null;
        try {
            String f = uiFont().getFamily();
            return f.startsWith(".") ? "San Francisco" : f;
        } finally {
            uiFamilyChoice = saved;
            uiFont = cached;
        }
    }

    private static Set<String> families;

    public static Set<String> installedFamilies() {
        if (families == null) {
            families = new java.util.LinkedHashSet<>(java.util.List.of(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        }
        return families;
    }

    public static Font ui(int style, float size) {
        return uiFont().deriveFont(style, size * zoom);
    }

    // ---- colors ----------------------------------------------------------------------------
    // One monochromatic family (hue 230, a cool slate) for text, strokes, fills and backgrounds, plus an
    // indigo-blue brand colour, so every surface reads as part of the same system (Practical UI, "Colour").
    // Text and input borders keep at least 4.5:1 and 3:1 contrast against their backgrounds.

    private static Color pick(String token, int light, int darkRgb) {
        Integer o = palette.overrides.get(token);
        return new Color(o != null ? o : palette.dark ? darkRgb : light);
    }

    private static Color pickA(String token, int light, int darkArgb) {
        Integer o = palette.overrides.get(token);
        return new Color(o != null ? o : palette.dark ? darkArgb : light, true);
    }

    /**
     * A colour scheme. Light and Dark are the app's own values (in this file); every other palette is a theme
     * file, either bundled (Solarized Sepia and Solarized Dark, in {@code /themes}) or the user's own in
     * {@code ~/.restclient/themes}, laid over Light or Dark (see {@link ThemeFile} and {@link Themes}).
     */
    public static final class Palette {
        public static final Palette LIGHT = new Palette("light", "Light", false, null, java.util.Map.of(), List.of(), null);
        public static final Palette DARK = new Palette("dark", "Dark", true, null, java.util.Map.of(), List.of(), null);
        public static final Palette SEPIA = Themes.bundled("solarized-sepia");
        public static final Palette SOLARIZED_DARK = Themes.bundled("solarized-dark");

        /** "light", "dark", "solarized-sepia", or a user file's name without ".css". */
        public final String id;
        public final String label;
        public final boolean dark;
        /** Where the light/dark toggle goes from here (a palette id), or null for the opposite of Light/Dark. */
        public final String toggle;
        /** Problems found reading the file, and contrast failures; empty for the built-in palettes. */
        public final List<String> warnings;
        /** The user's file, or null for built-in and bundled palettes. */
        public final java.nio.file.Path file;
        final java.util.Map<String, Integer> overrides;

        Palette(String id, String label, boolean dark, String toggle, java.util.Map<String, Integer> overrides,
                List<String> warnings, java.nio.file.Path file) {
            this.id = id;
            this.label = label;
            this.dark = dark;
            this.toggle = toggle;
            this.overrides = java.util.Map.copyOf(overrides);
            this.warnings = List.copyOf(warnings);
            this.file = file;
        }

        /** The four palettes that ship with the app. */
        public static List<Palette> builtIns() {
            return List.of(LIGHT, DARK, SEPIA, SOLARIZED_DARK);
        }

        /** For swatches: the page colour and the accent. */
        public Color swatchBg() {
            return new Color(overrides.getOrDefault("panelBg", dark ? 0xFF16171D : 0xFFFFFFFF), true);
        }

        public Color swatchAccent() {
            return new Color(overrides.getOrDefault("accent", dark ? 0xFF5068DE : 0xFF4059D6), true);
        }

        /** The colour this palette gives a token: its own value, or its base's. */
        public int argb(String token) {
            Palette saved = palette;
            palette = this;
            try {
                return colorOf(token).getRGB();
            } finally {
                palette = saved;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A colour by token name ("accent", "method.GET"…), for theme export and checks. */
    public static Color colorOf(String token) {
        if (token.startsWith("method.")) return methodColor(HttpMethod.valueOf(token.substring(7)));
        try {
            return (Color) Theme.class.getMethod(token).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unknown colour " + token, e);
        }
    }

    public static Color accent() { return pick("accent", 0x4059D6, 0x5068DE); }
    public static Color accentHover() { return pick("accentHover", 0x324AC2, 0x596DD4); }
    public static Color accentPressed() { return pick("accentPressed", 0x253BA8, 0x3F55C4); }
    public static Color onAccent() { return Color.WHITE; }
    public static Color focusRing() { return pickA("focusRing", 0x594059D6, 0x665068DE); }
    public static Color text() { return pick("text", 0x1E2133, 0xE6E8F2); }
    public static Color secondary() { return pick("secondary", 0x5A5E73, 0xA2A5B8); }
    public static Color disabledText() { return pick("disabledText", 0x9C9FAD, 0x656875); }
    public static Color iconColor() { return pick("iconColor", 0x56596B, 0xAAADBD); }
    public static Color separator() { return pick("separator", 0xDADDEB, 0x31333D); }
    public static Color sidebarBg() { return pick("sidebarBg", 0xF1F2F9, 0x1B1C22); }
    public static Color toolbarBg() { return pick("toolbarBg", 0xF5F6FB, 0x202129); }
    public static Color panelBg() { return pick("panelBg", 0xFFFFFF, 0x16171D); }
    public static Color editorBg() { return pick("editorBg", 0xFFFFFF, 0x16171D); }
    public static Color codeBg() { return pick("codeBg", 0xF9F9FC, 0x13141A); }
    public static Color selectionBg() { return pick("selectionBg", 0xE0E6FF, 0x323857); }
    public static Color textSelection() { return pick("textSelection", 0xC7D0FF, 0x444C75); }
    public static Color subtleBg() { return pick("subtleBg", 0xEBEDF5, 0x23242B); }
    public static Color fieldBg() { return pick("fieldBg", 0xFFFFFF, 0x1E1F26); }
    public static Color fieldBorder() { return pick("fieldBorder", 0x8D92A8, 0x676A7A); }
    public static Color buttonBg() { return pick("buttonBg", 0xFFFFFF, 0x292B33); }
    public static Color buttonHover() { return pick("buttonHover", 0xF0F1F7, 0x343640); }
    public static Color buttonPressed() { return pick("buttonPressed", 0xDDDFEB, 0x40424C); }
    public static Color hoverSubtle() { return pickA("hoverSubtle", 0x0F1E2550, 0x14C8D0FF); }
    public static Color pressedSubtle() { return pickA("pressedSubtle", 0x1E1E2550, 0x24C8D0FF); }
    public static Color segmentSelected() { return pick("segmentSelected", 0xFFFFFF, 0x40424C); }
    public static Color menuBg() { return pick("menuBg", 0xFFFFFF, 0x23242B); }
    public static Color tooltipBg() { return pick("tooltipBg", 0xF9F9FC, 0x23242B); }
    public static Color thumb() { return pickA("thumb", 0x38000000, 0x40FFFFFF); }
    public static Color thumbHover() { return pickA("thumbHover", 0x5C000000, 0x66FFFFFF); }
    public static Color findMatch() { return pickA("findMatch", 0x66FFD60A, 0x55B38F00); }

    public static Color okBg() { return pick("okBg", 0xE3F4E8, 0x1E3A27); }
    public static Color okFg() { return pick("okFg", 0x16722F, 0x56D364); }
    public static Color infoBg() { return pick("infoBg", 0xE4EEFB, 0x1C3350); }
    public static Color infoFg() { return pick("infoFg", 0x0062CC, 0x79B8FF); }
    public static Color warnBg() { return pick("warnBg", 0xFFF4E5, 0x3D2A12); }
    public static Color warnFg() { return pick("warnFg", 0x8A4500, 0xF5B261); }
    public static Color warnIcon() { return pick("warnIcon", 0xC77C02, 0xF5B261); }
    public static Color warnBorder() { return pick("warnBorder", 0xF0B46A, 0x8A5A1E); }
    public static Color errBg() { return pick("errBg", 0xFCE8E6, 0x4A1F1C); }
    public static Color errFg() { return pick("errFg", 0xB3261E, 0xFF9A92); }

    public static Color varOkBg() { return pick("varOkBg", 0xE2F3EC, 0x1E3A30); }
    public static Color varOkBorder() { return pick("varOkBorder", 0x8FCFB4, 0x2F6B52); }
    public static Color varMissingBg() { return pick("varMissingBg", 0xFCE8E6, 0x4A1F1C); }
    public static Color varMissingLine() { return pick("varMissingLine", 0xB3261E, 0xFF7B72); }

    // syntax colors
    public static Color synKey() { return pick("synKey", 0x6F3BB8, 0xC4A2FF); }
    public static Color synString() { return pick("synString", 0x1A7F37, 0x7EE2A8); }
    public static Color synNumber() { return pick("synNumber", 0x0062CC, 0x79B8FF); }
    public static Color synLiteral() { return pick("synLiteral", 0xC0271D, 0xFF8F87); }
    public static Color synPunct() { return pick("synPunct", 0x6E6E73, 0x8E8E93); }
    public static Color synTag() { return pick("synTag", 0x0062CC, 0x79B8FF); }
    public static Color synAttr() { return pick("synAttr", 0xA35200, 0xE3A33B); }
    public static Color synComment() { return pick("synComment", 0x8E8E93, 0x6E6E73); }

    public static Color methodColor(HttpMethod m) {
        if (m == null) return secondary();
        return switch (m) {
            case GET -> pick("method.GET", 0x1A7F37, 0x3FB950);
            case POST -> pick("method.POST", 0xA35200, 0xE3A33B);
            case PUT -> pick("method.PUT", 0x0062CC, 0x58A6FF);
            case PATCH -> pick("method.PATCH", 0x7A3FC4, 0xBC8CFF);
            case DELETE -> pick("method.DELETE", 0xC0271D, 0xFF7B72);
            case HEAD, OPTIONS -> pick("method.HEAD", 0x636366, 0xA1A1A6);
        };
    }

    /** Background/foreground for a status badge. */
    public static Color[] statusColors(int status) {
        if (status >= 200 && status < 300) return new Color[]{okBg(), okFg()};
        if (status >= 300 && status < 400) return new Color[]{infoBg(), infoFg()};
        if (status >= 400 && status < 500) return new Color[]{warnBg(), warnFg()};
        return new Color[]{errBg(), errFg()};
    }

    public static Color hex(String hex, Color fallback) {
        try {
            return Color.decode(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Mixes two colors; {@code t} = 0 gives {@code a}, 1 gives {@code b}. */
    public static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    // ---- app icon --------------------------------------------------------------------------

    /** Blue rounded tile with two opposing arrows, drawn in code so no image files are needed. */
    public static List<Image> appIcons() {
        List<Image> out = new ArrayList<>();
        for (int s : new int[]{16, 32, 64, 128, 256, 512}) out.add(appIcon(s));
        return out;
    }

    public static BufferedImage appIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size;
        float pad = s * 0.06f;
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x6E86FF), 0, s, new Color(0x3346C8)));
        g.fill(new RoundRectangle2D.Float(pad, pad, s - 2 * pad, s - 2 * pad, s * 0.42f, s * 0.42f));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.075f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float l = s * 0.28f;
        float r = s * 0.72f;
        float y1 = s * 0.39f;
        float y2 = s * 0.61f;
        float a = s * 0.11f;
        Path2D p = new Path2D.Float();
        p.moveTo(l, y1);
        p.lineTo(r, y1);
        p.moveTo(r - a, y1 - a);
        p.lineTo(r, y1);
        p.lineTo(r - a, y1 + a);
        p.moveTo(r, y2);
        p.lineTo(l, y2);
        p.moveTo(l + a, y2 - a);
        p.lineTo(l, y2);
        p.lineTo(l + a, y2 + a);
        g.draw(p);
        g.dispose();
        return img;
    }
}
