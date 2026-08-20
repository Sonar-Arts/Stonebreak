package com.openmason.engine.cearl;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for CEARL (Compute Efficient All-purpose Resource Language).
 *
 * <p>CEARL's surface is its own: statements end at the <b>newline</b> (no
 * semicolons), blocks are {@code header ... end} (no braces), comments run
 * from {@code ~} to end of line, and logic is worded ({@code and}/{@code or}/
 * {@code not} — {@code &&}, {@code ||}, and bare {@code !} are lex errors that
 * teach the spelling). Newlines inside parentheses or brackets are
 * insignificant, so long expressions and argument lists wrap naturally.
 *
 * <p>Consecutive newlines collapse into one NEWLINE token; leading newlines
 * emit nothing. Numbers are integer or decimal (negation is the unary
 * operator); {@code 0..6} lexes as NUMBER DOTDOT NUMBER — a dot only joins a
 * number when a digit follows it.
 */
final class CearlLexer {

    enum Type {
        IDENT, NUMBER, STRING, NEWLINE,
        LPAREN, RPAREN, LBRACKET, RBRACKET,
        COMMA, COLON, DOT, DOTDOT, ARROW,
        ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
        EQ, NE, LT, LE, GT, GE,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        EOF
    }

    record Token(Type type, String text, double number, int line, int column) {

        /** Human-readable description for error messages ("'('", "number 42", ...). */
        String describe() {
            return switch (type) {
                case IDENT -> "'" + text + "'";
                case NUMBER -> "number " + text;
                case STRING -> "string \"" + text + "\"";
                case NEWLINE -> "end of line";
                case EOF -> "end of file";
                default -> "'" + text + "'";
            };
        }
    }

    private final String source;
    private final String sourceName;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;
    private int line = 1;
    private int column = 1;
    private int bracketDepth;

    private CearlLexer(String source, String sourceName) {
        this.source = source;
        this.sourceName = sourceName;
    }

    static List<Token> lex(String source, String sourceName) {
        return new CearlLexer(source, sourceName).run();
    }

    private List<Token> run() {
        while (true) {
            skipBlanksAndComments();
            if (pos >= source.length()) {
                tokens.add(new Token(Type.EOF, "", 0, line, column));
                return tokens;
            }
            int l = line;
            int c = column;
            char ch = source.charAt(pos);
            if (ch == '\n') {
                advance();
                // Newlines are statement terminators — but only outside
                // brackets, never leading, and runs collapse to one.
                if (bracketDepth == 0 && !tokens.isEmpty()
                        && tokens.getLast().type() != Type.NEWLINE) {
                    tokens.add(new Token(Type.NEWLINE, "\\n", 0, l, c));
                }
            } else if (Character.isDigit(ch)) {
                tokens.add(lexNumber(l, c));
            } else if (Character.isLetter(ch) || ch == '_') {
                tokens.add(lexIdent(l, c));
            } else if (ch == '"') {
                tokens.add(lexString(l, c));
            } else {
                tokens.add(lexOperator(l, c));
            }
        }
    }

    private Token lexOperator(int l, int c) {
        char ch = source.charAt(pos);
        advance();
        switch (ch) {
            case '(':
                bracketDepth++;
                return tok(Type.LPAREN, "(", l, c);
            case ')':
                bracketDepth = Math.max(0, bracketDepth - 1);
                return tok(Type.RPAREN, ")", l, c);
            case '[':
                bracketDepth++;
                return tok(Type.LBRACKET, "[", l, c);
            case ']':
                bracketDepth = Math.max(0, bracketDepth - 1);
                return tok(Type.RBRACKET, "]", l, c);
            case ',': return tok(Type.COMMA, ",", l, c);
            case ':': return tok(Type.COLON, ":", l, c);
            case '%': return tok(Type.PERCENT, "%", l, c);
            case '.':
                if (eat('.')) return tok(Type.DOTDOT, "..", l, c);
                return tok(Type.DOT, ".", l, c);
            case '-':
                if (eat('>')) return tok(Type.ARROW, "->", l, c);
                if (eat('=')) return tok(Type.MINUS_ASSIGN, "-=", l, c);
                return tok(Type.MINUS, "-", l, c);
            case '=':
                if (eat('=')) return tok(Type.EQ, "==", l, c);
                return tok(Type.ASSIGN, "=", l, c);
            case '!':
                if (eat('=')) return tok(Type.NE, "!=", l, c);
                throw new CearlException(sourceName, l, c,
                    "negation is written 'not' in CEARL ('!=' is still the inequality operator)");
            case '<':
                if (eat('=')) return tok(Type.LE, "<=", l, c);
                return tok(Type.LT, "<", l, c);
            case '>':
                if (eat('=')) return tok(Type.GE, ">=", l, c);
                return tok(Type.GT, ">", l, c);
            case '+':
                if (eat('=')) return tok(Type.PLUS_ASSIGN, "+=", l, c);
                return tok(Type.PLUS, "+", l, c);
            case '*':
                if (eat('=')) return tok(Type.STAR_ASSIGN, "*=", l, c);
                return tok(Type.STAR, "*", l, c);
            case '/':
                if (eat('=')) return tok(Type.SLASH_ASSIGN, "/=", l, c);
                return tok(Type.SLASH, "/", l, c);
            case '&':
                throw new CearlException(sourceName, l, c,
                    "logical AND is written 'and' in CEARL");
            case '|':
                throw new CearlException(sourceName, l, c,
                    "logical OR is written 'or' in CEARL");
            case ';':
                throw new CearlException(sourceName, l, c,
                    "CEARL statements end at the newline — remove the ';'");
            case '{', '}':
                throw new CearlException(sourceName, l, c,
                    "CEARL blocks are written 'header ... end' — braces are not part of the syntax");
            case '#':
                throw new CearlException(sourceName, l, c,
                    "CEARL comments start with '~'");
            default:
                throw new CearlException(sourceName, l, c,
                    "unexpected character '" + ch + "'");
        }
    }

    private Token lexNumber(int l, int c) {
        int start = pos;
        boolean seenDot = false;
        while (pos < source.length()) {
            char ch = source.charAt(pos);
            if (Character.isDigit(ch) || ch == '_') {
                advance();
            } else if (ch == '.' && !seenDot && pos + 1 < source.length()
                    && Character.isDigit(source.charAt(pos + 1))) {
                seenDot = true;
                advance();
            } else {
                break;
            }
        }
        String text = source.substring(start, pos);
        double value = Double.parseDouble(text.replace("_", ""));
        return new Token(Type.NUMBER, text, value, l, c);
    }

    private Token lexIdent(int l, int c) {
        int start = pos;
        while (pos < source.length()) {
            char ch = source.charAt(pos);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                advance();
            } else {
                break;
            }
        }
        return new Token(Type.IDENT, source.substring(start, pos), 0, l, c);
    }

    private Token lexString(int l, int c) {
        advance(); // opening quote
        StringBuilder out = new StringBuilder();
        while (pos < source.length()) {
            char ch = source.charAt(pos);
            if (ch == '"') {
                advance();
                return new Token(Type.STRING, out.toString(), 0, l, c);
            }
            if (ch == '\n') {
                break;
            }
            out.append(ch);
            advance();
        }
        throw new CearlException(sourceName, l, c,
            "unterminated string — strings must open and close on one line");
    }

    /** Skips spaces/tabs and {@code ~} comments — but never the newline itself. */
    private void skipBlanksAndComments() {
        while (pos < source.length()) {
            char ch = source.charAt(pos);
            if (ch == '~') {
                while (pos < source.length() && source.charAt(pos) != '\n') {
                    advance();
                }
            } else if (ch != '\n' && Character.isWhitespace(ch)) {
                advance();
            } else {
                return;
            }
        }
    }

    private Token tok(Type type, String text, int l, int c) {
        return new Token(type, text, 0, l, c);
    }

    private boolean eat(char expected) {
        if (pos < source.length() && source.charAt(pos) == expected) {
            advance();
            return true;
        }
        return false;
    }

    private void advance() {
        if (source.charAt(pos) == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        pos++;
    }
}
