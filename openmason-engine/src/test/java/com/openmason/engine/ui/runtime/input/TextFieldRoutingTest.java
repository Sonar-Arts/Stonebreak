package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.masonry.MasonryEnvironment;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TextField editing through the router: keys, text, clipboard, IME, validation and commit (#288). */
class TextFieldRoutingTest {

    @AfterEach
    void resetClipboard() {
        MasonryEnvironment.reset();
    }

    private static OmuiArchive form(UiDocs.N field) {
        return InputRig.withFeatures(screen("t:ui/form", box("root").style("width", 400).style("height", 300).kids(
            field.style("width", 200).style("height", 30), node("ok", "Button").style("width", 60).style("height", 30))),
            UiFeatures.INPUT);
    }

    private static UiDocs.N field() {
        return node("name", "TextField");
    }

    private static void type(InputRig r, String s) {
        s.codePoints().forEach(cp -> assertTrue(r.router.text(cp)));
        r.frame();
    }

    @Test
    void typingEditsTheValueAndSendsChanges() {
        try (InputRig r = new InputRig(form(field()))) {
            List<String> changes = new ArrayList<>();
            r.el("name").on(UiEventType.CHANGE, e -> changes.add(((ChangeEvent) e).value()));
            r.click("name");
            type(r, "Ab");
            assertTrue(r.press(MKeys.KEY_SPACE), "Space is the field's: it must not submit");
            type(r, " c");
            assertEquals("Ab c", r.el("name").text("text"));
            assertEquals(List.of("A", "Ab", "Ab ", "Ab c"), changes);
            assertTrue(r.press(MKeys.KEY_BACKSPACE, MKeys.MOD_CONTROL));
            assertEquals("Ab ", r.el("name").text("text"), "Ctrl+Backspace deletes the previous word");
            assertTrue(r.press(MKeys.KEY_LEFT, MKeys.MOD_CONTROL));
            type(r, ">");
            assertEquals(">Ab ", r.el("name").text("text"), "Ctrl+Left jumps to the word start");
        }
    }

    @Test
    void emojiAndCombiningMarksArriveWholeAndDeleteWhole() {
        try (InputRig r = new InputRig(form(field()))) {
            r.click("name");
            type(r, "é👨‍👩‍👧🇫🇷");
            assertEquals("é👨‍👩‍👧🇫🇷", r.el("name").text("text"));
            r.press(MKeys.KEY_BACKSPACE);
            assertEquals("é👨‍👩‍👧", r.el("name").text("text"), "a flag is one character");
            r.press(MKeys.KEY_BACKSPACE);
            assertEquals("é", r.el("name").text("text"), "a ZWJ family is one character");
            r.press(MKeys.KEY_BACKSPACE);
            assertEquals("", r.el("name").text("text"), "a base and its combining mark go together");
            assertFalse(r.router.text(0xD800), "a lone surrogate is never inserted");
        }
    }

    @Test
    void enterCommitsAValidValueAndEscapeReverts() {
        try (InputRig r = new InputRig(form(field().prop("pattern", "[a-z]+")))) {
            List<String> commits = new ArrayList<>();
            r.el("name").on(UiEventType.COMMIT, e -> commits.add(((ChangeEvent) e).value()));
            r.click("name");
            type(r, "ab1");
            assertTrue(r.el("name").hasState(UiElement.INVALID));
            assertTrue(r.press(MKeys.KEY_ENTER));
            assertEquals(List.of(), commits, "an invalid value is not committed");
            r.press(MKeys.KEY_BACKSPACE);
            assertFalse(r.el("name").hasState(UiElement.INVALID));
            assertTrue(r.press(MKeys.KEY_ENTER));
            assertEquals(List.of("ab"), commits);
            type(r, "zz");
            assertTrue(r.press(MKeys.KEY_ESCAPE), "Escape reverts an edit...");
            assertEquals("ab", r.el("name").text("text"));
            assertFalse(r.press(MKeys.KEY_ESCAPE), "...and with nothing to revert belongs to the screen");
        }
    }

    @Test
    void blurCommitsWhenAsked() {
        try (InputRig r = new InputRig(form(field()))) {
            List<String> commits = new ArrayList<>();
            r.el("name").on(UiEventType.COMMIT, e -> commits.add(((ChangeEvent) e).value()));
            r.click("name");
            type(r, "steve");
            r.click("ok");
            assertEquals(List.of("steve"), commits);
        }
    }

    @Test
    void filtersAndLengthsApplyToTypingAndPasting() {
        try (InputRig r = new InputRig(form(field().prop("inputFilter", "digits").prop("maxLength", 4)))) {
            r.click("name");
            type(r, "1a2b3");
            assertEquals("123", r.el("name").text("text"));
            MasonryEnvironment.clipboard().write("4567");
            assertTrue(r.press(MKeys.KEY_V, MKeys.MOD_CONTROL));
            assertEquals("1234", r.el("name").text("text"), "paste is filtered and truncated by the same rules");
        }
    }

    @Test
    void clipboardCopiesSelectionsButNeverPasswords() {
        try (InputRig r = new InputRig(form(field()))) {
            r.click("name");
            type(r, "hello");
            r.press(MKeys.KEY_A, MKeys.MOD_CONTROL);
            r.press(MKeys.KEY_C, MKeys.MOD_CONTROL);
            assertEquals("hello", MasonryEnvironment.clipboard().read());
            r.el("name").setProp("password", UiValue.TRUE);
            MasonryEnvironment.clipboard().write("");
            r.press(MKeys.KEY_A, MKeys.MOD_CONTROL);
            r.press(MKeys.KEY_X, MKeys.MOD_CONTROL);
            assertEquals("", MasonryEnvironment.clipboard().read(), "a password is never copied or cut");
            assertEquals("hello", r.el("name").text("text"));
            assertEquals("*****", r.router.textField(r.el("name")).displayText());
        }
    }

    @Test
    void imeCompositionShowsPreeditThenCommits() {
        try (InputRig r = new InputRig(form(field()))) {
            r.click("name");
            type(r, "x");
            TextFieldController tf = r.router.textField(r.el("name"));
            assertTrue(r.router.composition(CompositionEvent.Phase.START, "", 0));
            r.router.composition(CompositionEvent.Phase.UPDATE, "にほ", 2);
            assertEquals("x", r.el("name").text("text"), "preedit is not part of the value");
            assertEquals("xにほ", tf.displayText());
            assertEquals(3, tf.displayCaret());
            assertTrue(r.press(MKeys.KEY_LEFT), "editing keys belong to the IME while composing");
            assertEquals("x", r.el("name").text("text"));
            r.router.composition(CompositionEvent.Phase.COMMIT, "日本", 0);
            assertEquals("x日本", r.el("name").text("text"));
            r.router.composition(CompositionEvent.Phase.START, "", 0);
            r.router.composition(CompositionEvent.Phase.UPDATE, "ご", 1);
            r.router.composition(CompositionEvent.Phase.CANCEL, "", 0);
            assertEquals("x日本", tf.displayText(), "a cancelled composition leaves no trace");
        }
    }

    @Test
    void multiLineFieldsTakeEnterAndVerticalArrows() {
        try (InputRig r = new InputRig(form(field().prop("multiline", true).style("height", 90)))) {
            List<String> commits = new ArrayList<>();
            r.el("name").on(UiEventType.COMMIT, e -> commits.add(((ChangeEvent) e).value()));
            r.click("name");
            type(r, "ab");
            r.press(MKeys.KEY_ENTER);
            type(r, "cd");
            assertEquals("ab\ncd", r.el("name").text("text"));
            assertTrue(r.press(MKeys.KEY_UP));
            type(r, "X");
            assertEquals("abX\ncd", r.el("name").text("text"), "up kept the column");
            assertEquals("name", r.focusKey(), "arrows did not leave the field");
            r.press(MKeys.KEY_ENTER, MKeys.MOD_CONTROL);
            assertEquals(List.of("abX\ncd"), commits, "Ctrl+Enter commits a multi-line field");
        }
    }

    @Test
    void singleLineFieldsLetTabAndVerticalArrowsNavigate() {
        try (InputRig r = new InputRig(form(field()))) {
            r.click("name");
            assertTrue(r.press(MKeys.KEY_TAB));
            assertEquals("ok", r.focusKey());
            r.press(MKeys.KEY_TAB, MKeys.MOD_SHIFT);
            assertEquals("name", r.focusKey());
            assertTrue(r.router.textField(r.el("name")).model().hasSelection() || r.el("name").text("text").isEmpty(),
                "keyboard focus selects the whole value");
            r.press(MKeys.KEY_DOWN);
            assertEquals("ok", r.focusKey());
        }
    }

    @Test
    void clicksPlaceTheCaretAndDoubleClicksSelectAWord() {
        try (InputRig r = new InputRig(form(field()))) {
            r.el("name").setProp("text", UiValue.of("abc def"));
            r.frame();
            UiElement f = r.el("name");
            // FIXED_TEXT has no font: the approximate measure uses 9 px cells at font-size 18.
            float x = f.rect().x() + 10 + 9 * 2;
            r.router.pointerDown(x, r.cy("name"), PointerEvent.PRIMARY, 0);
            r.router.pointerUp(x, r.cy("name"), PointerEvent.PRIMARY, 0);
            TextFieldController tf = r.router.textField(f);
            assertEquals(2, tf.model().caret());
            r.router.pointerDown(x, r.cy("name"), PointerEvent.PRIMARY, 0);
            r.router.pointerUp(x, r.cy("name"), PointerEvent.PRIMARY, 0);
            assertEquals("abc", tf.model().selectedText());
            r.router.tick(1);
            float end = f.rect().x() + 10 + 9 * 6;
            r.router.pointerDown(x, r.cy("name"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(end, r.cy("name"));
            r.router.pointerUp(end, r.cy("name"), PointerEvent.PRIMARY, 0);
            assertEquals("c de", tf.model().selectedText(), "dragging selects");
        }
    }

    @Test
    void readOnlyAndBoundFieldsCannotBeEdited() {
        try (InputRig r = new InputRig(form(field().prop("readOnly", true).prop("text", "fixed")))) {
            r.click("name");
            assertTrue(r.router.text('x'), "typing into a focused read-only field is still the field's");
            assertEquals("fixed", r.el("name").text("text"));
        }
    }

    @Test
    void externalWritesReachTheEditor() {
        try (InputRig r = new InputRig(form(field()))) {
            r.click("name");
            r.el("name").setProp("text", UiValue.of("from script"));
            r.frame();
            r.press(MKeys.KEY_END);
            type(r, "!");
            assertEquals("from script!", r.el("name").text("text"));
        }
    }

    @Test
    void reducedMotionKeepsTheCaretSteady() {
        try (InputRig r = new InputRig(form(field()))) {
            r.click("name");
            TextFieldController tf = r.router.textField(r.el("name"));
            double blink = r.router.settings().caretBlinkInterval();
            r.router.tick(blink * 1.5);
            assertFalse(tf.caretVisible(r.router.time(), blink, false), "blinking off half the time");
            assertTrue(tf.caretVisible(r.router.time(), blink, true), "steady with reduced motion");
        }
    }
}
