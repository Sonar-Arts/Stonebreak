package com.stonebreak.core.cheats;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Issue #318: in-world /cheats toggles route to the world authority, which may refuse. */
class CheatStateTest {

    @Test
    void acceptedToggleIsRequestedFromTheAuthorityAndAppliedLocally() {
        List<Boolean> requests = new ArrayList<>();
        CheatState cheats = new CheatState(enabled -> requests.add(enabled));

        assertTrue(cheats.applyToCurrentWorld(true));
        assertTrue(cheats.isEnabled());
        assertTrue(cheats.applyToCurrentWorld(false));
        assertFalse(cheats.isEnabled());
        assertEquals(List.of(true, false), requests);
    }

    @Test
    void refusedToggleLeavesTheFlagUnchanged() {
        CheatState cheats = new CheatState(enabled -> false);

        assertFalse(cheats.applyToCurrentWorld(true));
        assertFalse(cheats.isEnabled());
    }

    @Test
    void authoritativeStateIsAdoptedWithoutARequest() {
        CheatState cheats = new CheatState(enabled -> fail("setEnabled must not contact the authority"));

        cheats.setEnabled(true); // e.g. CheatsStateS2C restoring a loaded world's flag
        assertTrue(cheats.isEnabled());
        cheats.setEnabled(false); // e.g. the main-menu reset
        assertFalse(cheats.isEnabled());
    }
}
