package com.stonebreak.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #335: names are limited to a character set and a length. */
class PlayerNamesTest {

    @Test
    void lettersDigitsSymbolsAndSingleSpacesUpToTwentyFour() {
        assertTrue(PlayerNames.isValid("Player"));
        assertTrue(PlayerNames.isValid("x"));
        assertTrue(PlayerNames.isValid("Bob_42"));
        assertTrue(PlayerNames.isValid("Mr.Bob-2"), "the old sanitizer kept . and -");
        assertTrue(PlayerNames.isValid("a".repeat(PlayerNames.MAX_LENGTH)));
        assertFalse(PlayerNames.isValid("a".repeat(PlayerNames.MAX_LENGTH + 1)));
        assertFalse(PlayerNames.isValid(""));
        assertFalse(PlayerNames.isValid(null));
        assertFalse(PlayerNames.isValid("[SERVER]"));
        assertTrue(PlayerNames.isValid("Bob Smith"), "names from before the rule keep working");
        assertTrue(PlayerNames.isValid("a".repeat(24)), "the old join screen allowed 24");
        assertFalse(PlayerNames.isValid(" Bob"));
        assertFalse(PlayerNames.isValid("Bob "));
        assertFalse(PlayerNames.isValid("Bob  Smith"), "names never differ only in spacing");
        assertFalse(PlayerNames.isValid("Bob\n"));
        assertFalse(PlayerNames.isValid("Bøb"));
    }

    @Test
    void theHostsNameIsCoercedNeverRefused() {
        assertEquals("Bob Smith", PlayerNames.forLocalHost("Bob Smith"));
        assertEquals("Bob Smith", PlayerNames.forLocalHost("  Bob   [Smith] "));
        assertEquals("SERVER", PlayerNames.forLocalHost("[SERVER]"));
        assertEquals("abcdefghijklmnopqrstuvwx", PlayerNames.forLocalHost("abcdefghijklmnopqrstuvwxyz"));
        assertEquals(PlayerNames.DEFAULT, PlayerNames.forLocalHost("!!!"));
        assertEquals(PlayerNames.DEFAULT, PlayerNames.forLocalHost(null));
        assertTrue(PlayerNames.isValid(PlayerNames.forLocalHost("  ~~ weird name ~~ ")));
    }
}
