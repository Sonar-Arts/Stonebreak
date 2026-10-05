package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A missing input feature blocks a screen's migration instead of degrading it (#288). */
class UiInputGateTest {

    /** What the game window has today plus what #288 adds (see ui-input.md §Capabilities). */
    private static final Set<InputCapability> GAME = EnumSet.of(InputCapability.POINTER, InputCapability.WHEEL,
        InputCapability.KEYBOARD, InputCapability.TEXT_INPUT, InputCapability.TEXT_INPUT_SUPPLEMENTARY,
        InputCapability.CLIPBOARD, InputCapability.GAMEPAD);

    private static OmuiArchive chatLike() {
        return screen("t:ui/gate", box("root").kids(label("title", "Chat"), node("line", "TextField")));
    }

    @Test
    void aMenuWithoutTextNeedsNothingSpecial() {
        OmuiArchive menu = screen("t:ui/menu", box("root").kids(node("b", "Button").kids(label("l", "Resume"))));
        assertEquals(List.of(), UiInputGate.check(menu, Locale.ENGLISH, GAME));
        assertEquals(List.of(), UiInputGate.check(menu, Locale.forLanguageTag("ja"), GAME));
    }

    @Test
    void textFieldsPassInEnglishOnTheGameHost() {
        assertEquals(List.of(), UiInputGate.check(chatLike(), Locale.ENGLISH, GAME));
    }

    @Test
    void japaneseTextEntryIsBlockedUntilThereIsAnImeSource() {
        List<UiInputGate.Block> blocks = UiInputGate.check(chatLike(), Locale.JAPANESE, GAME);
        assertEquals(1, blocks.size());
        assertEquals(InputCapability.IME_COMPOSITION, blocks.getFirst().capability());
        assertEquals("line", blocks.getFirst().nodeId());
    }

    @Test
    void rightToLeftLocalesAreBlockedUntilShapingExists() {
        List<InputCapability> caps = UiInputGate.check(chatLike(), Locale.forLanguageTag("ar"), GAME).stream()
            .map(UiInputGate.Block::capability).toList();
        assertEquals(List.of(InputCapability.RTL_TEXT, InputCapability.COMPLEX_SHAPING), caps);
    }

    @Test
    void anAsciiOnlyFieldDoesNotNeedSupplementaryInput() {
        OmuiArchive doc = screen("t:ui/ascii", box("root").kids(node("f", "TextField").prop("inputFilter", "ascii")));
        Set<InputCapability> bmpOnly = EnumSet.copyOf(GAME);
        bmpOnly.remove(InputCapability.TEXT_INPUT_SUPPLEMENTARY);
        assertEquals(List.of(), UiInputGate.check(doc, Locale.ENGLISH, bmpOnly));
        assertTrue(UiInputGate.check(chatLike(), Locale.ENGLISH, bmpOnly).stream()
            .anyMatch(b -> b.capability() == InputCapability.TEXT_INPUT_SUPPLEMENTARY),
            "an unrestricted field would silently drop emoji on a BMP-only host");
    }
}
