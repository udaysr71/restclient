package dev.restclient.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JWindow;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Soft drop shadows for floating surfaces (menus, popovers, tooltips, the command palette), as macOS and
 * Windows 11 draw them. A shadow needs a see-through window, so heavyweight popups only get one where the
 * platform supports per-pixel translucency; otherwise they stay flat.
 */
public final class Shadow {
    /** Room around the content for the shadow: more below than above, like a light from overhead. */
    public static final Insets INSETS = new Insets(6, 10, 14, 10);
    private static final Map<String, BufferedImage> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
            return size() > 24;
        }
    };
    private static Boolean translucency;

    private Shadow() {
    }

    public static boolean translucencySupported() {
        if (translucency == null) {
            try {
                GraphicsDevice d = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                translucency = !GraphicsEnvironment.isHeadless() && (Platform.isWindows() || Platform.isMac())
                        && d.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
                        && !Boolean.getBoolean("restclient.noShadows");
            } catch (RuntimeException e) {
                translucency = false;
            }
        }
        return translucency;
    }

    /** Paints a blurred shadow for a rounded rectangle occupying {@code (x, y, w, h)}. */
    public static void paint(Graphics g, int x, int y, int w, int h, int arc) {
        if (w <= 0 || h <= 0) return;
        boolean dark = Theme.dark();
        int blur = 10;
        String key = w + "x" + h + ":" + arc + ":" + dark;
        BufferedImage img = CACHE.get(key);
        if (img == null) {
            img = render(w, h, arc, blur, dark ? 0.55f : 0.20f);
            CACHE.put(key, img);
        }
        g.drawImage(img, x - blur, y - blur + 4, null);
    }

    private static BufferedImage render(int w, int h, int arc, int blur, float opacity) {
        int iw = w + blur * 2;
        int ih = h + blur * 2;
        int[] a = new int[iw * ih];
        // shape mask
        BufferedImage mask = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        mg.setColor(Color.BLACK);
        mg.fill(new RoundRectangle2D.Float(blur, blur, w, h, arc, arc));
        mg.dispose();
        for (int yy = 0; yy < ih; yy++) for (int xx = 0; xx < iw; xx++) a[yy * iw + xx] = mask.getRGB(xx, yy) >>> 24;
        // two box-blur passes in each direction approximate a Gaussian
        int r = blur / 2;
        for (int pass = 0; pass < 2; pass++) {
            a = boxBlur(a, iw, ih, r, true);
            a = boxBlur(a, iw, ih, r, false);
        }
        BufferedImage out = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < a.length; i++) {
            int alpha = Math.min(255, Math.round(a[i] * opacity));
            out.setRGB(i % iw, i / iw, alpha << 24);
        }
        return out;
    }

    private static int[] boxBlur(int[] src, int w, int h, int r, boolean horizontal) {
        int[] dst = new int[src.length];
        int n = 2 * r + 1;
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            int sum = 0;
            for (int i = -r; i <= r; i++) sum += at(src, w, h, line, i, horizontal);
            for (int i = 0; i < len; i++) {
                int idx = horizontal ? line * w + i : i * w + line;
                dst[idx] = sum / n;
                sum += at(src, w, h, line, i + r + 1, horizontal) - at(src, w, h, line, i - r, horizontal);
            }
        }
        return dst;
    }

    private static int at(int[] a, int w, int h, int line, int i, boolean horizontal) {
        if (horizontal) return i < 0 || i >= w ? 0 : a[line * w + i];
        return i < 0 || i >= h ? 0 : a[i * w + line];
    }

    /** Makes a window see-through so a shadow around its content shows over whatever is behind it. */
    public static boolean makeTranslucent(Window w) {
        try {
            w.setBackground(new Color(0, 0, 0, 0));
        } catch (RuntimeException e) {
            return false;
        }
        if (w.isOpaque()) return false;
        if (w instanceof RootPaneContainer rpc) {
            JRootPane root = rpc.getRootPane();
            root.setOpaque(false);
            if (rpc.getContentPane() instanceof JComponent c) c.setOpaque(false);
            if (rpc.getLayeredPane() != null) rpc.getLayeredPane().setOpaque(false);
        }
        return true;
    }

    /** A transparent frame around content that paints the content's shadow in its margin. */
    public static final class Frame extends JPanel {
        private final int arc;

        public Frame(Component content, int arc) {
            super(new BorderLayout());
            this.arc = arc;
            setOpaque(false);
            setBorder(new EmptyBorder(INSETS));
            add(content);
        }

        /** Only the content takes the mouse; clicks on the shadow fall through to what's beneath it. */
        @Override
        public boolean contains(int x, int y) {
            return x >= INSETS.left && y >= INSETS.top && x < getWidth() - INSETS.right && y < getHeight() - INSETS.bottom;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.SrcOver);
            Shadow.paint(g2, INSETS.left, INSETS.top, getWidth() - INSETS.left - INSETS.right,
                    getHeight() - INSETS.top - INSETS.bottom, arc);
            g2.dispose();
        }
    }

    /** Popup factory that wraps every popup in a {@link Frame}. Installed once at startup. */
    /**
     * Popup factory that draws a soft shadow under menus, dropdowns, tooltips and popovers.
     *
     * <p>The shadow is painted inside the app window, on its popup layer, the same way Swing shows lightweight
     * popups. Nothing depends on see-through OS windows, which some systems draw as a solid black or white
     * frame. A popup that doesn't fit inside the window (for example a long menu near the edge) gets an
     * ordinary window without a shadow.
     */
    public static final class Factory extends PopupFactory {
        public static void install() {
            if (!GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("restclient.noShadows")
                    && !(PopupFactory.getSharedInstance() instanceof Factory)) {
                PopupFactory.setSharedInstance(new Factory());
            }
        }

        @Override
        public Popup getPopup(Component owner, Component contents, int x, int y) {
            Window w = owner == null ? null : owner instanceof Window ow ? ow : SwingUtilities.getWindowAncestor(owner);
            if (w instanceof RootPaneContainer rpc && w.isShowing()) {
                int arc = contents instanceof JComponent jc && jc.getClientProperty("rc.shadowArc") instanceof Integer i ? i
                        : contents instanceof Ui.RoundedPanel ? 12 : 8;
                Frame frame = new Frame(contents, arc);
                java.awt.Dimension size = frame.getPreferredSize();
                javax.swing.JLayeredPane layers = rpc.getLayeredPane();
                java.awt.Point origin = layers.getLocationOnScreen();
                java.awt.Rectangle r = new java.awt.Rectangle(x - INSETS.left - origin.x, y - INSETS.top - origin.y,
                        size.width, size.height);
                // The content itself must fit; the shadow margin may be clipped at the window edge.
                java.awt.Rectangle content = new java.awt.Rectangle(x - origin.x, y - origin.y,
                        size.width - INSETS.left - INSETS.right, size.height - INSETS.top - INSETS.bottom);
                if (new java.awt.Rectangle(0, 0, layers.getWidth(), layers.getHeight()).contains(content)) {
                    return new LayerPopup(layers, frame, r);
                }
                frame.remove(contents);
            }
            return super.getPopup(owner, contents, x, y);
        }
    }

    /** A popup shown on the window's popup layer. */
    private static final class LayerPopup extends Popup {
        private final javax.swing.JLayeredPane layers;
        private final Frame frame;
        private final java.awt.Rectangle bounds;

        LayerPopup(javax.swing.JLayeredPane layers, Frame frame, java.awt.Rectangle bounds) {
            this.layers = layers;
            this.frame = frame;
            this.bounds = bounds;
        }

        @Override
        public void show() {
            layers.add(frame, javax.swing.JLayeredPane.POPUP_LAYER, 0);
            frame.setBounds(bounds);
            frame.validate();
            frame.repaint();
        }

        @Override
        public void hide() {
            java.awt.Container parent = frame.getParent();
            if (parent != null) {
                java.awt.Rectangle b = frame.getBounds();
                parent.remove(frame);
                parent.repaint(b.x, b.y, b.width, b.height);
            }
        }
    }
}
