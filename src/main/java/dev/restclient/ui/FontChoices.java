package dev.restclient.ui;

import dev.restclient.model.Settings;
import dev.restclient.ui.laf.Paint;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The font and theme pickers in Settings › General. Font lists show every installed family, each previewed in
 * itself, with fonts designed for legibility (Atkinson Hyperlegible, Lexend, OpenDyslexic…) listed first when
 * they're installed. The code list only offers fonts whose letters are all the same width.
 */
final class FontChoices {
    /** Fonts made for low vision or dyslexia, then the widely installed humanist sans fonts known to read well. */
    static final List<String> READABLE_UI = List.of("Atkinson Hyperlegible Next", "Atkinson Hyperlegible", "Lexend",
            "OpenDyslexic", "Andika", "Verdana", "Tahoma");
    static final List<String> READABLE_CODE = List.of("Atkinson Hyperlegible Mono", "OpenDyslexicMono", "Intel One Mono",
            "JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Fira Code", "Source Code Pro");
    private static final Set<String> LOGICAL = Set.of(Font.DIALOG, Font.DIALOG_INPUT, Font.MONOSPACED, Font.SANS_SERIF, Font.SERIF);
    private static final Map<String, Boolean> MONO = new ConcurrentHashMap<>();
    private static final String DEFAULT = "";

    private FontChoices() {
    }

    /** UI fonts: "" (default), then installed readable fonts, then everything else that can show Latin text. */
    static List<String> uiFamilies() {
        return ordered(READABLE_UI, f -> true);
    }

    static List<String> codeFamilies() {
        return ordered(READABLE_CODE, FontChoices::monospaced);
    }

    /** How many of the leading items (after Default) are the recommended ones; a divider is drawn after them. */
    static int recommendedCount(List<String> items, List<String> readable) {
        int n = 0;
        for (int i = 1; i < items.size() && readable.contains(items.get(i)); i++) n++;
        return n;
    }

    private static List<String> ordered(List<String> readable, java.util.function.Predicate<String> keep) {
        Set<String> installed = Theme.installedFamilies();
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        for (String r : readable) if (installed.contains(r) && usable(r) && keep.test(r)) out.add(r);
        for (String f : installed) {
            if (out.contains(f) || f.startsWith(".") || LOGICAL.contains(f) || !usable(f) || !keep.test(f)) continue;
            out.add(f);
        }
        return out;
    }

    /** Symbol and icon fonts can't show the alphabet; they'd make the app unreadable. */
    private static boolean usable(String family) {
        return new Font(family, Font.PLAIN, 12).canDisplayUpTo("AaBbGgQq0123") == -1;
    }

    static boolean monospaced(String family) {
        return MONO.computeIfAbsent(family, f -> {
            BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                FontMetrics fm = g.getFontMetrics(new Font(f, Font.PLAIN, 20));
                int w = fm.charWidth('m');
                return w > 0 && fm.charWidth('i') == w && fm.charWidth('W') == w && fm.charWidth('.') == w;
            } finally {
                g.dispose();
            }
        });
    }

    /**
     * A font picker. {@code current} is the saved family or null; {@code defaultName} is what "Default" resolves
     * to. Selecting an item calls {@code onPick} with the family, or null for the default.
     */
    static JComboBox<String> combo(List<String> items, List<String> readable, String current, String defaultName,
                                   Consumer<String> onPick) {
        JComboBox<String> box = new JComboBox<>(items.toArray(new String[0]));
        box.setMaximumRowCount(14);
        // without a prototype every family would be loaded and measured just to size the box
        box.setPrototypeDisplayValue("Atkinson Hyperlegible Next ");
        int divider = recommendedCount(items, readable);
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                String fam = (String) value;
                String text = fam == null || fam.isEmpty() ? "Default (" + defaultName + ")" : fam;
                JLabel l = (JLabel) super.getListCellRendererComponent(list, text, index, sel, focus);
                Font base = list.getFont();
                // previews: each family in itself (the closed box too, so you see what you picked)
                l.setFont(fam == null || fam.isEmpty() ? base : new Font(fam, Font.PLAIN, base.getSize()));
                // a hairline under Default and under the recommended group
                boolean rule = index == 0 || (divider > 0 && index == divider);
                l.setBorder(BorderFactory.createCompoundBorder(
                        rule ? BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.separator()) : BorderFactory.createEmptyBorder(0, 0, 1, 0),
                        BorderFactory.createEmptyBorder(3, 6, 3, 6)));
                return l;
            }
        });
        box.setSelectedItem(current != null && items.contains(current) ? current : DEFAULT);
        box.addActionListener(e -> {
            String v = (String) box.getSelectedItem();
            onPick.accept(v == null || v.isEmpty() ? null : v);
        });
        return box;
    }

    /** The theme picker: each choice with a swatch of its page colour and accent. */
    static JComboBox<Settings.ThemeMode> themeCombo(Settings.ThemeMode current, Consumer<Settings.ThemeMode> onPick,
                                                    java.util.function.IntSupplier minWidth) {
        // as wide as the font pickers below it, so the Appearance controls line up (and follow zoom)
        JComboBox<Settings.ThemeMode> box = new JComboBox<>(Settings.ThemeMode.values()) {
            @Override
            public java.awt.Dimension getPreferredSize() {
                java.awt.Dimension d = super.getPreferredSize();
                return new java.awt.Dimension(Math.max(d.width, minWidth.getAsInt()), d.height);
            }
        };
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                Settings.ThemeMode m = (Settings.ThemeMode) value;
                JLabel l = (JLabel) super.getListCellRendererComponent(list, m == null ? "" : m.label, index, sel, focus);
                l.setIcon(m == null ? null : swatch(m));
                l.setIconTextGap(8);
                l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
                return l;
            }
        });
        box.setSelectedItem(current);
        box.addActionListener(e -> {
            if (box.getSelectedItem() instanceof Settings.ThemeMode m) onPick.accept(m);
        });
        return box;
    }

    /** A small circle: page colour with an accent dot, split light/dark for "Match System". */
    static Icon swatch(Settings.ThemeMode m) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Paint.smooth(g);
                int s = getIconWidth();
                if (m == Settings.ThemeMode.SYSTEM) {
                    g2.setColor(Theme.Palette.LIGHT.swatchBg);
                    g2.fillArc(x, y, s, s, 90, 180);
                    g2.setColor(Theme.Palette.DARK.swatchBg);
                    g2.fillArc(x, y, s, s, 270, 180);
                } else {
                    Theme.Palette p = Theme.paletteFor(m);
                    g2.setColor(p.swatchBg);
                    g2.fillOval(x, y, s, s);
                    g2.setColor(p.swatchAccent);
                    int d = Math.round(s * 0.42f);
                    g2.fillOval(x + (s - d) / 2, y + (s - d) / 2, d, d);
                }
                g2.setColor(new Color(0x80808080, true));
                g2.drawOval(x, y, s - 1, s - 1);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return Theme.scaled(14);
            }

            @Override
            public int getIconHeight() {
                return Theme.scaled(14);
            }
        };
    }
}
