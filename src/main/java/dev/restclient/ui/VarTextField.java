package dev.restclient.ui;

import dev.restclient.curl.CurlParser;
import dev.restclient.vars.VariableResolver;

import javax.swing.JTextField;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** A text field with variable chips, hover popovers, autocomplete, undo and optional cURL paste detection. */
public class VarTextField extends JTextField {
    private final VarSupport support;
    private Consumer<String> curlPasteHandler;

    public VarTextField(Supplier<VariableResolver> resolver, VarSupport.Actions actions, boolean mono) {
        if (mono) setFont(Theme.mono(13f));
        support = VarSupport.install(this, resolver, actions);
    }

    public VarSupport support() {
        return support;
    }

    public VarTextField placeholder(String text) {
        Ui.placeholder(this, text);
        return this;
    }

    public void setSuggestions(Function<String, List<String>> suggestions) {
        support.setPlainSuggestions(suggestions);
    }

    /** When set, pasting a curl command calls the handler instead of inserting the text. */
    public void setCurlPasteHandler(Consumer<String> handler) {
        this.curlPasteHandler = handler;
    }

    @Override
    public void paste() {
        if (curlPasteHandler != null && isEditable()) {
            try {
                Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                if (data instanceof String s && CurlParser.looksLikeCurl(s)) {
                    curlPasteHandler.accept(s);
                    return;
                }
            } catch (Exception ignored) {
                // fall back to normal paste
            }
        }
        super.paste();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        return null; // the hover popover replaces tooltips
    }

    public void refreshVariables() {
        support.refresh();
    }
}
