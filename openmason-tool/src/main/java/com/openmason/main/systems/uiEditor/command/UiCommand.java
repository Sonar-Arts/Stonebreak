package com.openmason.main.systems.uiEditor.command;

import java.util.List;

/**
 * One source mutation of a UI document (#293). Every edit the editor, a script or an agent
 * makes goes through a command, and every executed command is exactly one undo step: the
 * history stores the document before and after (immutable, structurally shared), the selection
 * before and after, and any project writes, so undo restores hierarchy, references, selection
 * and dirty state together.
 *
 * <p>A command reads and writes the {@link UiEditContext}; throwing {@link UiCommandException}
 * aborts it with the document untouched. Runtime preview state (hover, scripts, bindings)
 * never goes through here and so never dirties the source.
 */
public interface UiCommand {

    /** Short verb phrase for Undo/Redo menus ("Delete 2 elements"). */
    String label();

    void apply(UiEditContext ctx) throws UiCommandException;

    /**
     * Commands with the same non-null key issued during one interaction (a drag, typing in a
     * field) amend the previous step instead of adding one; see {@code UiHistory.endInteraction}.
     */
    default String mergeKey() {
        return null;
    }

    /** A command that applies {@code steps} in order as one undo step; any failure aborts all. */
    static UiCommand compound(String label, List<UiCommand> steps) {
        List<UiCommand> copy = List.copyOf(steps);
        return new UiCommand() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public void apply(UiEditContext ctx) throws UiCommandException {
                for (UiCommand c : copy) {
                    c.apply(ctx);
                }
            }
        };
    }

    /** A plain command from a lambda. */
    static UiCommand of(String label, Step step) {
        return of(label, null, step);
    }

    /** A mergeable command from a lambda. */
    static UiCommand of(String label, String mergeKey, Step step) {
        return new UiCommand() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public void apply(UiEditContext ctx) throws UiCommandException {
                step.apply(ctx);
            }

            @Override
            public String mergeKey() {
                return mergeKey;
            }
        };
    }

    @FunctionalInterface
    interface Step {
        void apply(UiEditContext ctx) throws UiCommandException;
    }
}
