package com.openmason.engine.ui.text;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.openmason.engine.ui.text.TextBoundariesTest.E_ACUTE;
import static com.openmason.engine.ui.text.TextBoundariesTest.FAMILY;
import static com.openmason.engine.ui.text.TextBoundariesTest.FLAGS;
import static com.openmason.engine.ui.text.TextBoundariesTest.THUMB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEditModelTest {

    /** In-memory clipboard. */
    static final class FakeClipboard implements TextClipboard {
        String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String text) {
            value = text;
        }
    }

    private static TextEditModel model(String text) {
        TextEditModel m = new TextEditModel(TextInputRules.singleLine());
        m.setText(text);
        return m;
    }

    private static void type(TextEditModel m, String s) {
        s.codePoints().forEach(cp -> m.insert(Character.toString(cp)));
    }

    // ── Unicode safety ──────────────────────────────────────────────────────

    @Test
    void movementAndDeletionNeverSplitAClusterOrSurrogatePair() {
        String s = "a" + FAMILY + E_ACUTE + THUMB + FLAGS;
        TextEditModel m = model(s);
        int clusters = TextBoundaries.count(s);
        for (int i = 0; i < clusters; i++) {
            m.moveLeft(false, false);
            assertTrue(TextBoundaries.isBoundary(m.text(), m.caret()));
        }
        assertEquals(0, m.caret());
        m.moveDocumentEnd(false);
        int before = m.length();
        while (m.backspace(false)) {
            assertEquals(--before, m.length(), "one cluster per backspace");
            assertTrue(TextBoundaries.isBoundary(m.text(), m.caret()));
        }
        assertEquals("", m.text());

        m.setText(s);
        m.moveDocumentStart(false);
        m.moveRight(false, false);
        assertTrue(m.delete(false));
        assertEquals("a" + E_ACUTE + THUMB + FLAGS, m.text(), "the whole family went");
    }

    @Test
    void setCaretSnapsIntoClusters() {
        TextEditModel m = model("x" + THUMB);
        m.setCaret(2, false); // between the thumb's surrogates
        assertEquals(1, m.caret());
        m.setCaret(99, false);
        assertEquals(m.text().length(), m.caret());
    }

    @Test
    void maxLengthTruncatesByWholeClusters() {
        TextEditModel m = new TextEditModel(TextInputRules.singleLine().withMaxLength(3));
        assertTrue(m.insert("ab" + FAMILY + "c"));
        assertEquals("ab" + FAMILY, m.text());
        assertFalse(m.insert("z"), "full");
        assertEquals(3, m.length());
        m.selectAll();
        assertTrue(m.insert(FLAGS + FLAGS), "selection makes room");
        assertEquals(FLAGS + "🇺🇸", m.text(), "three flags, the fourth dropped");
    }

    // ── selection ───────────────────────────────────────────────────────────

    @Test
    void collapsingASelectionGoesToTheSideOfTheArrow() {
        TextEditModel m = model("hello world");
        m.select(2, 8);
        m.moveLeft(false, false);
        assertEquals(2, m.caret());
        assertFalse(m.hasSelection());
        m.select(8, 2);
        m.moveRight(false, false);
        assertEquals(8, m.caret());
    }

    @Test
    void shiftMovementExtendsFromTheAnchor() {
        TextEditModel m = model("hello world");
        m.setCaret(5, false);
        m.moveRight(true, true);
        assertEquals(" world", m.selectedText());
        m.moveLeft(true, false);
        assertEquals(" worl", m.selectedText());
        m.moveLineStart(true);
        assertEquals("hello", m.selectedText());
        assertEquals(0, m.selectionStart());
        assertEquals(5, m.anchor());
    }

    @Test
    void selectAllAndWord() {
        TextEditModel m = model("hello, world");
        m.selectWordAt(9);
        assertEquals("world", m.selectedText());
        m.selectAll();
        assertEquals("hello, world", m.selectedText());
        assertEquals(12, m.caret());
    }

    @Test
    void wordDeletion() {
        TextEditModel m = model("one two three");
        assertTrue(m.backspace(true));
        assertEquals("one two ", m.text());
        m.moveDocumentStart(false);
        assertTrue(m.delete(true));
        assertEquals(" two ", m.text());
    }

    // ── clipboard ───────────────────────────────────────────────────────────

    @Test
    void cutCopyPaste() {
        FakeClipboard cb = new FakeClipboard();
        TextEditModel m = model("hello world");
        assertFalse(m.copy(cb), "nothing selected");
        m.select(0, 5);
        assertTrue(m.copy(cb));
        assertEquals("hello", cb.value);
        assertEquals("hello world", m.text());
        assertTrue(m.cut(cb));
        assertEquals(" world", m.text());
        m.moveDocumentEnd(false);
        assertTrue(m.paste(cb));
        assertEquals(" worldhello", m.text());
        cb.value = "";
        assertFalse(m.paste(cb), "empty clipboard");
    }

    @Test
    void pasteIntoASingleLineFieldDropsNewlines() {
        FakeClipboard cb = new FakeClipboard();
        cb.value = "a\r\nb\nc\rd\te";
        TextEditModel m = model("");
        m.paste(cb);
        assertEquals("abcde", m.text(), "line ends removed, tab is an ISO control");

        TextEditModel ml = new TextEditModel(TextInputRules.multiLine());
        ml.paste(cb);
        assertEquals("a\nb\nc\nde", ml.text(), "multiline normalizes line ends");
    }

    @Test
    void asciiPrintableFilterIsTheChatRule() {
        TextEditModel m = new TextEditModel(TextInputRules.singleLine().withAllowed(TextInputRules.ASCII_PRINTABLE));
        assertTrue(m.insert("café " + THUMB + "~ok"));
        assertEquals("caf ~ok", m.text());
        assertFalse(m.insert("é"), "nothing left after filtering");

        TextEditModel d = new TextEditModel(TextInputRules.singleLine().withAllowed(TextInputRules.DIGITS));
        d.insert("a1b2");
        assertEquals("12", d.text());
    }

    @Test
    void readOnlyAllowsOnlySelectionAndCopy() {
        FakeClipboard cb = new FakeClipboard();
        TextEditModel m = new TextEditModel(TextInputRules.singleLine().withReadOnly(true));
        m.setText("locked");
        m.selectAll();
        assertFalse(m.insert("x"));
        assertFalse(m.backspace(false));
        assertFalse(m.delete(false));
        assertFalse(m.cut(cb));
        assertFalse(m.paste(cb));
        assertTrue(m.copy(cb));
        assertEquals("locked", cb.value);
        assertEquals("locked", m.text());
        m.beginComposition();
        assertFalse(m.isComposing(), "no IME into a read-only field");
    }

    @Test
    void validationIsACommitCheckNotATypingFilter() {
        TextEditModel m = new TextEditModel(TextInputRules.singleLine().withPattern(Pattern.compile("-?\\d+")));
        m.insert("-");
        assertEquals("-", m.text(), "still typeable");
        assertFalse(m.valid());
        m.insert("5");
        assertTrue(m.valid());
    }

    // ── multiline ───────────────────────────────────────────────────────────

    @Test
    void newlineOnlyInMultiline() {
        TextEditModel single = model("a");
        assertFalse(single.newline());
        TextEditModel m = new TextEditModel(TextInputRules.multiLine());
        m.insert("a");
        assertTrue(m.newline());
        m.insert("b");
        assertEquals("a\nb", m.text());
    }

    @Test
    void verticalMovementKeepsTheGoalColumnAcrossAShortLine() {
        TextEditModel m = new TextEditModel(TextInputRules.multiLine());
        m.setText("abcdef\nab\nabcdef");
        m.setCaret(5, false); // line 0, column 5
        assertFalse(m.moveVertical(-1, false, null), "already on the first line");
        assertEquals(5, m.caret());
        assertTrue(m.moveVertical(1, false, null));
        assertEquals(9, m.caret(), "clamped to the end of \"ab\"");
        assertTrue(m.moveVertical(1, false, null));
        assertEquals(15, m.caret(), "goal column 5 restored on the long line");
        assertFalse(m.moveVertical(1, false, null));
        m.moveLeft(false, false); // horizontal movement resets the goal
        m.moveVertical(-2, false, null);
        assertEquals(4, m.caret());
    }

    @Test
    void verticalMovementUsesTheMeasure() {
        TextMeasure wide = TextMeasure.monospace(10f);
        TextEditModel m = new TextEditModel(TextInputRules.multiLine());
        m.setText("a" + FAMILY + "b\nxyz");
        m.setCaret(1 + FAMILY.length(), false); // x = 20
        m.moveVertical(1, true, wide);
        assertEquals(m.text().indexOf('\n') + 3, m.caret());
        assertTrue(m.hasSelection(), "shift+down extends");
        m.moveVertical(-1, false, wide);
        assertEquals(1 + FAMILY.length(), m.caret());
        m.moveLineEnd(false);
        assertEquals(m.text().indexOf('\n'), m.caret());
        m.moveLineStart(false);
        assertEquals(0, m.caret());
    }

    // ── undo ────────────────────────────────────────────────────────────────

    @Test
    void consecutiveTypingIsOneUndoStep() {
        TextEditModel m = model("");
        assertFalse(m.canUndo());
        type(m, "hello");
        m.moveLeft(false, false); // breaks the run
        m.moveRight(false, false);
        type(m, "!!");
        assertTrue(m.undo());
        assertEquals("hello", m.text());
        assertTrue(m.undo());
        assertEquals("", m.text());
        assertFalse(m.undo());
        assertTrue(m.redo());
        assertEquals("hello", m.text());
        assertTrue(m.redo());
        assertEquals("hello!!", m.text());
        assertEquals(7, m.caret());
        assertFalse(m.canRedo());
    }

    @Test
    void anEditAfterUndoClearsRedoAndOtherEditsAreTheirOwnSteps() {
        FakeClipboard cb = new FakeClipboard();
        cb.value = "XY";
        TextEditModel m = model("");
        type(m, "ab");
        m.paste(cb);
        m.backspace(false);
        assertEquals("abX", m.text());
        m.undo();
        assertEquals("abXY", m.text());
        m.undo();
        assertEquals("ab", m.text());
        type(m, "c");
        assertFalse(m.canRedo());
    }

    @Test
    void historyIsCappedAndSetTextClearsIt() {
        TextEditModel m = model("");
        for (int i = 0; i < 150; i++) {
            m.insert("x");
            m.moveLeft(false, false);
            m.moveRight(false, false);
        }
        int undos = 0;
        while (m.undo()) {
            undos++;
        }
        assertEquals(TextEditModel.HISTORY, undos);
        m.setText("fresh");
        assertFalse(m.canUndo());
        assertFalse(m.canRedo());
    }

    @Test
    void revisionCountsTextChanges() {
        TextEditModel m = model("a");
        int r = m.revision();
        m.moveLeft(false, false);
        assertEquals(r, m.revision(), "movement is not a change");
        m.insert("b");
        assertEquals(r + 1, m.revision());
        m.setText("ba");
        assertEquals(r + 1, m.revision(), "same value");
        m.setText("c");
        assertEquals(r + 2, m.revision());
    }

    // ── composition ─────────────────────────────────────────────────────────

    @Test
    void compositionShowsAPreeditThenCommitsIt() {
        TextEditModel m = model("ab");
        m.setCaret(1, false);
        m.beginComposition();
        m.updateComposition("に", 1);
        assertTrue(m.isComposing());
        assertEquals("ab", m.text(), "preedit is not text");
        assertEquals("aにb", m.displayText());
        assertEquals(2, m.displayCaret());
        assertEquals(1, m.compositionStart());
        m.updateComposition("にほ", 1);
        assertEquals("aにほb", m.displayText());
        assertEquals(2, m.displayCaret(), "cursor inside the preedit");
        assertTrue(m.commitComposition("日本"));
        assertFalse(m.isComposing());
        assertEquals("a日本b", m.text());
        assertEquals(3, m.caret());
        assertEquals(m.text(), m.displayText());
    }

    @Test
    void compositionReplacesTheSelectionAndCancelKeepsTheRest() {
        TextEditModel m = model("hello world");
        m.select(0, 5);
        m.updateComposition("k", 1); // implicitly begins
        assertEquals(" world", m.text(), "selection deleted at begin");
        assertEquals("k world", m.displayText());
        m.cancelComposition();
        assertFalse(m.isComposing());
        assertEquals(" world", m.text());
        assertEquals("", m.preedit());
        assertTrue(m.undo(), "the selection deletion is undoable");
        assertEquals("hello world", m.text());
    }

    @Test
    void otherEditsAreIgnoredWhileComposing() {
        FakeClipboard cb = new FakeClipboard();
        cb.value = "zz";
        TextEditModel m = model("ab");
        m.beginComposition();
        m.updateComposition("x", 1);
        assertFalse(m.insert("q"));
        assertFalse(m.backspace(false));
        assertFalse(m.delete(false));
        assertFalse(m.paste(cb));
        assertFalse(m.undo());
        m.moveLeft(false, false);
        assertEquals(2, m.caret(), "movement ignored");
        m.setText("new");
        assertFalse(m.isComposing(), "setText cancels the composition");
        assertEquals("new", m.displayText());
    }

    @Test
    void committedTextGoesThroughTheRules() {
        TextEditModel m = new TextEditModel(TextInputRules.singleLine().withMaxLength(2)
            .withAllowed(TextInputRules.ASCII_PRINTABLE));
        m.beginComposition();
        m.updateComposition("ééé", 3); // preedit is never filtered
        assertEquals("ééé", m.displayText());
        m.commitComposition("aébc");
        assertEquals("ab", m.text());
    }
}
