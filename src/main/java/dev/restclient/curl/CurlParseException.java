package dev.restclient.curl;

/** A cURL command that can't be parsed, with the position of the problem. */
public final class CurlParseException extends Exception {
    private final int offset;
    private final int line;
    private final int column;

    public CurlParseException(String message, String source, int offset) {
        super(message);
        this.offset = Math.max(0, Math.min(offset, source == null ? 0 : source.length()));
        int l = 1;
        int c = 1;
        if (source != null) {
            for (int i = 0; i < this.offset; i++) {
                if (source.charAt(i) == '\n') {
                    l++;
                    c = 1;
                } else {
                    c++;
                }
            }
        }
        this.line = l;
        this.column = c;
    }

    public int offset() { return offset; }
    public int line() { return line; }
    public int column() { return column; }

    /** "Unclosed single quote at line 5, column 14." */
    public String describe() {
        return getMessage() + " at line " + line + ", column " + column + ".";
    }
}
