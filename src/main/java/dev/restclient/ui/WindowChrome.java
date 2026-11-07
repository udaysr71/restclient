package dev.restclient.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowStateListener;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.Locale;

/**
 * Themed window title bars. Windows draws its own title bar in the system's colours, which leaves a white strip
 * over a dark app (and there is no pure-Java way to ask for a dark one), so on Windows the app draws its own:
 * app icon, the menu bar, the title and the caption buttons, with drag-to-move, double-click to maximise and
 * edge resizing. macOS keeps its native title bar (which follows the system appearance) and so does Linux, where
 * the window manager's decorations are expected. Override with {@code -Drestclient.titlebar=custom|native}.
 */
public final class WindowChrome {
    private static final int RESIZE = 5;

    private WindowChrome() {
    }

    public static boolean enabled() {
        String p = System.getProperty("restclient.titlebar", "").toLowerCase(Locale.ROOT);
        if (p.equals("custom")) return true;
        if (p.equals("native")) return false;
        return Platform.isWindows();
    }

    /** Installs a themed title bar on a frame; the menu bar moves into it. Call before the frame is shown. */
    public static void install(JFrame f, JMenuBar menu) {
        macAppearance(f.getRootPane());
        if (!enabled() || f.isDisplayable()) {
            if (menu != null) f.setJMenuBar(menu);
            return;
        }
        f.setUndecorated(true);
        TitleBar bar = new TitleBar(f, menu, true);
        f.getRootPane().setJMenuBar(bar);
        decorate(f, f.getRootPane());
        f.addWindowStateListener(e -> bar.stateChanged());
    }

    /** Installs a themed title bar with just a close button on a dialog. Call before the dialog is shown. */
    public static void install(JDialog d) {
        install(d, true);
    }

    /** As {@link #install(JDialog)}; pass {@code showTitle=false} when the content already has a header. */
    public static void install(JDialog d, boolean showTitle) {
        macAppearance(d.getRootPane());
        if (!enabled() || d.isDisplayable()) return;
        d.setUndecorated(true);
        TitleBar bar = new TitleBar(d, null, false);
        bar.showTitle = showTitle;
        bar.refresh();
        d.getRootPane().setJMenuBar(bar);
        decorate(d, d.getRootPane());
    }

    /** On macOS, makes the native title bar follow the app's light/dark setting rather than the system's. */
    private static void macAppearance(JRootPane root) {
        if (!Platform.isMac()) return;
        Runnable sync = () -> root.putClientProperty("apple.awt.windowAppearance",
                Theme.dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        sync.run();
        Theme.onChange(sync);
    }

    private static void decorate(Window w, JRootPane root) {
        root.setBorder(new OutlineBorder(w));
        ResizeHandles handles = new ResizeHandles(w);
        root.setGlassPane(handles);
        handles.setVisible(true);
    }

    static boolean maximized(Window w) {
        return w instanceof Frame f && (f.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    /** A hairline around the window so it doesn't melt into what's behind it; none when maximised. */
    private static final class OutlineBorder implements Border {
        private final Window w;

        OutlineBorder(Window w) {
            this.w = w;
        }

        @Override
        public void paintBorder(java.awt.Component c, Graphics g, int x, int y, int width, int height) {
            if (maximized(w)) return;
            g.setColor(w.isActive() ? Theme.mix(Theme.separator(), Theme.dark() ? Color.WHITE : Color.BLACK, 0.15f) : Theme.separator());
            g.drawRect(x, y, width - 1, height - 1);
        }

        @Override
        public Insets getBorderInsets(java.awt.Component c) {
            return maximized(w) ? new Insets(0, 0, 0, 0) : new Insets(1, 1, 1, 1);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    /** The title bar. Extends JMenuBar so JRootPane lays it out across the top, above the content. */
    private static final class TitleBar extends JMenuBar {
        private final Window w;
        private final JLabel title = new JLabel("", SwingConstants.CENTER);
        private final CaptionButton maximize;
        private final JMenuBar menu;
        private JPanel left;
        private JPanel right;
        boolean showTitle = true;
        /** Main windows match their toolbar; dialogs match their content, so the bar reads as part of it. */
        private final boolean frameStyle;
        private Point dragFrom;
        private Point dragWindowFrom;

        TitleBar(Window w, JMenuBar menu, boolean frame) {
            this.w = w;
            this.menu = menu;
            this.frameStyle = frame;
            setLayout(new BorderLayout());
            JPanel left = new JPanel();
            left.setOpaque(false);
            left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
            left.add(Box.createHorizontalStrut(10));
            if (frame) {
                JLabel icon = new JLabel(new ImageIcon(Theme.appIcon(16)));
                left.add(icon);
                left.add(Box.createHorizontalStrut(6));
            }
            if (menu != null) {
                menu.setOpaque(false);
                menu.setBorder(BorderFactory.createEmptyBorder());
                left.add(menu);
            }
            JPanel right = new JPanel();
            right.setOpaque(false);
            right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
            if (frame) {
                right.add(new CaptionButton(CaptionButton.MIN, () -> ((Frame) w).setExtendedState(Frame.ICONIFIED)));
                maximize = new CaptionButton(CaptionButton.MAX, this::toggleMaximize);
                right.add(maximize);
            } else {
                maximize = null;
            }
            right.add(new CaptionButton(CaptionButton.CLOSE,
                    () -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING))));
            // Laid out by hand so the title is centred on the whole window, as Windows and macOS do, and only
            // slides aside when the menus would overlap it.
            setLayout(null);
            this.left = left;
            this.right = right;
            add(left);
            add(title);
            add(right);

            MouseAdapter drag = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    dragFrom = e.getLocationOnScreen();
                    dragWindowFrom = w.getLocation();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragFrom == null) return;
                    Point now = e.getLocationOnScreen();
                    if (maximized(w)) {
                        // Restore under the pointer, keeping the grab point at the same relative x.
                        Frame f = (Frame) w;
                        double rel = (double) (dragFrom.x - w.getX()) / Math.max(1, w.getWidth());
                        f.setExtendedState(Frame.NORMAL);
                        int nx = now.x - (int) (rel * w.getWidth());
                        w.setLocation(nx, now.y - e.getY());
                        dragFrom = now;
                        dragWindowFrom = w.getLocation();
                        return;
                    }
                    w.setLocation(dragWindowFrom.x + now.x - dragFrom.x, dragWindowFrom.y + now.y - dragFrom.y);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragFrom = null;
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (frame && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) toggleMaximize();
                }
            };
            for (JComponent c : new JComponent[]{this, title, left, right}) {
                c.addMouseListener(drag);
                c.addMouseMotionListener(drag);
            }
            w.addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent e) {
                    refresh();
                }

                @Override
                public void windowDeactivated(WindowEvent e) {
                    refresh();
                }

                @Override
                public void windowOpened(WindowEvent e) {
                    refresh();
                }
            });
            w.addPropertyChangeListener("title", e -> refresh());
            refresh();
        }

        // The menus live in a nested menu bar; expose them so code (and F10) that asks this bar finds them.
        @Override
        public int getMenuCount() {
            return menu == null ? 0 : menu.getMenuCount();
        }

        @Override
        public javax.swing.JMenu getMenu(int index) {
            return menu == null ? null : menu.getMenu(index);
        }

        private void refresh() {
            title.setText(!showTitle ? "" : w instanceof Frame f ? f.getTitle() : w instanceof java.awt.Dialog d ? d.getTitle() : "");
            title.setForeground(w.isActive() ? Theme.secondary() : Theme.disabledText());
            repaint();
        }

        void stateChanged() {
            if (maximize != null) maximize.setRestore(maximized(w));
            JRootPane root = SwingUtilities.getRootPane(this);
            if (root != null) {
                root.revalidate();
                root.repaint();
            }
        }

        private void toggleMaximize() {
            Frame f = (Frame) w;
            if (maximized(w)) {
                f.setExtendedState(Frame.NORMAL);
            } else {
                // An undecorated window would cover the taskbar when maximised; keep it to the work area.
                GraphicsConfiguration gc = w.getGraphicsConfiguration();
                Rectangle s = gc.getBounds();
                Insets in = w.getToolkit().getScreenInsets(gc);
                f.setMaximizedBounds(new Rectangle(s.x + in.left, s.y + in.top,
                        s.width - in.left - in.right, s.height - in.top - in.bottom));
                f.setExtendedState(Frame.MAXIMIZED_BOTH);
            }
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBorder(BorderFactory.createEmptyBorder());
            if (title != null) refresh();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            Dimension lp = left.getPreferredSize();
            Dimension rp = right.getPreferredSize();
            left.setBounds(0, 0, Math.min(lp.width, w), h);
            right.setBounds(w - rp.width, 0, rp.width, h);
            int tw = title.getPreferredSize().width;
            int min = left.getWidth() + 12;
            int max = right.getX() - 12;
            int x = (w - tw) / 2;
            if (x < min) x = min;
            if (x + tw > max) tw = Math.max(0, max - x);
            title.setBounds(x, 0, tw, h);
        }

        @Override
        public Dimension getPreferredSize() {
            int h = Theme.scaled(menu == null ? 32 : 34);
            if (left != null) h = Math.max(h, Math.max(left.getPreferredSize().height, right.getPreferredSize().height));
            int w = left == null ? 0 : left.getPreferredSize().width + right.getPreferredSize().width + title.getPreferredSize().width;
            return new Dimension(w, h);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(frameStyle ? Theme.toolbarBg() : Theme.panelBg());
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    /** Minimise, maximise/restore and close, drawn as Windows 11 draws them. */
    private static final class CaptionButton extends JButton {
        static final int MIN = 0;
        static final int MAX = 1;
        static final int CLOSE = 2;
        private final int kind;
        private boolean restore;

        CaptionButton(int kind, Runnable action) {
            this.kind = kind;
            setFocusable(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setToolTipText(kind == MIN ? "Minimize" : kind == MAX ? "Maximize" : "Close");
            addActionListener(e -> action.run());
        }

        void setRestore(boolean r) {
            restore = r;
            setToolTipText(kind == MAX ? (r ? "Restore Down" : "Maximize") : getToolTipText());
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(Theme.scaled(46), Theme.scaled(34));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(getPreferredSize().width, Integer.MAX_VALUE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            boolean hover = getModel().isRollover();
            boolean pressed = getModel().isArmed() && getModel().isPressed();
            Color fg = Theme.text();
            if (kind == CLOSE && (hover || pressed)) {
                g2.setColor(pressed ? new Color(0x94, 0x1E, 0x14) : new Color(0xC4, 0x2B, 0x1C));
                g2.fillRect(0, 0, getWidth(), getHeight());
                fg = Color.WHITE;
            } else if (hover || pressed) {
                g2.setColor(pressed ? Theme.pressedSubtle() : Theme.hoverSubtle());
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            if (!SwingUtilities.getWindowAncestor(this).isActive() && !hover) fg = Theme.secondary();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(fg);
            g2.setStroke(new BasicStroke(1f));
            float s = Theme.scaled(10);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float x = Math.round(cx - s / 2) + 0.5f;
            float y = Math.round(cy - s / 2) + 0.5f;
            switch (kind) {
                case MIN -> g2.draw(new Line2D.Float(x, Math.round(cy) + 0.5f, x + s, Math.round(cy) + 0.5f));
                case MAX -> {
                    if (restore) {
                        float d = 2;
                        g2.draw(new Rectangle2D.Float(x, y + d, s - d, s - d));
                        g2.draw(new Line2D.Float(x + d, y, x + s, y));
                        g2.draw(new Line2D.Float(x + s, y, x + s, y + s - d));
                    } else {
                        g2.draw(new Rectangle2D.Float(x, y, s, s));
                    }
                }
                default -> {
                    g2.draw(new Line2D.Float(x, y, x + s, y + s));
                    g2.draw(new Line2D.Float(x + s, y, x, y + s));
                }
            }
            g2.dispose();
        }
    }

    /**
     * Invisible strips along the window edges that take the mouse for resizing. As the glass pane it sits on top,
     * but {@link #contains} only claims the edge band, so every other click goes through to the content.
     */
    private static final class ResizeHandles extends JComponent {
        private final Window w;
        private int edge;
        private Point from;
        private Rectangle start;

        ResizeHandles(Window w) {
            this.w = w;
            setOpaque(false);
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(cursorFor(edgeAt(e.getX(), e.getY()))));
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    edge = edgeAt(e.getX(), e.getY());
                    from = e.getLocationOnScreen();
                    start = w.getBounds();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (from == null || edge == 0) return;
                    Point p = e.getLocationOnScreen();
                    int dx = p.x - from.x;
                    int dy = p.y - from.y;
                    Rectangle r = new Rectangle(start);
                    Dimension min = w.getMinimumSize();
                    if ((edge & 1) != 0) { // west
                        int nw = Math.max(min.width, start.width - dx);
                        r.x = start.x + start.width - nw;
                        r.width = nw;
                    }
                    if ((edge & 2) != 0) r.width = Math.max(min.width, start.width + dx); // east
                    if ((edge & 4) != 0) { // north
                        int nh = Math.max(min.height, start.height - dy);
                        r.y = start.y + start.height - nh;
                        r.height = nh;
                    }
                    if ((edge & 8) != 0) r.height = Math.max(min.height, start.height + dy); // south
                    w.setBounds(r);
                    w.validate();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    from = null;
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private boolean resizable() {
            return !maximized(w) && (w instanceof Frame f ? f.isResizable() : !(w instanceof java.awt.Dialog d) || d.isResizable());
        }

        private int edgeAt(int x, int y) {
            if (!resizable()) return 0;
            int band = RESIZE;
            int corner = band * 3;
            int e = 0;
            if (x < band || (x < corner && (y < band || y >= getHeight() - band))) e |= 1;
            if (x >= getWidth() - band || (x >= getWidth() - corner && (y < band || y >= getHeight() - band))) e |= 2;
            if (y < band || (y < corner && (x < band || x >= getWidth() - band))) e |= 4;
            if (y >= getHeight() - band || (y >= getHeight() - corner && (x < band || x >= getWidth() - band))) e |= 8;
            return e;
        }

        private static int cursorFor(int e) {
            return switch (e) {
                case 1 -> Cursor.W_RESIZE_CURSOR;
                case 2 -> Cursor.E_RESIZE_CURSOR;
                case 4 -> Cursor.N_RESIZE_CURSOR;
                case 8 -> Cursor.S_RESIZE_CURSOR;
                case 5 -> Cursor.NW_RESIZE_CURSOR;
                case 6 -> Cursor.NE_RESIZE_CURSOR;
                case 9 -> Cursor.SW_RESIZE_CURSOR;
                case 10 -> Cursor.SE_RESIZE_CURSOR;
                default -> Cursor.DEFAULT_CURSOR;
            };
        }

        @Override
        public boolean contains(int x, int y) {
            return edgeAt(x, y) != 0;
        }
    }
}
