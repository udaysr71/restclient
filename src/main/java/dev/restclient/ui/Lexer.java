package dev.restclient.ui;

import java.util.ArrayList;
import java.util.List;

/** Single-line tokenizers for colouring JSON and XML/HTML. Every character of the line belongs to a token. */
final class Lexer {
    enum Type { PLAIN, KEY, STRING, NUMBER, LITERAL, PUNCT, TAG, ATTR, COMMENT }

    record Token(int start, int end, Type type) {
    }

    private Lexer() {
    }

    static List<Token> lex(CodeArea.Lang lang, String line) {
        return switch (lang) {
            case JSON -> json(line);
            case XML, HTML -> markup(line);
            case NONE -> List.of(new Token(0, line.length(), Type.PLAIN));
        };
    }

    static List<Token> json(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int start = i;
            if (c == '"') {
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == '"') break;
                }
                i = Math.min(i, n);
                int j = i;
                while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
                out.add(new Token(start, i, j < n && s.charAt(j) == ':' ? Type.KEY : Type.STRING));
            } else if (c == '-' || Character.isDigit(c)) {
                i++;
                while (i < n && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
                out.add(new Token(start, i, Type.NUMBER));
            } else if (Character.isLetter(c)) {
                while (i < n && Character.isLetter(s.charAt(i))) i++;
                String w = s.substring(start, i);
                out.add(new Token(start, i, w.equals("true") || w.equals("false") || w.equals("null") ? Type.LITERAL : Type.PLAIN));
            } else if ("{}[],:".indexOf(c) >= 0) {
                i++;
                out.add(new Token(start, i, Type.PUNCT));
            } else {
                while (i < n && "\"{}[],:-".indexOf(s.charAt(i)) < 0 && !Character.isLetterOrDigit(s.charAt(i))) i++;
                if (i == start) i++;
                out.add(new Token(start, i, Type.PLAIN));
            }
        }
        return out;
    }

    static List<Token> markup(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            int start = i;
            if (s.startsWith("<!--", i)) {
                int end = s.indexOf("-->", i + 4);
                i = end < 0 ? n : end + 3;
                out.add(new Token(start, i, Type.COMMENT));
            } else if (s.charAt(i) == '<') {
                // tag name
                i++;
                while (i < n && (s.charAt(i) == '/' || s.charAt(i) == '?' || s.charAt(i) == '!')) i++;
                while (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '>' && s.charAt(i) != '/') i++;
                out.add(new Token(start, i, Type.TAG));
                // attributes until '>'
                while (i < n && s.charAt(i) != '>') {
                    int a = i;
                    char c = s.charAt(i);
                    if (c == '"' || c == '\'') {
                        int end = s.indexOf(c, i + 1);
                        i = end < 0 ? n : end + 1;
                        out.add(new Token(a, i, Type.STRING));
                    } else if (Character.isWhitespace(c) || c == '=' || c == '/' || c == '?') {
                        i++;
                        out.add(new Token(a, i, c == '=' ? Type.PUNCT : c == '/' || c == '?' ? Type.TAG : Type.PLAIN));
                    } else {
                        while (i < n && !Character.isWhitespace(s.charAt(i)) && "=>/\"'".indexOf(s.charAt(i)) < 0) i++;
                        out.add(new Token(a, i, Type.ATTR));
                    }
                }
                if (i < n) {
                    out.add(new Token(i, i + 1, Type.TAG));
                    i++;
                }
            } else {
                int end = s.indexOf('<', i);
                i = end < 0 ? n : end;
                out.add(new Token(start, i, Type.PLAIN));
            }
        }
        return out;
    }
}
