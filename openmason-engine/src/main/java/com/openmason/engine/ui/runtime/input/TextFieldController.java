package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MClipboard;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.TextLineMetrics;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.text.TextBoundaries;
import com.openmason.engine.ui.text.TextClipboard;
import com.openmason.engine.ui.text.TextDirection;
import com.openmason.engine.ui.text.TextEditModel;
import com.openmason.engine.ui.text.TextInputRules;

import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Editing behaviour of one {@code TextField} element (#288), over a {@link TextEditModel}.
 * The element's {@code text} property is the value: every edit writes it (and sends
 * {@code CHANGE}); Enter (or blur with {@code commitOnBlur}) sends {@code COMMIT} when the value
 * passes {@code pattern}, otherwise the field shows {@code :invalid} and nothing is committed.
 * Escape reverts to the value the field had when it gained focus.
 *
 * <p>Keys: arrows (Ctrl = by word, Shift = select; Left/Right follow an RTL paragraph
 * visually), Home/End (Ctrl = whole text), Up/Down between lines of a multi-line field,
 * Backspace/Delete (Ctrl = word), Ctrl+A/C/X/V, Ctrl+Z/Y and Ctrl+Shift+Z, Enter (newline in
 * a multi-line field, Ctrl+Enter commits there). Printable keys are consumed so their text
 * arrives through text input instead of triggering UI actions (Space would otherwise submit).
 *
 * <p><b>Keyboard layouts.</b> Shortcuts read the key token the host passes. GLFW tokens name
 * physical US-layout keys, so hosts translate letter keys to the active layout first (the game's
 * and the editor's {@code LayoutKeys}); then Ctrl+Z is undo on QWERTZ and AZERTY too. Ctrl
 * together with Alt is AltGr on Windows (AltGr+A types "ą" on a Polish layout), so it is
 * never a shortcut: a printable key with AltGr is typing and is consumed like any other.
 *
 * <p><b>What a focused field keeps from the screen and the world.</b> Every key it edits with,
 * printable and keypad keys, and the modifier keys themselves (a Shift held to type a capital
 * must not also make the player sneak). Function keys, Tab, Escape with nothing to revert and
 * plain-Alt chords pass through: screenshots, debug keys and screen shortcuts keep working
 * while a field has focus.
 * A {@code password} field never copies or cuts. A field whose {@code text} is owned by a
 * binding is read-only until two-way bindings (#289) exist.
 */
public final class TextFieldController {

    private static final TextClipboard SYSTEM = new TextClipboard() {
        @Override
        public String read() {
            return MClipboard.read();
        }

        @Override
        public void write(String text) {
            MClipboard.write(text);
        }
    };

    private final UiElement element;
    private final EventDispatcher dispatcher;
    private final DoubleSupplier clock;
    private final Supplier<TextLineMetrics> metrics;
    private final TextEditModel model = new TextEditModel(TextInputRules.singleLine());
    private String rulesKey = "";
    /** The {@code text} prop as last seen or written, raw, so normalization never looks like an outside edit. */
    private String lastProp;
    private String valueAtFocus = "";
    private String committed;
    private boolean focused;
    private double blinkStart;
    private float scrollX;

    TextFieldController(UiElement element, EventDispatcher dispatcher, DoubleSupplier clock,
                        Supplier<TextLineMetrics> metrics) {
        this.element = element;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.metrics = metrics;
        sync();
        committed = model.text();
    }

    public UiElement element() {
        return element;
    }

    /** The editing model (caret, selection, composition) the painter reads. */
    public TextEditModel model() {
        return model;
    }

    public boolean focused() {
        return focused;
    }

    /** Masked display for {@code password} fields: one {@code *} per character. */
    public boolean password() {
        return element.prop("password") instanceof UiValue.Bool b && b.value();
    }

    public boolean multiline() {
        return element.prop("multiline") instanceof UiValue.Bool b && b.value();
    }

    /** Caret phase: steady with reduced motion or a zero interval, else on for the first half of each period. */
    public boolean caretVisible(double now, double interval, boolean reducedMotion) {
        if (!focused) {
            return false;
        }
        if (reducedMotion || interval <= 0) {
            return true;
        }
        return ((now - blinkStart) % (2 * interval)) < interval;
    }

    /** Horizontal scroll of a single-line field, adjusted so the caret is visible. */
    public float scrollX(TextFieldGeometry g, TextLineMetrics m) {
        if (multiline()) {
            return 0;
        }
        String shown = displayText();
        float caret = m.measure().advance(shown, displayCaret());
        float full = m.measure().advance(shown, shown.length());
        if (caret - scrollX > g.width()) {
            scrollX = caret - g.width();
        }
        if (caret - scrollX < 0) {
            scrollX = caret;
        }
        scrollX = Math.clamp(scrollX, 0, Math.max(0, full - g.width()));
        return scrollX;
    }

    /** What is drawn: the value with any preedit spliced in, masked for passwords. */
    public String displayText() {
        String t = model.displayText();
        return password() ? "*".repeat(TextBoundaries.count(t)) : t;
    }

    /** The caret index in {@link #displayText()}. */
    public int displayCaret() {
        return password() ? TextBoundaries.count(model.displayText().substring(0, model.displayCaret()))
            : model.displayCaret();
    }

    /** Maps a value index into {@link #displayText()} (masking and preedit accounted for). */
    public int displayIndex(int valueIndex) {
        String t = model.text();
        int i = Math.clamp(valueIndex, 0, t.length());
        if (model.isComposing() && i > model.compositionStart()) {
            i += model.preedit().length();
        }
        return password() ? TextBoundaries.count(model.displayText().substring(0, i)) : i;
    }

    /** Maps a display index back to the value (identity unless masked). */
    int valueIndex(int displayIndex) {
        return password() ? TextBoundaries.offsetOfCluster(model.text(), displayIndex) : displayIndex;
    }

    // ── element ↔ model ─────────────────────────────────────────────────────

    /** Picks up rule and value changes made to the element by bindings, scripts or overrides. */
    void sync() {
        String key = rulesSignature();
        if (!key.equals(rulesKey)) {
            rulesKey = key;
            model.setRules(rules());
        }
        String value = element.text("text");
        if (!value.equals(lastProp)) {
            lastProp = value;
            model.setText(value);
            if (!focused) {
                committed = model.text();
            }
        }
    }

    private String rulesSignature() {
        return element.prop("multiline") + "|" + element.prop("maxLength") + "|" + readOnly() + "|"
            + element.prop("inputFilter") + "|" + element.prop("pattern");
    }

    /** {@code readOnly}, or a value owned by a one-way binding; a two-way bound field edits into the draft (#289). */
    private boolean readOnly() {
        UiNode.BindingMode mode = element.bindingMode("prop:text");
        return element.prop("readOnly") instanceof UiValue.Bool b && b.value()
            || mode == UiNode.BindingMode.TO_TARGET || mode == UiNode.BindingMode.ONCE;
    }

    private TextInputRules rules() {
        TextInputRules r = multiline() ? TextInputRules.multiLine() : TextInputRules.singleLine();
        int max = element.prop("maxLength") instanceof UiValue.Num n ? (int) n.value() : -1;
        r = r.withMaxLength(max).withReadOnly(readOnly()).withAllowed(filter(element.text("inputFilter")));
        if (element.prop("pattern") instanceof UiValue.Str p && !p.value().isEmpty()) {
            try {
                r = r.withPattern(Pattern.compile(p.value()));
            } catch (PatternSyntaxException e) {
                element.owner().reportDiagnostic(UiRuntimeDiagnostic.error(UiRuntimeDiagnostic.Code.PROPERTY_TYPE,
                    element.key(), "pattern is not a valid regular expression: " + e.getDescription()));
            }
        }
        return r;
    }

    static IntPredicate filter(String name) {
        return switch (name) {
            case "ascii" -> TextInputRules.ASCII_PRINTABLE;
            case "digits" -> TextInputRules.DIGITS;
            case "integer" -> cp -> TextInputRules.DIGITS.test(cp) || cp == '-';
            case "decimal" -> cp -> TextInputRules.DIGITS.test(cp) || cp == '-' || cp == '.' || cp == ',';
            case "identifier" -> cp -> Character.isLetterOrDigit(cp) || cp == '_' || cp == '-' || cp == '.';
            default -> null;
        };
    }

    // ── focus ───────────────────────────────────────────────────────────────

    void focusIn(InputDevice device) {
        sync();
        focused = true;
        valueAtFocus = model.text();
        if (device.showsFocus()) {
            model.selectAll();
        }
        blink();
    }

    void focusOut() {
        if (model.isComposing()) {
            model.cancelComposition();
        }
        focused = false;
        boolean commitOnBlur = !(element.prop("commitOnBlur") instanceof UiValue.Bool b) || b.value();
        if (commitOnBlur && !model.text().equals(committed)) {
            commit();
        }
    }

    // ── input ───────────────────────────────────────────────────────────────

    /** @return true when the key belongs to the field (it must not also trigger a UI action) */
    boolean key(int key, int mods) {
        boolean altGr = (mods & MKeys.MOD_CONTROL) != 0 && (mods & MKeys.MOD_ALT) != 0;
        boolean ctrl = (mods & MKeys.MOD_SUPER) != 0 || (mods & MKeys.MOD_CONTROL) != 0 && !altGr;
        boolean shift = (mods & MKeys.MOD_SHIFT) != 0;
        if (model.isComposing()) {
            return key != MKeys.KEY_TAB; // the IME owns editing keys while composing
        }
        if (modifierKey(key)) {
            return true; // typing modifiers are the field's, not gameplay's
        }
        String before = model.text();
        boolean rtl = TextDirection.leftArrowMovesForward(currentLine());
        boolean handled = true;
        switch (key) {
            case MKeys.KEY_LEFT -> {
                if (rtl) {
                    model.moveRight(shift, ctrl);
                } else {
                    model.moveLeft(shift, ctrl);
                }
            }
            case MKeys.KEY_RIGHT -> {
                if (rtl) {
                    model.moveLeft(shift, ctrl);
                } else {
                    model.moveRight(shift, ctrl);
                }
            }
            case MKeys.KEY_HOME -> {
                if (ctrl) {
                    model.moveDocumentStart(shift);
                } else {
                    model.moveLineStart(shift);
                }
            }
            case MKeys.KEY_END -> {
                if (ctrl) {
                    model.moveDocumentEnd(shift);
                } else {
                    model.moveLineEnd(shift);
                }
            }
            case MKeys.KEY_UP, MKeys.KEY_DOWN -> {
                if (!multiline()) {
                    return false; // directional navigation leaves a single-line field
                }
                model.moveVertical(key == MKeys.KEY_UP ? -1 : 1, shift, metrics.get().measure());
            }
            case MKeys.KEY_BACKSPACE -> model.backspace(ctrl);
            case MKeys.KEY_DELETE -> model.delete(ctrl);
            case MKeys.KEY_ENTER, MKeys.KEY_KP_ENTER -> {
                if (multiline() && !ctrl) {
                    model.newline();
                } else {
                    commit();
                }
            }
            case MKeys.KEY_ESCAPE -> {
                if (model.text().equals(valueAtFocus)) {
                    return false; // nothing to revert: Escape is the screen's
                }
                model.setText(valueAtFocus);
            }
            case MKeys.KEY_TAB -> handled = false;
            default -> handled = ctrl ? shortcut(key, shift)
                : printable(key) && ((mods & MKeys.MOD_ALT) == 0 || altGr);
        }
        changed(before);
        blink();
        return handled;
    }

    private boolean shortcut(int key, boolean shift) {
        switch (key) {
            case MKeys.KEY_A -> model.selectAll();
            case MKeys.KEY_C -> {
                if (!password()) {
                    model.copy(SYSTEM);
                }
            }
            case MKeys.KEY_X -> {
                if (!password()) {
                    model.cut(SYSTEM);
                }
            }
            case MKeys.KEY_V -> model.paste(SYSTEM);
            case MKeys.KEY_Z -> {
                if (shift) {
                    model.redo();
                } else {
                    model.undo();
                }
            }
            case MKeys.KEY_Y -> model.redo();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** GLFW printable keys and the keypad's text keys: their characters arrive as text input. */
    private static boolean printable(int key) {
        return key >= MKeys.KEY_SPACE && key <= 96 || key == 161 || key == 162
            || key >= KEY_KP_0 && key <= KEY_KP_EQUAL && key != MKeys.KEY_KP_ENTER;
    }

    /** GLFW left/right Shift, Control, Alt, Super. */
    private static boolean modifierKey(int key) {
        return key >= KEY_LEFT_SHIFT && key <= KEY_RIGHT_SUPER;
    }

    private static final int KEY_KP_0 = 320;
    private static final int KEY_KP_EQUAL = 336;
    private static final int KEY_LEFT_SHIFT = 340;
    private static final int KEY_RIGHT_SUPER = 347;

    /** Inserts typed or pasted text through the field's rules. Always consumed while focused. */
    void text(String s) {
        String before = model.text();
        model.insert(s);
        changed(before);
        blink();
    }

    void composition(CompositionEvent e) {
        String before = model.text();
        switch (e.compositionPhase()) {
            case START -> model.beginComposition();
            case UPDATE -> model.updateComposition(e.text(), e.cursor());
            case COMMIT -> model.commitComposition(e.text());
            case CANCEL -> model.cancelComposition();
        }
        changed(before);
        blink();
    }

    /** Pointer press at device-pixel {@code (x, y)}: caret, word (double) or all (triple) selection. */
    void pointerDown(float x, float y, int clickCount, boolean extend, TextFieldGeometry g, TextLineMetrics m) {
        if (model.isComposing()) {
            model.cancelComposition();
        }
        int index = indexAt(x, y, g, m);
        if (clickCount >= 3) {
            model.selectAll();
        } else if (clickCount == 2) {
            model.selectWordAt(index);
        } else {
            model.setCaret(index, extend);
        }
        blink();
    }

    /** Pointer drag after a press: extends the selection. */
    void pointerDrag(float x, float y, TextFieldGeometry g, TextLineMetrics m) {
        model.setCaret(indexAt(x, y, g, m), true);
    }

    private int indexAt(float x, float y, TextFieldGeometry g, TextLineMetrics m) {
        String shown = displayText();
        float scroll = scrollX(g, m);
        if (!multiline()) {
            return valueIndex(m.measure().indexAt(shown, x - g.left() + scroll));
        }
        String[] lines = shown.split("\n", -1);
        int line = g.lineAt(y, lines.length);
        int offset = 0;
        for (int i = 0; i < line; i++) {
            offset += lines[i].length() + 1;
        }
        return valueIndex(offset + m.measure().indexAt(lines[line], x - g.left()));
    }

    /** Commits a valid value ({@code COMMIT}); an invalid one shows {@code :invalid}. */
    boolean commit() {
        if (!model.valid()) {
            element.setState(UiElement.INVALID, true);
            return false;
        }
        element.setState(UiElement.INVALID, false);
        String previous = committed;
        committed = model.text();
        valueAtFocus = committed;
        dispatcher.dispatch(new ChangeEvent(UiEventType.COMMIT, clock.getAsDouble(), previous, committed), element);
        return true;
    }

    private void changed(String before) {
        String now = model.text();
        if (now.equals(before)) {
            return;
        }
        element.setProp("text", UiValue.of(now));
        lastProp = element.text("text");
        element.setState(UiElement.INVALID, !model.valid());
        dispatcher.dispatch(new ChangeEvent(UiEventType.CHANGE, clock.getAsDouble(), before, now), element);
    }

    private String currentLine() {
        String t = model.text();
        int c = model.caret();
        return t.substring(TextBoundaries.lineStart(t, c), TextBoundaries.lineEnd(t, c));
    }

    private void blink() {
        blinkStart = clock.getAsDouble();
    }
}
