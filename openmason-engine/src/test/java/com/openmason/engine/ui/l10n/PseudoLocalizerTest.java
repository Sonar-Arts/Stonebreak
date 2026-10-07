package com.openmason.engine.ui.l10n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PseudoLocalizerTest {

    @Test
    void accentsLettersAndKeepsEverythingElse() {
        String out = PseudoLocalizer.transform("Resume 12 ✓!");
        assertTrue(out.startsWith("[Ŕéšûɱé "), out);
        assertTrue(out.contains(" 12 ✓!"), "digits, spaces and non-letters survive: " + out);
        assertTrue(out.endsWith("]"));
        for (char c : out.toCharArray()) {
            assertTrue(!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z'), "no plain ASCII letter left: " + out);
        }
    }

    @Test
    void growsByAboutFortyPercent() {
        String text = "Quit to main menu"; // 17
        String out = PseudoLocalizer.transform(text);
        assertEquals((int) Math.ceil(17 * 0.4) + 17, out.length());
        assertEquals("[]", PseudoLocalizer.transform(""));
        assertEquals("[ö]", PseudoLocalizer.transform("o"), "short text still gets its brackets");
        assertTrue(PseudoLocalizer.transform("OK").length() >= 4);
    }

    @Test
    void isDeterministic() {
        assertEquals(PseudoLocalizer.transform("Settings"), PseudoLocalizer.transform("Settings"));
        assertEquals("[ÀƁÇàƀç~]", PseudoLocalizer.transform("ABCabc"));
        assertNull(PseudoLocalizer.transform(null));
    }
}
