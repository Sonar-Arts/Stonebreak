package com.openmason.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_LOOPING;
import static org.lwjgl.openal.AL10.AL_MAX_DISTANCE;
import static org.lwjgl.openal.AL10.AL_PITCH;
import static org.lwjgl.openal.AL10.AL_REFERENCE_DISTANCE;
import static org.lwjgl.openal.AL10.AL_ROLLOFF_FACTOR;
import static org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE;
import static org.lwjgl.openal.AL10.alGetSourcef;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alIsBuffer;
import static org.lwjgl.openal.AL10.alIsSource;
import static org.lwjgl.openal.AL10.alSourcePlay;

/** Per-sound source pools: creation, 3D defaults, round-robin and release, on a loopback context. */
@Tag("integration")
class SoundBufferTest {

    private static final int POOL = 3;

    private LoopbackAl al;
    private SoundBuffer pool;

    @BeforeEach
    void open() {
        al = LoopbackAl.open();
        pool = new SoundBuffer(POOL);
    }

    @AfterEach
    void close() {
        pool.cleanup();
        al.close();
    }

    private static int buffer() {
        AudioLoader.LoadResult r = new AudioLoader().loadSound("s",
                new ByteArrayInputStream(wav(pcmLe(44100, 16, 1), constant16(32, (short) 100))));
        assertTrue(r.success, r.errorMessage);
        return r.buffer;
    }

    @Test
    void unknownSoundHasNoSourcesAndNoIndex() {
        assertFalse(pool.isSoundLoaded("nope"));
        assertNull(pool.getSources("nope"));
        assertEquals(-1, pool.getNextSourceIndex("nope"));
    }

    @Test
    void addSoundCreatesAPoolOfDistinctSourcesBoundToTheBuffer() {
        int buf = buffer();
        assertTrue(pool.addSound("step", buf));
        assertTrue(pool.isSoundLoaded("step"));

        Integer[] sources = pool.getSources("step");
        assertEquals(POOL, sources.length);
        assertEquals(POOL, new HashSet<>(Arrays.asList(sources)).size());
        for (int s : sources) {
            assertTrue(alIsSource(s));
            assertEquals(buf, alGetSourcei(s, AL_BUFFER));
        }
    }

    @Test
    void newSourcesAreConfiguredForWorldSpace3dAudio() {
        pool.addSound("step", buffer());
        for (int s : pool.getSources("step")) {
            assertEquals(0.5f, alGetSourcef(s, AL_GAIN), 1e-6f);
            assertEquals(1.0f, alGetSourcef(s, AL_PITCH), 1e-6f);
            assertEquals(1.0f, alGetSourcef(s, AL_REFERENCE_DISTANCE), 1e-6f);
            assertEquals(50.0f, alGetSourcef(s, AL_MAX_DISTANCE), 1e-6f);
            assertEquals(1.0f, alGetSourcef(s, AL_ROLLOFF_FACTOR), 1e-6f);
            assertEquals(AL_FALSE, alGetSourcei(s, AL_SOURCE_RELATIVE));
            assertEquals(AL_FALSE, alGetSourcei(s, AL_LOOPING));
        }
    }

    @Test
    void sourceIndexRoundRobinsThroughThePool() {
        pool.addSound("step", buffer());
        int[] got = new int[7];
        for (int i = 0; i < got.length; i++) got[i] = pool.getNextSourceIndex("step");
        assertArrayEquals(new int[]{0, 1, 2, 0, 1, 2, 0}, got);
    }

    @Test
    void eachSoundKeepsItsOwnRoundRobinCursor() {
        pool.addSound("a", buffer());
        pool.addSound("b", buffer());
        assertEquals(0, pool.getNextSourceIndex("a"));
        assertEquals(1, pool.getNextSourceIndex("a"));
        assertEquals(0, pool.getNextSourceIndex("b"));
        assertEquals(2, pool.getNextSourceIndex("a"));
    }

    @Test
    void snapshotsAreCopiesNotLiveViews() {
        pool.addSound("a", buffer());
        Map<String, Integer> buffers = pool.getSoundBuffers();
        Map<String, Integer[]> sources = pool.getAllSources();
        buffers.clear();
        sources.clear();
        assertTrue(pool.isSoundLoaded("a"));
        assertNotNull(pool.getSources("a"));
    }

    @Test
    void cleanupDeletesEverySourceAndBufferAndForgetsTheSounds() {
        int buf = buffer();
        pool.addSound("a", buf);
        Integer[] sources = pool.getSources("a");
        alSourcePlay(sources[0]); // deleting a playing source must still work

        pool.cleanup();

        for (int s : sources) assertFalse(alIsSource(s));
        assertFalse(alIsBuffer(buf));
        assertFalse(pool.isSoundLoaded("a"));
        assertEquals(-1, pool.getNextSourceIndex("a"));
        assertTrue(pool.getSoundBuffers().isEmpty());
    }

    /**
     * Same stale-error trap as the loader: addSound checked alGetError after configuring each
     * source without clearing it first, so an earlier unrelated AL error made it report failure,
     * leave the sound half-registered and leak the source it had just generated.
     */
    @Test
    @Tag("regression")
    void staleErrorFromAnUnrelatedCallDoesNotFailAddSound() {
        int buf = buffer();
        alSourcePlay(987654); // AL_INVALID_NAME, left unread
        assertTrue(pool.addSound("step", buf));
        assertTrue(pool.isSoundLoaded("step"));
    }

    /**
     * Documents current behaviour (reported, not fixed): re-adding a name replaces the pool
     * without deleting the previous sources, so they outlive {@link SoundBuffer#cleanup()}.
     * Callers (ResolvedSoundEvents, the tool's SoundPreviewService) guard with isSoundLoaded.
     */
    @Test
    void readdingANameOrphansThePreviousSourcesCurrentBehaviour() {
        pool.addSound("a", buffer());
        Integer[] first = pool.getSources("a");
        pool.addSound("a", buffer());
        pool.cleanup();
        for (int s : first) assertTrue(alIsSource(s), "old sources are leaked");
    }
}
