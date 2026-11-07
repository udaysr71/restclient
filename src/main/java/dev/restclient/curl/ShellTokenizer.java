package dev.restclient.curl;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a POSIX-shell-style command line into words, the way bash would for a pasted curl command:
 * single quotes (literal), double quotes (with \" \\ \$ \` escapes), {@code $'…'} ANSI-C quotes,
 * backslash escapes and backslash-newline continuations. Variables and globs are not expanded.
 */
final class ShellTokenizer {
    /** A word and where it started in the source. */
    record Word(String text, int offset) {
    }

    private ShellTokenizer() {
    }

    static List<Word> tokenize(String src) throws CurlParseException {
        List<Word> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        int wordStart = 0;
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < n && (src.charAt(i + 1) == '\n' || src.charAt(i + 1) == '\r')) {
                // line continuation
                i += 2;
                if (i < n && src.charAt(i - 1) == '\r' && src.charAt(i) == '\n') i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(new Word(cur.toString(), wordStart));
                    cur.setLength(0);
                    inWord = false;
                }
                i++;
                continue;
            }
            if (!inWord) {
                inWord = true;
                wordStart = i;
            }
            if (c == '\'') {
                int end = src.indexOf('\'', i + 1);
                if (end < 0) throw new CurlParseException("Unclosed single quote", src, i);
                cur.append(src, i + 1, end);
                i = end + 1;
            } else if (c == '$' && i + 1 < n && src.charAt(i + 1) == '\'') {
                i = ansiC(src, i, cur);
            } else if (c == '"') {
                int start = i;
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '"') {
                        closed = true;
                        i++;
                        break;
                    }
                    if (d == '\\' && i + 1 < n) {
                        char e = src.charAt(i + 1);
                        if (e == '"' || e == '\\' || e == '$' || e == '`') {
                            cur.append(e);
                            i += 2;
                            continue;
                        }
                        if (e == '\n') {
                            i += 2;
                            continue;
                        }
                    }
                    cur.append(d);
                    i++;
                }
                if (!closed) throw new CurlParseException("Unclosed double quote", src, start);
            } else if (c == '\\') {
                if (i + 1 < n) {
                    cur.append(src.charAt(i + 1));
                    i += 2;
                } else {
                    i++;
                }
            } else {
                cur.append(c);
                i++;
            }
        }
        if (inWord) out.add(new Word(cur.toString(), wordStart));
        return out;
    }

    /** {@code $'...'} quoting: backslash escapes such as n, t, quote, backslash, xHH and uHHHH. Returns the new index. */
    private static int ansiC(String src, int i, StringBuilder cur) throws CurlParseException {
        int start = i;
        i += 2;
        int n = src.length();
        while (i < n) {
            char d = src.charAt(i);
            if (d == '\'') return i + 1;
            if (d == '\\' && i + 1 < n) {
                char e = src.charAt(i + 1);
                i += 2;
                switch (e) {
                    case 'n' -> cur.append('\n');
                    case 't' -> cur.append('\t');
                    case 'r' -> cur.append('\r');
                    case '0' -> cur.append('\0');
                    case 'a' -> cur.append('\u0007');
                    case 'b' -> cur.append('\b');
                    case 'e', 'E' -> cur.append('\u001b');
                    case 'f' -> cur.append('\f');
                    case 'v' -> cur.append('\u000b');
                    case 'x' -> i = hex(src, i, 2, cur);
                    case 'u' -> i = hex(src, i, 4, cur);
                    case 'U' -> i = hex(src, i, 8, cur);
                    default -> cur.append(e); // \' \" \\ and anything else
                }
                continue;
            }
            cur.append(d);
            i++;
        }
        throw new CurlParseException("Unclosed $'…' quote", src, start);
    }

    private static int hex(String src, int i, int max, StringBuilder cur) {
        int j = i;
        while (j < src.length() && j - i < max && Character.digit(src.charAt(j), 16) >= 0) j++;
        if (j == i) return i;
        cur.appendCodePoint(Integer.parseInt(src.substring(i, j), 16));
        return j;
    }
}
