package com.stonebreak.audio.music;

import com.openmason.engine.audio.MusicDirector.Track;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cue scheduling: once per time window, on the right ground, with throttled place checks. */
class MusicCuesTest {

    private static final float STEP = MusicCues.CHECK_INTERVAL;
    private static final boolean DAY = false;
    private static final boolean NIGHT = true;

    private static final MusicCue SHADE = new MusicCue("the_shade",
            new Track("music_the_shade", "/songs/biome music/The Shade.ogg"),
            MusicCue.Time.NIGHT, Set.of(BiomeType.SHADE), true);

    private static Supplier<MusicPlace> at(BiomeType biome, boolean surface) {
        return () -> new MusicPlace() {
            @Override public BiomeType biome() { return biome; }
            @Override public boolean onSurface() { return surface; }
        };
    }

    private static final Supplier<MusicPlace> IN_SHADE = at(BiomeType.SHADE, true);
    private static final Supplier<MusicPlace> ELSEWHERE = at(BiomeType.PLAINS, true);

    @Test
    void neverByDay() {
        MusicCues cues = new MusicCues(List.of(SHADE));
        AtomicInteger asked = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            assertNull(cues.update(DAY, STEP, () -> { asked.incrementAndGet(); return IN_SHADE.get(); }));
        }
        assertEquals(0, asked.get(), "no place query by day");
    }

    @Test
    void startsAsNightFallsOverAPlayerAlreadyThere() {
        MusicCues cues = new MusicCues(List.of(SHADE));
        assertNull(cues.update(DAY, STEP, IN_SHADE));
        assertSame(SHADE, cues.update(NIGHT, STEP / 100f, IN_SHADE), "checked as soon as night falls");
    }

    @Test
    void startsWhenEnteringAfterDark() {
        MusicCues cues = new MusicCues(List.of(SHADE));
        assertNull(cues.update(NIGHT, STEP, ELSEWHERE), "outside the Shade");
        assertNull(cues.update(NIGHT, STEP, at(BiomeType.SHADE, false)), "in a Shade cave");
        assertSame(SHADE, cues.update(NIGHT, STEP, IN_SHADE), "walked in");
    }

    @Test
    void onlyOnceANight() {
        MusicCues cues = new MusicCues(List.of(SHADE));
        assertSame(SHADE, cues.update(NIGHT, STEP, IN_SHADE));
        assertNull(cues.update(NIGHT, STEP, ELSEWHERE), "left");
        for (int i = 0; i < 20; i++) {
            assertNull(cues.update(NIGHT, STEP, IN_SHADE), "came back the same night");
        }
        assertNull(cues.update(DAY, STEP, IN_SHADE), "dawn");
        assertSame(SHADE, cues.update(NIGHT, STEP, IN_SHADE), "next night");
    }

    @Test
    void placeQueriesAreThrottled() {
        MusicCues cues = new MusicCues(List.of(SHADE));
        AtomicInteger asked = new AtomicInteger();
        float frame = STEP / 10f;
        for (int i = 0; i < 100; i++) { // ten check intervals of frames, never in the Shade
            cues.update(NIGHT, frame, () -> { asked.incrementAndGet(); return ELSEWHERE.get(); });
        }
        assertTrue(asked.get() <= 11, "asked " + asked.get() + " times");
    }

    @Test
    void resetArmsItAgain() {
        MusicCues cues = new MusicCues(List.of(SHADE));
        assertSame(SHADE, cues.update(NIGHT, STEP, IN_SHADE));
        cues.reset();
        assertSame(SHADE, cues.update(NIGHT, STEP, IN_SHADE), "a fresh world at night");
    }

    @Test
    void oneCheckAsksThePlaceOnceForEveryCue() {
        MusicCue desert = new MusicCue("desert", new Track("music_desert", "/songs/d.ogg"),
                MusicCue.Time.ANY, Set.of(BiomeType.DESERT), null);
        MusicCues cues = new MusicCues(List.of(SHADE, desert));
        AtomicInteger asked = new AtomicInteger();
        cues.update(NIGHT, STEP, () -> { asked.incrementAndGet(); return ELSEWHERE.get(); });
        assertEquals(1, asked.get());
    }

    @Test
    void anAnyTimeCuePlaysOncePerWorld() {
        MusicCue desert = new MusicCue("desert", new Track("music_desert", "/songs/d.ogg"),
                MusicCue.Time.ANY, Set.of(BiomeType.DESERT), null);
        MusicCues cues = new MusicCues(List.of(desert));
        Supplier<MusicPlace> inDesert = at(BiomeType.DESERT, false);
        assertSame(desert, cues.update(DAY, STEP, inDesert), "surface not required");
        assertNull(cues.update(NIGHT, STEP, inDesert));
        assertNull(cues.update(DAY, STEP, inDesert));
        cues.reset();
        assertSame(desert, cues.update(NIGHT, STEP, inDesert));
    }

    @Test
    void aCueWithoutPlaceConditionsNeverAsksThePlace() {
        MusicCue dusk = new MusicCue("night", new Track("music_night", "/songs/n.ogg"),
                MusicCue.Time.NIGHT, Set.of(), null);
        MusicCues cues = new MusicCues(List.of(dusk));
        assertSame(dusk, cues.update(NIGHT, STEP, () -> { throw new AssertionError("asked"); }));
    }
}
