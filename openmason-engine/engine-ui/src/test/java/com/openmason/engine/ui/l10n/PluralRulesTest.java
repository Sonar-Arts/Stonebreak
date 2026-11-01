package com.openmason.engine.ui.l10n;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;

import static com.openmason.engine.ui.l10n.PluralCategory.FEW;
import static com.openmason.engine.ui.l10n.PluralCategory.MANY;
import static com.openmason.engine.ui.l10n.PluralCategory.ONE;
import static com.openmason.engine.ui.l10n.PluralCategory.OTHER;
import static com.openmason.engine.ui.l10n.PluralCategory.TWO;
import static com.openmason.engine.ui.l10n.PluralCategory.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluralRulesTest {

    private static PluralRules rules(String tag) {
        return PluralRules.forLocale(Locale.forLanguageTag(tag));
    }

    private static PluralCategory sel(String tag, String number) {
        return rules(tag).select(new BigDecimal(number));
    }

    @Test
    void englishDistinguishesOneFromVisibleFractions() {
        PluralRules en = rules("en");
        assertEquals(ONE, en.select(1));
        assertEquals(OTHER, en.select(2));
        assertEquals(OTHER, en.select(0));
        assertEquals(OTHER, sel("en", "1.0"), "1.0 items: v = 1");
        assertEquals(ONE, en.select(1.0), "a double carries no scale");
        assertEquals(ONE, en.select(-1), "negatives use the absolute value");
        assertEquals(EnumSet.of(ONE, OTHER), en.categories());
    }

    @Test
    void frenchCountsZeroAndFractionsBelowTwoAsOne() {
        assertEquals(ONE, sel("fr", "0"));
        assertEquals(ONE, sel("fr", "1"));
        assertEquals(ONE, sel("fr", "1.5"));
        assertEquals(OTHER, sel("fr", "2"));
    }

    @Test
    void russianUsesTheLastDigits() {
        assertEquals(ONE, sel("ru", "1"));
        assertEquals(FEW, sel("ru", "2"));
        assertEquals(MANY, sel("ru", "5"));
        assertEquals(MANY, sel("ru", "11"));
        assertEquals(ONE, sel("ru", "21"));
        assertEquals(FEW, sel("ru", "22"));
        assertEquals(MANY, sel("ru", "25"));
        assertEquals(MANY, sel("ru", "111"));
        assertEquals(OTHER, sel("ru", "1.5"));
        assertEquals(EnumSet.of(ONE, FEW, MANY, OTHER), rules("ru").categories());
        assertEquals(FEW, sel("uk", "3"));
    }

    @Test
    void polishOneIsOnlyExactlyOne() {
        assertEquals(ONE, sel("pl", "1"));
        assertEquals(FEW, sel("pl", "2"));
        assertEquals(MANY, sel("pl", "5"));
        assertEquals(MANY, sel("pl", "12"));
        assertEquals(FEW, sel("pl", "22"));
        assertEquals(MANY, sel("pl", "21"));
        assertEquals(OTHER, sel("pl", "1.5"));
    }

    @Test
    void arabicHasAllSixCategories() {
        assertEquals(ZERO, sel("ar", "0"));
        assertEquals(ONE, sel("ar", "1"));
        assertEquals(TWO, sel("ar", "2"));
        assertEquals(FEW, sel("ar", "3"));
        assertEquals(MANY, sel("ar", "11"));
        assertEquals(OTHER, sel("ar", "100"));
        assertEquals(OTHER, sel("ar", "102"));
        assertEquals(FEW, sel("ar", "103"));
        assertEquals(EnumSet.allOf(PluralCategory.class), rules("ar").categories());
    }

    @Test
    void japaneseAndUnknownLanguagesAreAlwaysOther() {
        for (String n : new String[] {"0", "1", "2", "1.5", "100"}) {
            assertEquals(OTHER, sel("ja", n));
            assertEquals(OTHER, sel("zh", n));
            assertEquals(OTHER, sel("xx", n));
        }
        assertEquals(EnumSet.of(OTHER), rules("ja").categories());
    }

    @Test
    void czechUsesManyForFractions() {
        assertEquals(ONE, sel("cs", "1"));
        assertEquals(FEW, sel("cs", "3"));
        assertEquals(OTHER, sel("cs", "5"));
        assertEquals(MANY, sel("cs", "1.5"));
        assertEquals(MANY, sel("sk", "0.5"));
    }

    @Test
    void portugueseDiffersByRegion() {
        assertEquals(ONE, sel("pt", "0"));
        assertEquals(ONE, sel("pt-BR", "1.5"));
        assertEquals(OTHER, sel("pt-PT", "0"));
        assertEquals(ONE, sel("pt-PT", "1"));
        assertEquals(OTHER, sel("pt-PT", "1.5"));
        assertEquals("pt-PT", rules("pt-PT").language());
    }

    @Test
    void otherLanguagesFollowCldr() {
        assertEquals(ONE, sel("he", "1"));
        assertEquals(TWO, sel("he", "2"));
        assertEquals(ONE, sel("he", "0.5"));
        assertEquals(ONE, sel("da", "0.5"));
        assertEquals(FEW, sel("ro", "0"));
        assertEquals(FEW, sel("ro", "19"));
        assertEquals(OTHER, sel("ro", "20"));
        assertEquals(ONE, sel("lt", "21"));
        assertEquals(FEW, sel("lt", "9"));
        assertEquals(OTHER, sel("lt", "11"));
        assertEquals(MANY, sel("lt", "1.5"));
        assertEquals(ZERO, sel("lv", "10"));
        assertEquals(ONE, sel("lv", "21"));
        assertEquals(OTHER, sel("lv", "2"));
        assertEquals(ONE, sel("be", "21"));
        assertEquals(OTHER, sel("be", "1.5"));
    }

    @Test
    void categoryWireNames() {
        assertEquals("few", FEW.wire());
        assertEquals(MANY, PluralCategory.fromWire("many"));
        assertNull(PluralCategory.fromWire("lots"));
    }
}
