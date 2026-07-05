package com.openmason.main.systems.scripting.commands;

/**
 * A script command failed. The message states the defect; {@link #hint()}
 * (optional) tells the caller what to do instead — both are surfaced verbatim
 * to LLM and CLI callers, so keep them short and actionable.
 */
public class CommandException extends RuntimeException {

    private final String hint;

    public CommandException(String message) {
        this(message, null);
    }

    public CommandException(String message, String hint) {
        super(message);
        this.hint = hint;
    }

    /** Actionable next step for the caller, or null. */
    public String hint() {
        return hint;
    }
}
