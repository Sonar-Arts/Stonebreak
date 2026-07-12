package com.stonebreak.audio;

import com.openmason.engine.format.sound.SoundDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic coverage for {@link ResolvedSoundEvents}: event lookup, pitch
 * selection (per-def variation switch), and content-derived sound keys.
 * Registration against a live {@code SoundSystem} needs OpenAL and is
 * exercised manually in-game.
 */
class ResolvedSoundEventsTest {

    private static ResolvedSoundEvents.Entry entry(float pitchMin, float pitchMax, boolean variation) {
        return new ResolvedSoundEvents.Entry("res:/sounds/x.wav", 0.5f, pitchMin, pitchMax, variation);
    }

    @Test
    void pickReturnsNullForUnboundEventsAndEntriesForBoundOnes() {
        ResolvedSoundEvents events = new ResolvedSoundEvents(Map.of(
                "step", List.of(entry(0.9f, 1.1f, true))));

        assertTrue(events.has("step"));
        assertFalse(events.has("break"));
        assertNotNull(events.pick("step"));
        assertNull(events.pick("break"));
        assertNull(events.pick(null));
        assertNull(ResolvedSoundEvents.EMPTY.pick("step"));
    }

    @Test
    void pickDrawsFromAllEntriesOfAnEvent() {
        ResolvedSoundEvents.Entry a = new ResolvedSoundEvents.Entry("a", 1f, 1f, 1f, true);
        ResolvedSoundEvents.Entry b = new ResolvedSoundEvents.Entry("b", 1f, 1f, 1f, true);
        ResolvedSoundEvents events = new ResolvedSoundEvents(Map.of("break", List.of(a, b)));

        boolean sawA = false, sawB = false;
        for (int i = 0; i < 200 && !(sawA && sawB); i++) {
            ResolvedSoundEvents.Entry picked = events.pick("break");
            sawA |= picked == a;
            sawB |= picked == b;
        }
        assertTrue(sawA && sawB, "random pick should eventually hit every entry");
    }

    @Test
    void pitchHonorsPerDefVariationSwitch() {
        // Variation off: always the natural pitch, range ignored.
        ResolvedSoundEvents.Entry fixed = entry(0.5f, 2.0f, false);
        for (int i = 0; i < 50; i++) {
            assertEquals(1.0f, fixed.pitch(), 1e-6);
        }

        // Variation on: every draw inside the authored range.
        ResolvedSoundEvents.Entry varied = entry(0.9f, 1.1f, true);
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < 500; i++) {
            float p = varied.pitch();
            assertTrue(p >= 0.9f && p <= 1.1f, "pitch outside authored range: " + p);
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        assertTrue(max - min > 0.01f, "variation on should actually vary the pitch");

        // Degenerate range with variation on: fixed at the range value.
        assertEquals(1.05f, entry(1.05f, 1.05f, true).pitch(), 1e-6);
    }

    @Test
    void soundKeysAreContentDerived() {
        SoundDef resource = new SoundDef("step", null, null, "/sounds/GrassWalk.wav", 1f, 0.9f, 1.1f, true);
        assertEquals("res:/sounds/GrassWalk.wav", ResolvedSoundEvents.keyFor(resource, "a:b"),
                "resource refs key by path so shared samples dedupe across assets");

        SoundDef embedded = new SoundDef("break", "sounds/break_0.wav", "abc123", null, 1f, 1f, 1f, true);
        assertEquals("snd:abc123", ResolvedSoundEvents.keyFor(embedded, "a:b"),
                "embedded samples key by checksum so identical audio dedupes");

        SoundDef stub = new SoundDef("break", "sounds/break_0.wav", "", null, 1f, 1f, 1f, true);
        assertEquals("snd:a:b/sounds/break_0.wav", ResolvedSoundEvents.keyFor(stub, "a:b"),
                "blank tool-stub checksums fall back to an owner-scoped key");
    }
}
