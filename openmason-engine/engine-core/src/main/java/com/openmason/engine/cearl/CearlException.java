package com.openmason.engine.cearl;

/**
 * A CEARL compile error — lexing, parsing, type checking, or plan validation.
 * Always carries the source name and the 1-based line/column of the offending
 * token, and the message teaches: it names what was found AND what was
 * expected, so a broken program is fixable from the log alone.
 */
public final class CearlException extends RuntimeException {

    private final String sourceName;
    private final int line;
    private final int column;

    public CearlException(String sourceName, int line, int column, String message) {
        super(sourceName + ":" + line + (column > 0 ? ":" + column : "") + ": " + message);
        this.sourceName = sourceName;
        this.line = line;
        this.column = column;
    }

    public String sourceName() {
        return sourceName;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
