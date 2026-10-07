package com.openmason.main.systems.uiEditor.ops;

/**
 * A UI op batch was refused: which op ({@code -1} = the batch itself), why, and what to do
 * instead. Validation failures never touched the document; execution failures left it exactly
 * as it was (the batch is one command, see {@link UiOpBatch}).
 */
public final class UiOpException extends RuntimeException {

    private final int opIndex;
    private final String hint;

    public UiOpException(int opIndex, String message, String hint) {
        super(message);
        this.opIndex = opIndex;
        this.hint = hint;
    }

    public int opIndex() {
        return opIndex;
    }

    public String hint() {
        return hint;
    }
}
