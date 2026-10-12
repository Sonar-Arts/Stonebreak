package com.openmason.main.systems.menus.textureCreator.commands;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undo/redo stack semantics of the texture editor's command history.
 */
class CommandHistoryTest {

    /** Plain fake: logs every execute/undo into a shared journal. */
    private static final class Recording implements Command {
        private final String name;
        private final List<String> journal;

        Recording(String name, List<String> journal) {
            this.name = name;
            this.journal = journal;
        }

        @Override
        public void execute() {
            journal.add("do " + name);
        }

        @Override
        public void undo() {
            journal.add("undo " + name);
        }

        @Override
        public String getDescription() {
            return name;
        }
    }

    @Test
    void emptyHistoryHasNothingToUndoOrRedo() {
        CommandHistory history = new CommandHistory();
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertFalse(history.undo());
        assertFalse(history.redo());
        assertNull(history.peekUndo());
    }

    @Test
    void undoAndRedoRunInLastInFirstOutOrder() {
        List<String> journal = new ArrayList<>();
        CommandHistory history = new CommandHistory();
        history.executeCommand(new Recording("a", journal));
        history.executeCommand(new Recording("b", journal));

        assertTrue(history.undo());
        assertTrue(history.undo());
        assertFalse(history.undo());
        assertTrue(history.redo());
        assertTrue(history.redo());
        assertFalse(history.redo());

        assertEquals(List.of("do a", "do b", "undo b", "undo a", "do a", "do b"), journal);
    }

    @Test
    void aNewCommandDiscardsTheRedoBranch() {
        List<String> journal = new ArrayList<>();
        CommandHistory history = new CommandHistory();
        history.executeCommand(new Recording("a", journal));
        history.undo();
        assertTrue(history.canRedo());

        history.executeCommand(new Recording("b", journal));
        assertFalse(history.canRedo());
        assertFalse(history.redo());
    }

    @Test
    void pushCompletedRecordsWithoutExecutingAndStillClearsRedo() {
        List<String> journal = new ArrayList<>();
        CommandHistory history = new CommandHistory();
        history.executeCommand(new Recording("a", journal));
        history.undo();

        Recording done = new Recording("batch", journal);
        history.pushCompleted(done);

        assertEquals(List.of("do a", "undo a"), journal, "pushCompleted must not execute");
        assertSame(done, history.peekUndo());
        assertFalse(history.canRedo());
        history.undo();
        assertEquals("undo batch", journal.getLast());
    }

    @Test
    void nullCommandsAreIgnored() {
        CommandHistory history = new CommandHistory();
        history.executeCommand(null);
        history.pushCompleted(null);
        assertFalse(history.canUndo());
    }

    @Test
    void historyKeepsOnlyTheNewestHundredCommands() {
        List<String> journal = new ArrayList<>();
        CommandHistory history = new CommandHistory();
        for (int i = 0; i < 150; i++) {
            history.executeCommand(new Recording("c" + i, journal));
        }
        journal.clear();

        int undone = 0;
        while (history.undo()) {
            undone++;
        }
        assertEquals(100, undone);
        assertEquals("undo c149", journal.getFirst());
        assertEquals("undo c50", journal.getLast(), "the oldest 50 commands were evicted");
    }

    @Test
    void peekUndoIsAStableHistoryPositionMarker() {
        List<String> journal = new ArrayList<>();
        CommandHistory history = new CommandHistory();
        Recording a = new Recording("a", journal);
        history.executeCommand(a);
        Command savedAt = history.peekUndo();

        history.executeCommand(new Recording("b", journal));
        assertFalse(savedAt == history.peekUndo(), "dirty after a new edit");
        history.undo();
        assertSame(savedAt, history.peekUndo(), "clean again after undoing back to the save point");
        history.redo();
        assertFalse(savedAt == history.peekUndo());
    }

    @Test
    void clearDropsBothStacks() {
        List<String> journal = new ArrayList<>();
        CommandHistory history = new CommandHistory();
        history.executeCommand(new Recording("a", journal));
        history.executeCommand(new Recording("b", journal));
        history.undo();
        history.clear();
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
    }
}
