package dev.restclient.ui.laf;

import dev.restclient.ui.Icons;
import dev.restclient.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.SwingConstants;
import javax.swing.UIDefaults;
import javax.swing.border.Border;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.IconUIResource;
import javax.swing.plaf.InsetsUIResource;
import javax.swing.plaf.basic.BasicLookAndFeel;
import java.awt.Color;

/**
 * The app's own flat look and feel, built on Swing's Basic look and feel. Light and dark palettes come
 * from {@link Theme}. The visual design is inspired by FlatLaf (Apache-2.0) and macOS; no FlatLaf code is used.
 */
public class RcLookAndFeel extends BasicLookAndFeel {
    @Override
    public void initialize() {
        super.initialize();
        FocusVisible.install();
    }

    @Override
    public String getName() {
        return "REST Client";
    }

    @Override
    public String getID() {
        return "RestClient";
    }

    @Override
    public String getDescription() {
        return "Flat look and feel for REST Client";
    }

    @Override
    public boolean isNativeLookAndFeel() {
        return false;
    }

    @Override
    public boolean isSupportedLookAndFeel() {
        return true;
    }

    @Override
    protected void initClassDefaults(UIDefaults t) {
        super.initClassDefaults(t);
        String p = "dev.restclient.ui.laf.";
        t.putDefaults(new Object[]{
                "ButtonUI", p + "RcButtonUI",
                "ToggleButtonUI", p + "RcButtonUI",
                "TextFieldUI", p + "RcTextFieldUI",
                "PasswordFieldUI", p + "RcPasswordFieldUI",
                "FormattedTextFieldUI", p + "RcFormattedTextFieldUI",
                "ComboBoxUI", p + "RcComboBoxUI",
                "ScrollBarUI", p + "RcScrollBarUI",
                "ProgressBarUI", p + "RcProgressBarUI",
                "SpinnerUI", p + "RcSpinnerUI",
                "SplitPaneUI", p + "RcSplitPaneUI",
                "TreeUI", p + "RcTreeUI",
                "CheckBoxUI", p + "RcCheckBoxUI",
                "RadioButtonUI", p + "RcRadioButtonUI",
                "LabelUI", p + "RcLabelUI",
                "TableHeaderUI", p + "RcTableHeaderUI",
                "MenuItemUI", p + "RcMenuItemUI",
                "CheckBoxMenuItemUI", p + "RcCheckBoxMenuItemUI",
                "RadioButtonMenuItemUI", p + "RcRadioButtonMenuItemUI",
                "MenuUI", p + "RcMenuUI",
                "PopupMenuUI", p + "RcPopupMenuUI",
                "PopupMenuSeparatorUI", p + "RcPopupMenuSeparatorUI",
        });
    }

    @Override
    protected void initSystemColorDefaults(UIDefaults t) {
        super.initSystemColorDefaults(t);
        Color bg = Theme.panelBg();
        Color fg = Theme.text();
        t.putDefaults(new Object[]{
                "control", c(bg), "controlText", c(fg), "text", c(Theme.fieldBg()), "textText", c(fg),
                "window", c(bg), "windowText", c(fg), "menu", c(Theme.menuBg()), "menuText", c(fg),
                "textHighlight", c(Theme.textSelection()), "textHighlightText", c(fg),
                "controlShadow", c(Theme.separator()), "controlDkShadow", c(Theme.fieldBorder()),
                "controlHighlight", c(Theme.separator()), "controlLtHighlight", c(bg),
                "info", c(Theme.tooltipBg()), "infoText", c(fg),
        });
    }

    @Override
    protected void initComponentDefaults(UIDefaults t) {
        super.initComponentDefaults(t);
        Color bg = Theme.panelBg();
        Color fg = Theme.text();
        Color secondary = Theme.secondary();
        Color disabled = Theme.disabledText();
        Color field = Theme.fieldBg();
        Color sel = Theme.textSelection();
        Color line = Theme.separator();
        Color menuBg = Theme.menuBg();
        Color accent = Theme.accent();
        FontUIResource font = new FontUIResource(Theme.uiFont());
        Border empty = new BorderUIResource(BorderFactory.createEmptyBorder());
        // Basic sizes menus from their border, not their margin, so the padding lives here.
        Border menuPad = new BorderUIResource(BorderFactory.createEmptyBorder(4, 9, 4, 9));
        Border itemPad = new BorderUIResource(BorderFactory.createEmptyBorder(5, 10, 5, 14));
        Border tableCell = new BorderUIResource(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        Border listCell = new BorderUIResource(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        Border textPad = new BorderUIResource(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        Border lineBorder = new BorderUIResource(BorderFactory.createLineBorder(line));
        Border popup = new BorderUIResource(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.fieldBorder()), BorderFactory.createEmptyBorder(4, 0, 4, 0)));

        String[] fontKeys = {"Button", "ToggleButton", "RadioButton", "CheckBox", "ComboBox", "Label", "List",
                "MenuBar", "MenuItem", "RadioButtonMenuItem", "CheckBoxMenuItem", "Menu", "PopupMenu", "OptionPane",
                "Panel", "ProgressBar", "ScrollPane", "Viewport", "TabbedPane", "Table", "TableHeader", "TextField",
                "FormattedTextField", "Spinner", "PasswordField", "TextArea", "TextPane", "EditorPane", "TitledBorder",
                "ToolBar", "ToolTip", "Tree"};
        for (String k : fontKeys) t.put(k + ".font", font);
        t.put("defaultFont", font);
        t.put("MenuItem.acceleratorFont", font);
        t.put("Menu.acceleratorFont", font);
        t.put("CheckBoxMenuItem.acceleratorFont", font);
        t.put("RadioButtonMenuItem.acceleratorFont", font);

        // Keyboard bindings for text components (Basic doesn't provide them).
        // Basic leaves arrow keys out of the combo box map; without these a closed or open combo ignores Up/Down.
        t.put("ComboBox.ancestorInputMap", new javax.swing.UIDefaults.LazyInputMap(new Object[]{
                "ESCAPE", "hidePopup", "PAGE_UP", "pageUpPassThrough", "PAGE_DOWN", "pageDownPassThrough",
                "HOME", "homePassThrough", "END", "endPassThrough", "DOWN", "selectNext", "KP_DOWN", "selectNext",
                "alt DOWN", "togglePopup", "alt KP_DOWN", "togglePopup", "alt UP", "togglePopup", "alt KP_UP", "togglePopup",
                "SPACE", "spacePopup", "ENTER", "enterPressed", "UP", "selectPrevious", "KP_UP", "selectPrevious"}));
        t.put("TextField.focusInputMap", TextKeymaps.field());
        t.put("FormattedTextField.focusInputMap", TextKeymaps.field());
        t.put("PasswordField.focusInputMap", TextKeymaps.password());
        t.put("TextArea.focusInputMap", TextKeymaps.area());
        t.put("TextPane.focusInputMap", TextKeymaps.area());
        t.put("EditorPane.focusInputMap", TextKeymaps.area());

        // Text antialiasing as the OS does it (ClearType on Windows, grayscale elsewhere).
        Object hints = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        Object aa = hints instanceof java.util.Map<?, ?> m ? m.get(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING) : null;
        Object contrast = hints instanceof java.util.Map<?, ?> m ? m.get(java.awt.RenderingHints.KEY_TEXT_LCD_CONTRAST) : null;
        if (aa == null || aa == java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
                || aa == java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT) {
            aa = java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
        }
        t.put(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, aa);
        t.put(java.awt.RenderingHints.KEY_TEXT_LCD_CONTRAST, contrast != null ? contrast : 140);

        t.putDefaults(new Object[]{
                // panels and labels
                "Panel.background", c(bg), "Panel.foreground", c(fg),
                "Label.foreground", c(fg), "Label.disabledForeground", c(disabled), "Label.background", c(bg),
                "Viewport.background", c(bg), "Viewport.foreground", c(fg),
                "ScrollPane.background", c(bg), "ScrollPane.border", lineBorder,
                "OptionPane.background", c(bg), "OptionPane.messageForeground", c(fg),
                "OptionPane.border", new BorderUIResource(BorderFactory.createEmptyBorder(18, 20, 14, 20)),
                "OptionPane.buttonAreaBorder", new BorderUIResource(BorderFactory.createEmptyBorder(14, 0, 0, 0)),
                "OptionPane.buttonOrientation", SwingConstants.RIGHT,
                "OptionPane.sameSizeButtons", Boolean.TRUE,
                "OptionPane.errorIcon", icon("error", 32), "OptionPane.warningIcon", icon("warning", 32),
                "OptionPane.questionIcon", icon("info", 32), "OptionPane.informationIcon", icon("info", 32),
                "RootPane.background", c(bg),
                "ToolBar.background", c(bg), "ToolBar.border", empty,
                "TitledBorder.titleColor", c(secondary), "TitledBorder.border", lineBorder,
                "Separator.foreground", c(line), "Separator.background", c(bg),
                "PopupMenuSeparator.foreground", c(line), "PopupMenuSeparator.background", c(menuBg),

                // buttons
                "Button.background", c(Theme.buttonBg()), "Button.foreground", c(fg),
                "Button.disabledText", c(disabled), "Button.select", c(Theme.buttonPressed()),
                "Button.focus", c(Theme.focusRing()), "Button.margin", new InsetsUIResource(5, 14, 5, 14),
                "Button.textShiftOffset", 0, "Button.defaultButtonFollowsFocus", Boolean.FALSE,
                "ToggleButton.background", c(Theme.buttonBg()), "ToggleButton.foreground", c(fg),
                "ToggleButton.disabledText", c(disabled), "ToggleButton.select", c(Theme.selectionBg()),
                "ToggleButton.margin", new InsetsUIResource(4, 10, 4, 10), "ToggleButton.textShiftOffset", 0,
                "CheckBox.background", c(bg), "CheckBox.foreground", c(fg), "CheckBox.disabledText", c(disabled),
                "CheckBox.icon", new LafIcons.CheckBox(), "CheckBox.border", new BorderUIResource(BorderFactory.createEmptyBorder(3, 2, 3, 2)),
                "CheckBox.textIconGap", 8, "CheckBox.focus", c(Theme.focusRing()),
                "RadioButton.background", c(bg), "RadioButton.foreground", c(fg), "RadioButton.disabledText", c(disabled),
                "RadioButton.icon", new LafIcons.Radio(), "RadioButton.border", new BorderUIResource(BorderFactory.createEmptyBorder(3, 2, 3, 2)),
                "RadioButton.textIconGap", 8, "RadioButton.focus", c(Theme.focusRing()),

                // text
                "TextField.background", c(field), "TextField.foreground", c(fg), "TextField.caretForeground", c(fg),
                "TextField.selectionBackground", c(sel), "TextField.selectionForeground", c(fg),
                "TextField.inactiveForeground", c(disabled), "TextField.inactiveBackground", c(Theme.subtleBg()),
                "TextField.disabledBackground", c(Theme.subtleBg()), "TextField.border", new RoundBorder(),
                "TextField.margin", new InsetsUIResource(0, 0, 0, 0),
                "PasswordField.background", c(field), "PasswordField.foreground", c(fg),
                "PasswordField.caretForeground", c(fg), "PasswordField.selectionBackground", c(sel),
                "PasswordField.selectionForeground", c(fg), "PasswordField.inactiveForeground", c(disabled),
                "PasswordField.border", new RoundBorder(),
                "PasswordField.echoChar", '\u2022',
                "FormattedTextField.background", c(field), "FormattedTextField.foreground", c(fg),
                "FormattedTextField.caretForeground", c(fg), "FormattedTextField.selectionBackground", c(sel),
                "FormattedTextField.selectionForeground", c(fg), "FormattedTextField.inactiveForeground", c(disabled),
                "FormattedTextField.border", new RoundBorder(),
                "TextArea.background", c(field), "TextArea.foreground", c(fg), "TextArea.caretForeground", c(fg),
                "TextArea.selectionBackground", c(sel), "TextArea.selectionForeground", c(fg),
                "TextArea.inactiveForeground", c(disabled), "TextArea.border", textPad,
                "TextPane.background", c(field), "TextPane.foreground", c(fg), "TextPane.caretForeground", c(fg),
                "TextPane.selectionBackground", c(sel), "TextPane.selectionForeground", c(fg),
                "EditorPane.background", c(field), "EditorPane.foreground", c(fg), "EditorPane.caretForeground", c(fg),
                "EditorPane.selectionBackground", c(sel), "EditorPane.selectionForeground", c(fg),

                // combo, spinner
                "ComboBox.background", c(Theme.buttonBg()), "ComboBox.foreground", c(fg),
                "ComboBox.selectionBackground", c(accent), "ComboBox.selectionForeground", c(Color.WHITE),
                "ComboBox.disabledForeground", c(disabled), "ComboBox.disabledBackground", c(Theme.subtleBg()),
                "ComboBox.buttonBackground", c(Theme.buttonBg()), "ComboBox.border", new RoundBorder(),
                "ComboBox.padding", new InsetsUIResource(3, 4, 3, 2),
                "Spinner.background", c(field), "Spinner.foreground", c(fg), "Spinner.border", new RoundBorder(),
                "Spinner.arrowButtonBorder", empty, "Spinner.editorBorderPainted", Boolean.FALSE,

                // lists, tables, trees
                "List.background", c(field), "List.foreground", c(fg),
                "List.selectionBackground", c(Theme.selectionBg()), "List.selectionForeground", c(fg),
                "List.focusCellHighlightBorder", listCell, "List.cellNoFocusBorder", listCell,
                "List.focusSelectedCellHighlightBorder", listCell,
                "Table.background", c(field), "Table.foreground", c(fg),
                "Table.selectionBackground", c(Theme.selectionBg()), "Table.selectionForeground", c(fg),
                "Table.gridColor", c(line), "Table.focusCellHighlightBorder", tableCell, "Table.cellNoFocusBorder", tableCell,
                "Table.focusSelectedCellHighlightBorder", tableCell,
                "Table.scrollPaneBorder", lineBorder, "Table.rowHeight", Theme.scaled(30),
                "Table.dropLineColor", c(accent), "Table.dropLineShortColor", c(accent),
                "TableHeader.background", c(field), "TableHeader.foreground", c(secondary),
                "TableHeader.cellBorder", new BorderUIResource(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, line), BorderFactory.createEmptyBorder(5, 8, 5, 8))),
                "Tree.background", c(bg), "Tree.foreground", c(fg), "Tree.textBackground", c(bg), "Tree.textForeground", c(fg),
                "Tree.selectionBackground", c(Theme.selectionBg()), "Tree.selectionForeground", c(fg),
                "Tree.selectionBorderColor", null, "Tree.rendererFillBackground", Boolean.FALSE,
                "Tree.paintLines", Boolean.FALSE, "Tree.rowHeight", Theme.scaled(26), "Tree.leftChildIndent", 8, "Tree.rightChildIndent", 12,
                "Tree.expandedIcon", new LafIcons.Chevron(LafIcons.Direction.DOWN, 14, Theme::secondary),
                "Tree.collapsedIcon", new LafIcons.Chevron(LafIcons.Direction.RIGHT, 14, Theme::secondary),
                "Tree.leafIcon", null, "Tree.openIcon", null, "Tree.closedIcon", null,
                "Tree.dropLineColor", c(accent),

                // menus
                "MenuBar.background", c(Theme.toolbarBg()), "MenuBar.foreground", c(fg), "MenuBar.border", new BorderUIResource(BorderFactory.createEmptyBorder(2, 4, 2, 4)),
                "PopupMenu.background", c(menuBg), "PopupMenu.foreground", c(fg), "PopupMenu.border", new BorderUIResource(BorderFactory.createEmptyBorder(5, 0, 5, 0)),
                "PopupMenu.consumeEventOnClose", Boolean.TRUE,
                "Menu.background", c(Theme.toolbarBg()), "Menu.foreground", c(fg),
                "Menu.selectionBackground", c(Theme.selectionBg()), "Menu.selectionForeground", c(fg),
                "Menu.disabledForeground", c(disabled), "Menu.margin", new InsetsUIResource(4, 8, 4, 8),
                "Menu.borderPainted", Boolean.FALSE, "Menu.border", menuPad,
                "Menu.arrowIcon", new LafIcons.Chevron(LafIcons.Direction.RIGHT, 12, Theme::secondary),
                "Menu.acceleratorForeground", c(secondary), "Menu.acceleratorSelectionForeground", c(Color.WHITE),
                "MenuItem.background", c(menuBg), "MenuItem.foreground", c(fg),
                "MenuItem.selectionBackground", c(accent), "MenuItem.selectionForeground", c(Color.WHITE),
                "MenuItem.disabledForeground", c(disabled), "MenuItem.acceleratorForeground", c(secondary),
                "MenuItem.acceleratorSelectionForeground", c(Color.WHITE), "MenuItem.margin", new InsetsUIResource(4, 10, 4, 14),
                "MenuItem.border", itemPad, "MenuItem.borderPainted", Boolean.FALSE, "MenuItem.checkIcon", null,
                "CheckBoxMenuItem.background", c(menuBg), "CheckBoxMenuItem.foreground", c(fg),
                "CheckBoxMenuItem.selectionBackground", c(accent), "CheckBoxMenuItem.selectionForeground", c(Color.WHITE),
                "CheckBoxMenuItem.acceleratorForeground", c(secondary), "CheckBoxMenuItem.acceleratorSelectionForeground", c(Color.WHITE),
                "CheckBoxMenuItem.checkIcon", new LafIcons.MenuCheck(), "CheckBoxMenuItem.margin", new InsetsUIResource(4, 10, 4, 14),
                "CheckBoxMenuItem.border", itemPad, "CheckBoxMenuItem.borderPainted", Boolean.FALSE,
                "RadioButtonMenuItem.background", c(menuBg), "RadioButtonMenuItem.foreground", c(fg),
                "RadioButtonMenuItem.selectionBackground", c(accent), "RadioButtonMenuItem.selectionForeground", c(Color.WHITE),
                "RadioButtonMenuItem.acceleratorForeground", c(secondary), "RadioButtonMenuItem.acceleratorSelectionForeground", c(Color.WHITE),
                "RadioButtonMenuItem.checkIcon", new LafIcons.MenuCheck(), "RadioButtonMenuItem.margin", new InsetsUIResource(4, 10, 4, 14),
                "RadioButtonMenuItem.border", itemPad, "RadioButtonMenuItem.borderPainted", Boolean.FALSE,

                // misc
                "ToolTip.background", c(Theme.tooltipBg()), "ToolTip.foreground", c(fg),
                "ToolTip.border", new BorderUIResource(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Theme.fieldBorder()), BorderFactory.createEmptyBorder(5, 8, 5, 8))),
                "ProgressBar.foreground", c(accent), "ProgressBar.background", c(Theme.subtleBg()),
                "ProgressBar.border", empty, "ProgressBar.cycleTime", 1400, "ProgressBar.repaintInterval", 20,
                "SplitPane.background", c(bg), "SplitPane.border", empty, "SplitPane.dividerSize", 5,
                "SplitPaneDivider.border", empty, "SplitPaneDivider.draggingColor", c(line),
                "ScrollBar.width", 11, "ScrollBar.background", c(bg), "ScrollBar.track", c(bg),
                "ScrollBar.thumb", c(Theme.thumb()), "ScrollBar.thumbDarkShadow", c(bg),
                "ScrollBar.thumbHighlight", c(bg), "ScrollBar.thumbShadow", c(bg),
                "ScrollBar.minimumThumbSize", new java.awt.Dimension(24, 24),
                "TabbedPane.background", c(bg), "TabbedPane.foreground", c(fg),
                "Caret.width", 1,
        });
    }

    private static ColorUIResource c(Color col) {
        return new ColorUIResource(col);
    }

    private static Icon icon(String name, int size) {
        return new IconUIResource(Icons.get(name, size));
    }
}
