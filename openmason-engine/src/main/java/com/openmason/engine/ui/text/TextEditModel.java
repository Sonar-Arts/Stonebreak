package com.openmason.engine.ui.text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Caret, selection, undo and IME composition state of one text field (#288). Pure model: no
 * painting, no key bindings. The runtime's text-field controller maps keys onto these calls.
 *
 * <p><b>Indices</b> are UTF-16 offsets that are always grapheme-cluster boundaries
 * ({@link TextBoundaries}), so no operation can split an emoji, a flag or a combining sequence.
 * The selection is {@code [min(anchor, caret), max(anchor, caret))}; it is empty when they meet.
 *
 * <p><b>Edits</b> return whether the text changed. With {@link TextInputRules#readOnly()} every
 * edit is a no-op ({@link #copy} still works). Inserted text passes {@link TextInputRules#filter}
 * and is truncated by whole clusters so the text never exceeds {@code maxLength} clusters.
 *
 * <p><b>Undo</b> keeps up to {@value #HISTORY} snapshots of (text, caret, anchor). Consecutive
 * single-cluster typing coalesces into one step until the caret moves or another kind of edit
 * happens. {@link #setText} is a programmatic value change: it clears the history.
 *
 * <p><b>Composition</b> (IME preedit): {@link #beginComposition} deletes the selection; the
 * preedit is displayed at {@link #compositionStart()} but is not part of {@link #text()} and is
 * not filtered. {@link #commitComposition} inserts the committed string through the normal
 * rules. While composing, every other edit, movement and undo call is ignored (returns false)
 * so the platform's composition and the field never disagree; {@link #setText} cancels it.
 */
public final class TextEditModel {

    static final int HISTORY = 100;

    private record Snapshot(String text, int caret, int anchor) {
    }

    private TextInputRules rules;
    private String text = "";
    private int caret;
    private int anchor;
    private int revision;
    private float goalX = Float.NaN;

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private boolean typingRun;

    private boolean composing;
    private String preedit = "";
    private int preeditCursor;

    public TextEditModel(TextInputRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public TextInputRules rules() {
        return rules;
    }

    /** Replaces the rules; the current text is kept as is (re-validate with {@link #valid()}). */
    public void setRules(TextInputRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    // ── value ───────────────────────────────────────────────────────────────

    public String text() {
        return text;
    }

    /**
     * Programmatic value: stored as given after line-end normalization (a single-line field drops
     * newlines; nothing else is filtered). Caret to the end, selection and composition cleared,
     * undo history cleared.
     */
    public void setText(String value) {
        String next = rules.normalizeLineEnds(value == null ? "" : value);
        cancelComposition();
        if (!next.equals(text)) {
            text = next;
            revision++;
        }
        caret = anchor = text.length();
        goalX = Float.NaN;
        undo.clear();
        redo.clear();
        typingRun = false;
    }

    /** Increments on every text change. */
    public int revision() {
        return revision;
    }

    /** Length in grapheme clusters. */
    public int length() {
        return TextBoundaries.count(text);
    }

    public boolean valid() {
        return rules.valid(text);
    }

    // ── caret and selection ─────────────────────────────────────────────────

    public int caret() {
        return caret;
    }

    public int anchor() {
        return anchor;
    }

    public boolean hasSelection() {
        return caret != anchor;
    }

    public int selectionStart() {
        return Math.min(caret, anchor);
    }

    public int selectionEnd() {
        return Math.max(caret, anchor);
    }

    public String selectedText() {
        return text.substring(selectionStart(), selectionEnd());
    }

    /** Moves the caret (snapped to a cluster boundary); {@code extend} keeps the anchor. */
    public void setCaret(int index, boolean extend) {
        if (composing) {
            return;
        }
        goalX = Float.NaN;
        place(index, extend);
    }

    public void select(int anchorIndex, int caretIndex) {
        if (composing) {
            return;
        }
        goalX = Float.NaN;
        typingRun = false;
        anchor = TextBoundaries.snap(text, anchorIndex);
        caret = TextBoundaries.snap(text, caretIndex);
    }

    public void selectAll() {
        select(0, text.length());
    }

    /** Selects the word (or run of non-word characters) at {@code index}: double-click. */
    public void selectWordAt(int index) {
        int[] w = TextBoundaries.wordAt(text, index);
        select(w[0], w[1]);
    }

    private void place(int index, boolean extend) {
        typingRun = false;
        caret = TextBoundaries.snap(text, index);
        if (!extend) {
            anchor = caret;
        }
    }

    // ── movement ────────────────────────────────────────────────────────────

    /** Backwards by one cluster, or to the previous word start; collapses a selection to its start. */
    public void moveLeft(boolean extend, boolean word) {
        if (composing) {
            return;
        }
        if (hasSelection() && !extend) {
            setCaret(selectionStart(), false);
            return;
        }
        setCaret(word ? TextBoundaries.previousWord(text, caret) : TextBoundaries.previous(text, caret), extend);
    }

    /** Forwards by one cluster, or to the next word end; collapses a selection to its end. */
    public void moveRight(boolean extend, boolean word) {
        if (composing) {
            return;
        }
        if (hasSelection() && !extend) {
            setCaret(selectionEnd(), false);
            return;
        }
        setCaret(word ? TextBoundaries.nextWord(text, caret) : TextBoundaries.next(text, caret), extend);
    }

    /** Home: start of the current line. */
    public void moveLineStart(boolean extend) {
        setCaret(TextBoundaries.lineStart(text, caret), extend);
    }

    /** End: end of the current line (before its newline). */
    public void moveLineEnd(boolean extend) {
        setCaret(TextBoundaries.lineEnd(text, caret), extend);
    }

    /** Ctrl+Home. */
    public void moveDocumentStart(boolean extend) {
        setCaret(0, extend);
    }

    /** Ctrl+End. */
    public void moveDocumentEnd(boolean extend) {
        setCaret(text.length(), extend);
    }

    /**
     * Up ({@code lines < 0}) or down by whole lines, keeping the caret's goal x across shorter
     * lines. The goal resets when the caret moves horizontally or the text changes.
     *
     * @param measure line geometry; {@code null} counts clusters (one unit each)
     * @return false when already on the first (or last) line; the caret then stays
     */
    public boolean moveVertical(int lines, boolean extend, TextMeasure measure) {
        if (composing || lines == 0) {
            return false;
        }
        TextMeasure m = measure == null ? TextMeasure.monospace(1f) : measure;
        int start = TextBoundaries.lineStart(text, caret);
        float x = Float.isNaN(goalX)
            ? m.advance(text.substring(start, TextBoundaries.lineEnd(text, caret)), caret - start)
            : goalX;
        int target = start;
        int moved = 0;
        for (int i = 0; i < Math.abs(lines); i++) {
            if (lines < 0) {
                if (target == 0) {
                    break;
                }
                target = TextBoundaries.lineStart(text, target - 1);
            } else {
                int end = TextBoundaries.lineEnd(text, target);
                if (end == text.length()) {
                    break;
                }
                target = end + 1;
            }
            moved++;
        }
        if (moved == 0) {
            return false;
        }
        String line = text.substring(target, TextBoundaries.lineEnd(text, target));
        place(target + m.indexAt(line, x), extend);
        goalX = x;
        return true;
    }

    // ── editing ─────────────────────────────────────────────────────────────

    /** Typing or programmatic insertion over the selection, filtered and length-limited. */
    public boolean insert(String s) {
        return insert(s, true);
    }

    private boolean insert(String s, boolean typingCandidate) {
        if (rules.readOnly() || composing) {
            return false;
        }
        String in = rules.filter(s);
        int start = selectionStart();
        int end = selectionEnd();
        if (rules.maxLength() >= 0) {
            in = fit(start, end, in);
        }
        if (in.isEmpty() && start == end) {
            return false;
        }
        boolean typing = typingCandidate && start == end && TextBoundaries.count(in) == 1;
        replace(start, end, in, typing);
        return true;
    }

    /** Truncates {@code in} by whole clusters so the result stays within maxLength. */
    private String fit(int start, int end, String in) {
        String head = text.substring(0, start);
        String tail = text.substring(end);
        int room = rules.maxLength() - TextBoundaries.count(head + tail);
        int keep = Math.max(0, Math.min(room, TextBoundaries.count(in)));
        while (keep > 0) {
            String candidate = in.substring(0, TextBoundaries.offsetOfCluster(in, keep));
            if (TextBoundaries.count(head + candidate + tail) <= rules.maxLength()) {
                return candidate;
            }
            keep--;
        }
        return "";
    }

    /** Deletes the selection, else the previous cluster (or back to the previous word start). */
    public boolean backspace(boolean word) {
        if (rules.readOnly() || composing) {
            return false;
        }
        if (hasSelection()) {
            replace(selectionStart(), selectionEnd(), "", false);
            return true;
        }
        if (caret == 0) {
            return false;
        }
        int from = word ? TextBoundaries.previousWord(text, caret) : TextBoundaries.previous(text, caret);
        replace(from, caret, "", false);
        return true;
    }

    /** Deletes the selection, else the next cluster (or forward to the next word end). */
    public boolean delete(boolean word) {
        if (rules.readOnly() || composing) {
            return false;
        }
        if (hasSelection()) {
            replace(selectionStart(), selectionEnd(), "", false);
            return true;
        }
        if (caret == text.length()) {
            return false;
        }
        int to = word ? TextBoundaries.nextWord(text, caret) : TextBoundaries.next(text, caret);
        replace(caret, to, "", false);
        return true;
    }

    /** Inserts {@code "\n"} in a multiline field; false otherwise. */
    public boolean newline() {
        return rules.multiline() && insert("\n", false);
    }

    public boolean cut(TextClipboard clipboard) {
        if (rules.readOnly() || composing || !hasSelection()) {
            return false;
        }
        clipboard.write(selectedText());
        replace(selectionStart(), selectionEnd(), "", false);
        return true;
    }

    /** Copies the selection; never changes the text. @return whether anything was copied */
    public boolean copy(TextClipboard clipboard) {
        if (!hasSelection()) {
            return false;
        }
        clipboard.write(selectedText());
        return true;
    }

    public boolean paste(TextClipboard clipboard) {
        String s = clipboard.read();
        return insert(s == null ? "" : s, false);
    }

    private void replace(int start, int end, String in, boolean typing) {
        if (!(typing && typingRun)) {
            push(undo, snapshot());
        }
        redo.clear();
        text = text.substring(0, start) + in + text.substring(end);
        caret = anchor = TextBoundaries.snap(text, start + in.length());
        goalX = Float.NaN;
        revision++;
        typingRun = typing;
    }

    // ── undo ────────────────────────────────────────────────────────────────

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public boolean undo() {
        if (composing || undo.isEmpty()) {
            return false;
        }
        push(redo, snapshot());
        restore(undo.pop());
        return true;
    }

    public boolean redo() {
        if (composing || redo.isEmpty()) {
            return false;
        }
        push(undo, snapshot());
        restore(redo.pop());
        return true;
    }

    private Snapshot snapshot() {
        return new Snapshot(text, caret, anchor);
    }

    private void restore(Snapshot s) {
        if (!s.text().equals(text)) {
            revision++;
        }
        text = s.text();
        caret = s.caret();
        anchor = s.anchor();
        goalX = Float.NaN;
        typingRun = false;
    }

    private static void push(Deque<Snapshot> stack, Snapshot s) {
        stack.push(s);
        while (stack.size() > HISTORY) {
            stack.removeLast();
        }
    }

    // ── composition ─────────────────────────────────────────────────────────

    /** Starts an IME composition at the caret, deleting the selection first. Ignored when read-only. */
    public void beginComposition() {
        if (composing || rules.readOnly()) {
            return;
        }
        if (hasSelection()) {
            replace(selectionStart(), selectionEnd(), "", false);
        }
        typingRun = false;
        composing = true;
        preedit = "";
        preeditCursor = 0;
    }

    /** Shows {@code text} as the preedit (begins a composition if none is open). */
    public void updateComposition(String text, int cursorInPreedit) {
        if (!composing) {
            beginComposition();
            if (!composing) {
                return;
            }
        }
        preedit = text == null ? "" : text;
        preeditCursor = Math.clamp(cursorInPreedit, 0, preedit.length());
    }

    /** Ends the composition and inserts {@code committed} through the normal rules. */
    public boolean commitComposition(String committed) {
        composing = false;
        preedit = "";
        preeditCursor = 0;
        return insert(committed == null ? "" : committed, false);
    }

    /** Drops the preedit; the text is unchanged. */
    public void cancelComposition() {
        composing = false;
        preedit = "";
        preeditCursor = 0;
    }

    public boolean isComposing() {
        return composing;
    }

    public String preedit() {
        return preedit;
    }

    /** Text index where the preedit is displayed (the caret when the composition began). */
    public int compositionStart() {
        return caret;
    }

    /** The text with the preedit spliced in at {@link #compositionStart()}. */
    public String displayText() {
        return composing ? text.substring(0, caret) + preedit + text.substring(caret) : text;
    }

    /** Caret position in {@link #displayText()}. */
    public int displayCaret() {
        return composing ? caret + preeditCursor : caret;
    }
}
