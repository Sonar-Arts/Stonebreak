package com.openmason.engine.ui.l10n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiLocalizerTest {

    private static final Locale DE = Locale.GERMAN;
    private static final Locale DE_AT = Locale.forLanguageTag("de-AT");

    private static UiLocalizer localizer() {
        UiLocalizer l = UiLocalizer.english();
        l.addCatalog(MessageCatalog.of(Locale.ENGLISH, Map.of(
            "pause.resume", "Resume",
            "pause.quit", "Quit",
            "items", "{n, plural, one {# item} other {# items}}")));
        l.addCatalog(MessageCatalog.of(DE, Map.of(
            "pause.resume", "Fortsetzen",
            "items", "{n, plural, one {# Gegenstand} other {# Gegenstände}}")));
        l.addCatalog(MessageCatalog.of(DE_AT, Map.of("pause.resume", "Weiter")));
        return l;
    }

    @Test
    void fallbackChainRunsFromRegionToLanguageToFallback() {
        UiLocalizer l = localizer();
        l.setLocale(DE_AT);
        assertEquals(List.of(DE_AT, DE, Locale.ENGLISH), l.chain());
        assertEquals("Weiter", l.text("pause.resume", Map.of(), null));
        assertEquals("1.234 Gegenstände", l.text("items", Map.of("n", 1234), null), "from de");
        assertEquals("Quit", l.text("pause.quit", Map.of(), null), "from the English fallback");
        l.setLocale(Locale.forLanguageTag("de-CH"));
        assertEquals("Fortsetzen", l.text("pause.resume", Map.of(), null));
    }

    @Test
    void pluralsUseTheRulesOfTheCatalogThatSuppliedThePattern() {
        UiLocalizer l = UiLocalizer.english();
        l.addCatalog(MessageCatalog.of(Locale.ENGLISH, Map.of(
            "files", "{n, plural, one {# file} few {# FEW} other {# files}}")));
        l.setLocale(Locale.forLanguageTag("pl"));
        assertEquals("2 files", l.text("files", Map.of("n", 2), null), "English rules: no 'few'");
        l.addCatalog(MessageCatalog.of(Locale.forLanguageTag("pl"), Map.of(
            "files", "{n, plural, one {# plik} few {# pliki} many {# plików} other {# pliku}}")));
        assertEquals("2 pliki", l.text("files", Map.of("n", 2), null));
        assertEquals("5 plików", l.text("files", Map.of("n", 5), null));
    }

    @Test
    void revisionBumpsOnlyOnChanges() {
        UiLocalizer l = localizer();
        int r = l.revision();
        l.setLocale(Locale.ENGLISH);
        assertEquals(r, l.revision(), "same locale");
        l.setLocale(DE);
        assertEquals(r + 1, l.revision());
        l.addCatalog(MessageCatalog.of(DE, Map.of("x", "y")));
        assertEquals(r + 2, l.revision());
        l.setPseudo(true);
        l.setPseudo(true);
        assertEquals(r + 3, l.revision());
        l.text("pause.resume", Map.of(), null);
        assertEquals(r + 3, l.revision(), "lookups never bump");
    }

    @Test
    void addingACatalogReplacesTheSameLocale() {
        UiLocalizer l = localizer();
        l.addCatalog(MessageCatalog.of(Locale.ENGLISH, Map.of("pause.resume", "Continue")));
        assertEquals("Continue", l.text("pause.resume", Map.of(), null));
        assertFalse(l.has("pause.quit"), "the old English catalog is gone");
    }

    @Test
    void missingKeysFallBackAndAreRecorded() {
        UiLocalizer l = localizer();
        assertEquals(Optional.empty(), l.resolve("nope", Map.of()));
        assertEquals("Fallback", l.text("also.nope", Map.of(), "Fallback"));
        assertEquals("third.nope", l.text("third.nope", Map.of(), null));
        assertEquals(List.of("nope", "also.nope", "third.nope"), List.copyOf(l.missingKeys()));
        assertTrue(l.has("pause.quit"));
        assertFalse(l.has("nope"));
    }

    @Test
    void rightToLeftFollowsTheLanguage() {
        UiLocalizer l = UiLocalizer.english();
        assertFalse(l.isRightToLeft());
        l.setLocale(Locale.forLanguageTag("ar-EG"));
        assertTrue(l.isRightToLeft());
        l.setLocale(Locale.forLanguageTag("he"));
        assertTrue(l.isRightToLeft());
        l.setLocale(Locale.forLanguageTag("fa"));
        assertTrue(l.isRightToLeft());
        l.setLocale(Locale.JAPANESE);
        assertFalse(l.isRightToLeft());
    }

    @Test
    void pseudoModeTransformsResolvedAndFallbackText() {
        UiLocalizer l = localizer();
        l.setPseudo(true);
        String resumed = l.text("pause.resume", Map.of(), null);
        assertEquals(PseudoLocalizer.transform("Resume"), resumed);
        assertNotEquals("Resume", resumed);
        assertEquals(PseudoLocalizer.transform("Fallback"), l.text("missing", Map.of(), "Fallback"));
        assertEquals(Optional.of(PseudoLocalizer.transform("2 items")), l.resolve("items", Map.of("n", 2)));
    }

    @Test
    void catalogsLoadPropertiesAndFailOnBrokenPatterns() throws IOException {
        MessageCatalog c = MessageCatalog.load(DE, new StringReader("""
            # comment
            pause.resume = Fortsetzen
            items = {n, plural, one {# Gegenstand} other {# Gegenstände}}
            """));
        assertEquals(DE, c.locale());
        assertEquals(List.of("items", "pause.resume"), List.copyOf(c.keys()));
        assertEquals("Fortsetzen", c.pattern("pause.resume"));
        assertTrue(c.contains("items"));
        assertEquals("3 Gegenstände", c.compiled("items").format(Map.of("n", 3), DE));
        MessageFormatException e = assertThrows(MessageFormatException.class,
            () -> MessageCatalog.load(DE, new StringReader("bad = {n, plural, one {x}}")));
        assertTrue(e.getMessage().contains("'bad'"), e.getMessage());
    }
}
