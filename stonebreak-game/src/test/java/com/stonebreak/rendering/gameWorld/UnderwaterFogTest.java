package com.stonebreak.rendering.gameWorld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The underwater visibility ladder. These numbers are only ever seen as fog on
 * a screen, so nothing else pins them: a sign flip here would read as "the fog
 * got worse near the surface" and survive a whole play session unnoticed.
 */
class UnderwaterFogTest {

    private static final float SEA = WorldConfiguration.SEA_LEVEL;
    private static final float EPS = 1e-4f;

    @Test
    void visibilityShrinksWithDepth() {
        float surfaceEnd = UnderwaterFog.end(SEA);
        float midEnd = UnderwaterFog.end(SEA - 12);
        float deepEnd = UnderwaterFog.end(SEA - 24);

        assertEquals(64f, surfaceEnd, EPS, "just under the surface you see a long way");
        assertEquals(20f, deepEnd, EPS, "deep down it is the old murky reach");
        assertTrue(midEnd < surfaceEnd && midEnd > deepEnd, "and it closes in between: " + midEnd);

        assertEquals(12f, UnderwaterFog.start(SEA), EPS);
        assertEquals(4f, UnderwaterFog.start(SEA - 24), EPS);
        assertTrue(UnderwaterFog.start(SEA) > UnderwaterFog.start(SEA - 12),
                "the near edge of the band closes in with depth too");
    }

    @Test
    void theLadderIsClampedAtBothEnds() {
        // Above sea level (a river or lake perched high) reads as the surface,
        // never clearer than it; an abyss reads as the deep, never murkier.
        assertEquals(UnderwaterFog.end(SEA), UnderwaterFog.end(SEA + 40), EPS);
        assertEquals(UnderwaterFog.end(SEA - 24), UnderwaterFog.end(SEA - 300), EPS);
    }

    @Test
    void exponentialDensityTracksTheLinearBand() {
        // Entities and drops fog exponentially, terrain and water linearly. The
        // two have to reach the same distance or a mob hangs in clear water the
        // seabed has already vanished from — 0.15 is the density the entity
        // passes carried when the deep band was the only band.
        assertEquals(0.15f, UnderwaterFog.density(SEA - 24), 1e-3f);
        assertTrue(UnderwaterFog.density(SEA) < UnderwaterFog.density(SEA - 24),
                "thinner fog near the surface");
    }
}
