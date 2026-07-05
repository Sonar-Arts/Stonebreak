package com.openmason.main.systems.scripting.json;

/**
 * An op batch failed validation or execution. Carries the failing op's index
 * (-1 for batch-level problems) and an optional actionable hint.
 */
public class OpBatchException extends RuntimeException {

    private final int opIndex;
    private final String hint;

    public OpBatchException(int opIndex, String message, String hint) {
        super(message);
        this.opIndex = opIndex;
        this.hint = hint;
    }

    /** Index into the ops array, or -1 when the batch itself is malformed. */
    public int opIndex() {
        return opIndex;
    }

    /** Actionable next step, or null. */
    public String hint() {
        return hint;
    }
}
