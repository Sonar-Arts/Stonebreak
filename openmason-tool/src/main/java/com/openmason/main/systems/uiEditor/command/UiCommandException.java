package com.openmason.main.systems.uiEditor.command;

/**
 * A command refused to run: the message says why, in words fit for the status line. The
 * document is left exactly as it was (commands work on snapshots; project writes already made
 * by the command are reverted).
 */
public final class UiCommandException extends Exception {

    public UiCommandException(String message) {
        super(message);
    }

    public UiCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
