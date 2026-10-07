package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.edit.AssetEdit;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Transactional undo/redo for one UI document. Each step is a pair of immutable document
 * snapshots plus selections and project writes, so undo and redo are exact by construction:
 * there is no inverse operation that could drift from its forward twin.
 *
 * <p>Dirty state is a position in the history, not a flag: saving records the current step's
 * serial, undoing back to it is clean again and undoing past it is dirty.
 *
 * <p>Not thread-safe; the editor uses it from the UI thread only.
 */
public final class UiHistory {

    /** Steps kept; the oldest fall off (their snapshots are then unreachable and collected). */
    public static final int MAX_STEPS = 256;

    /** One undo step. */
    public record Step(long serial, String label, OmuiArchive before, OmuiArchive after, List<String> selectionBefore,
                       List<String> selectionAfter, List<AssetEdit> assetEdits, String mergeKey) {
    }

    /** What an execute, undo or redo produced. */
    public record Outcome(OmuiArchive doc, List<String> selection, String label) {
    }

    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private long nextSerial = 1;
    /** Serial of the step on top of the undo stack when last saved; 0 = the opened state. */
    private long savedSerial;
    /** Serial of the state at the bottom of the undo stack (0 = opened; raised as old steps fall off). */
    private long baseSerial;
    /** False once the step on top may no longer absorb merges (interaction ended, undo, save). */
    private boolean mergeOpen;

    /**
     * Runs {@code command} against {@code doc} and records it.
     *
     * @return the new state, or {@code null} when the command changed nothing (no step recorded)
     * @throws UiCommandException when the command refused; nothing changed
     */
    public Outcome execute(UiCommand command, OmuiArchive doc, List<String> selection, ProjectFolder folder)
            throws UiCommandException {
        UiEditContext ctx = new UiEditContext(doc, selection, folder);
        try {
            command.apply(ctx);
        } catch (UiCommandException | RuntimeException e) {
            ctx.rollbackWrites();
            if (e instanceof UiCommandException uce) {
                throw uce;
            }
            throw new UiCommandException(command.label() + " failed: " + e.getMessage(), e);
        }
        OmuiArchive after = withRequiredFeatures(ctx.doc());
        List<String> selAfter = List.copyOf(ctx.selection());
        if (after.equals(doc) && ctx.assetEdits().isEmpty()) {
            return selAfter.equals(selection) ? null : new Outcome(doc, selAfter, null);
        }
        Step top = undo.peekLast();
        String key = command.mergeKey();
        if (key != null && mergeOpen && top != null && key.equals(top.mergeKey()) && top.serial() != savedSerial
                && ctx.assetEdits().isEmpty() && top.assetEdits().isEmpty()) {
            undo.removeLast();
            undo.addLast(new Step(top.serial(), top.label(), top.before(), after, top.selectionBefore(), selAfter,
                List.of(), key));
        } else {
            undo.addLast(new Step(nextSerial++, command.label(), doc, after, List.copyOf(selection), selAfter,
                ctx.assetEdits(), key));
            while (undo.size() > MAX_STEPS) {
                baseSerial = undo.removeFirst().serial();
            }
        }
        redo.clear();
        mergeOpen = true;
        return new Outcome(after, selAfter, command.label());
    }

    /** Ends the current interaction: the next mergeable command starts a new step. */
    public void endInteraction() {
        mergeOpen = false;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public String undoLabel() {
        return undo.isEmpty() ? null : undo.peekLast().label();
    }

    public String redoLabel() {
        return redo.isEmpty() ? null : redo.peekLast().label();
    }

    /** Labels of the undo stack, most recent first (the History panel). */
    public List<String> undoLabels() {
        List<String> out = new ArrayList<>();
        undo.descendingIterator().forEachRemaining(s -> out.add(s.label()));
        return out;
    }

    public List<String> redoLabels() {
        List<String> out = new ArrayList<>();
        redo.descendingIterator().forEachRemaining(s -> out.add(s.label()));
        return out;
    }

    /**
     * Steps back once; project writes of the step are reverted first and, if that fails (a file
     * changed since), the step stays applied and the error is thrown.
     */
    public Outcome undo(ProjectFolder folder) throws UiCommandException {
        Step s = undo.peekLast();
        if (s == null) {
            return null;
        }
        for (int i = s.assetEdits().size() - 1; i >= 0; i--) {
            AssetEdit e = s.assetEdits().get(i);
            try {
                if (!e.writes().isEmpty()) {
                    e.undo(folder);
                }
            } catch (IOException ex) {
                for (int j = i + 1; j < s.assetEdits().size(); j++) { // re-apply what we reverted
                    try {
                        s.assetEdits().get(j).apply(folder);
                    } catch (IOException ignored) {
                        // reported below
                    }
                }
                throw new UiCommandException("Cannot undo " + s.label() + ": " + ex.getMessage(), ex);
            }
        }
        undo.removeLast();
        redo.addLast(s);
        mergeOpen = false;
        return new Outcome(s.before(), s.selectionBefore(), s.label());
    }

    public Outcome redo(ProjectFolder folder) throws UiCommandException {
        Step s = redo.peekLast();
        if (s == null) {
            return null;
        }
        for (int i = 0; i < s.assetEdits().size(); i++) {
            AssetEdit e = s.assetEdits().get(i);
            try {
                if (!e.writes().isEmpty()) {
                    e.apply(folder);
                }
            } catch (IOException ex) {
                for (int j = i - 1; j >= 0; j--) {
                    try {
                        s.assetEdits().get(j).undo(folder);
                    } catch (IOException ignored) {
                        // reported below
                    }
                }
                throw new UiCommandException("Cannot redo " + s.label() + ": " + ex.getMessage(), ex);
            }
        }
        redo.removeLast();
        undo.addLast(s);
        mergeOpen = false;
        return new Outcome(s.after(), s.selectionAfter(), s.label());
    }

    /**
     * Earlier document states, newest first: the state before each undo step from the top down
     * (crash recovery falls back to the newest one that still writes).
     */
    public List<OmuiArchive> recentStates() {
        List<OmuiArchive> out = new ArrayList<>();
        undo.descendingIterator().forEachRemaining(s -> out.add(s.before()));
        return out;
    }

    /** The history as it is now, to {@link #rollbackTo} if a run that continues from here fails later. */
    public record Checkpoint(Step top, List<Step> redo) {
    }

    public Checkpoint checkpoint() {
        return new Checkpoint(undo.peekLast(), List.copyOf(redo));
    }

    /**
     * Retracts every step recorded since {@code cp} as if it never ran (#324: an automation run
     * that failed after applying its UI step): no redo entry is created and the redo stack the
     * author had at {@code cp} comes back.
     *
     * @return the state at {@code cp}, or null when nothing was recorded since
     */
    public Outcome rollbackTo(Checkpoint cp, ProjectFolder folder) throws UiCommandException {
        if (undo.peekLast() == cp.top()) {
            return null;
        }
        Step first = null;
        List<Step> retracted = new ArrayList<>();
        while (!undo.isEmpty() && undo.peekLast() != cp.top()) {
            Step s = undo.removeLast();
            for (int i = s.assetEdits().size() - 1; i >= 0; i--) {
                AssetEdit e = s.assetEdits().get(i);
                try {
                    if (!e.writes().isEmpty()) {
                        e.undo(folder);
                    }
                } catch (IOException ex) {
                    // all or nothing: put back what this step and the steps before it reverted, so
                    // the history and the project files keep describing the same document
                    reapply(s, i + 1, folder);
                    undo.addLast(s);
                    for (int r = retracted.size() - 1; r >= 0; r--) {
                        reapply(retracted.get(r), 0, folder);
                        undo.addLast(retracted.get(r));
                    }
                    throw new UiCommandException("Cannot retract " + s.label() + " (nothing was retracted): "
                        + ex.getMessage(), ex);
                }
            }
            retracted.add(s);
            first = s;
        }
        redo.clear();
        redo.addAll(cp.redo());
        mergeOpen = false;
        return new Outcome(first.before(), first.selectionBefore(), first.label());
    }

    /** Re-applies {@code s}'s asset edits from index {@code from} on (best effort; a compensation). */
    private static void reapply(Step s, int from, ProjectFolder folder) {
        for (int j = from; j < s.assetEdits().size(); j++) {
            try {
                if (!s.assetEdits().get(j).writes().isEmpty()) {
                    s.assetEdits().get(j).apply(folder);
                }
            } catch (IOException ignored) {
                // reported by the caller's exception
            }
        }
    }

    /** The current state is now what is on disk. */
    public void markSaved() {
        Step top = undo.peekLast();
        savedSerial = top == null ? baseSerial : top.serial();
        mergeOpen = false;
    }

    /** Forces dirty until the next save (a recovered or imported document that has no file yet). */
    public void markUnsaved() {
        savedSerial = -1;
    }

    public boolean isDirty() {
        Step top = undo.peekLast();
        return (top == null ? baseSerial : top.serial()) != savedSerial;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        savedSerial = 0;
        baseSerial = 0;
        mergeOpen = false;
    }

    /**
     * {@code doc} with every optional feature it uses declared in {@code requires} (the writer
     * refuses undeclared features). Declared-but-unused features are kept: the author may have
     * declared them on purpose, and removing one is a separate, explicit edit.
     */
    public static OmuiArchive withRequiredFeatures(OmuiArchive doc) {
        return UiFeatures.withInferred(doc); // the writer's own inference: the two can never drift
    }

}
