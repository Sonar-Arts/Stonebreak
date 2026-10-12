package com.openmason.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_INITIAL;
import static org.lwjgl.openal.AL10.AL_PITCH;
import static org.lwjgl.openal.AL10.AL_PLAYING;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE;
import static org.lwjgl.openal.AL10.AL_SOURCE_STATE;
import static org.lwjgl.openal.AL10.AL_TRUE;
import static org.lwjgl.openal.AL10.AL_VELOCITY;
import static org.lwjgl.openal.AL10.alGetSource3f;
import static org.lwjgl.openal.AL10.alGetSourcef;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alSource3f;

/**
 * What each play call does to the pooled source it picks: gain = volume x master x ducking,
 * 2D calls pin the source to the listener, 3D calls place it in the world, pitch handling.
 */
@Tag("integration")
class SoundPlayerTest {

    private static final float EPS = 1e-5f;

    private LoopbackAl al;
    private SoundBuffer pool;
    private VolumeController volume;
    private SoundPlayer player;
    private Integer[] sources;

    @BeforeEach
    void open() {
        al = LoopbackAl.open();
        pool = new SoundBuffer(3);
        volume = new VolumeController();
        player = new SoundPlayer(pool, volume);
        // Long enough (1 s) that nothing finishes: the loopback mixer only advances on render().
        AudioLoader.LoadResult r = new AudioLoader().loadSound("hit",
                new ByteArrayInputStream(wav(pcmLe(44100, 16, 1), constant16(44100, (short) 100))));
        assertTrue(r.success, r.errorMessage);
        pool.addSound("hit", r.buffer);
        sources = pool.getSources("hit");
    }

    @AfterEach
    void close() {
        pool.cleanup();
        al.close();
    }

    private static float[] position(int source) {
        float[] x = new float[1], y = new float[1], z = new float[1];
        alGetSource3f(source, AL_POSITION, x, y, z);
        return new float[]{x[0], y[0], z[0]};
    }

    @Test
    void playSoundUsesHalfVolumeScaledByMasterAndDucking() {
        volume.setMasterVolume(0.8f);
        volume.setEnvironmentGain(0.5f);
        player.playSound("hit");
        assertEquals(0.5f * 0.8f * 0.5f, alGetSourcef(sources[0], AL_GAIN), EPS);
        assertEquals(AL_PLAYING, alGetSourcei(sources[0], AL_SOURCE_STATE));
    }

    @Test
    void twoDimensionalPlayPinsTheSourceToTheListener() {
        alSource3f(sources[0], AL_POSITION, 9, 9, 9);
        player.playSoundWithVolume("hit", 0.7f);
        assertEquals(0.7f, alGetSourcef(sources[0], AL_GAIN), EPS);
        assertEquals(AL_TRUE, alGetSourcei(sources[0], AL_SOURCE_RELATIVE));
        assertArrayEquals(new float[]{0, 0, 0}, position(sources[0]), EPS);
    }

    @Test
    void consecutivePlaysRotateThroughThePoolSoTheyCanOverlap() {
        player.playSoundWithVolume("hit", 0.1f);
        player.playSoundWithVolume("hit", 0.2f);
        player.playSoundWithVolume("hit", 0.3f);
        player.playSoundWithVolume("hit", 0.4f); // wraps to the first source
        assertEquals(0.4f, alGetSourcef(sources[0], AL_GAIN), EPS);
        assertEquals(0.2f, alGetSourcef(sources[1], AL_GAIN), EPS);
        assertEquals(0.3f, alGetSourcef(sources[2], AL_GAIN), EPS);
        for (int s : sources) assertEquals(AL_PLAYING, alGetSourcei(s, AL_SOURCE_STATE));
    }

    @Test
    void explicitPitchIsApplied() {
        player.playSound("hit", 0.6f, 1.25f);
        assertEquals(1.25f, alGetSourcef(sources[0], AL_PITCH), EPS);
        assertEquals(0.6f, alGetSourcef(sources[0], AL_GAIN), EPS);
        assertEquals(AL_TRUE, alGetSourcei(sources[0], AL_SOURCE_RELATIVE));
    }

    @Test
    void variationPitchStaysWithinTenPercent() {
        for (int i = 0; i < 60; i++) {
            player.playSoundWithVariation("hit", 0.5f);
            int s = sources[i % sources.length];
            float pitch = alGetSourcef(s, AL_PITCH);
            assertTrue(pitch >= 0.9f - EPS && pitch <= 1.1f + EPS, "pitch " + pitch);
        }
    }

    @Test
    void threeDimensionalPlayPlacesAStaticSourceInTheWorld() {
        volume.setMasterVolume(0.5f);
        player.playSoundWithVolume("hit", 1f); // leaves source 0 listener-relative
        player.playSoundAt3D("hit", 0.6f, 10, 64, -3);

        int s = sources[1];
        assertEquals(AL_FALSE, alGetSourcei(s, AL_SOURCE_RELATIVE));
        assertArrayEquals(new float[]{10, 64, -3}, position(s), EPS);
        float[] vx = new float[1], vy = new float[1], vz = new float[1];
        alGetSource3f(s, AL_VELOCITY, vx, vy, vz);
        assertArrayEquals(new float[]{0, 0, 0}, new float[]{vx[0], vy[0], vz[0]}, EPS);
        assertEquals(0.3f, alGetSourcef(s, AL_GAIN), EPS);
    }

    @Test
    void threeDimensionalPlayResetsASourceLastUsedFor2d() {
        player.playSoundWithVolume("hit", 1f);
        player.playSoundWithVolume("hit", 1f);
        player.playSoundWithVolume("hit", 1f); // all three now listener-relative
        player.playSoundAt3D("hit", 1f, 1.5f, 4, 5, 6);
        assertEquals(AL_FALSE, alGetSourcei(sources[0], AL_SOURCE_RELATIVE));
        assertEquals(1.5f, alGetSourcef(sources[0], AL_PITCH), EPS);
        assertArrayEquals(new float[]{4, 5, 6}, position(sources[0]), EPS);
    }

    @Test
    void threeDimensionalVariationKeepsPitchInRangeAndPosition() {
        player.playSoundAt3DWithVariation("hit", 0.5f, -1, 2, -3);
        float pitch = alGetSourcef(sources[0], AL_PITCH);
        assertTrue(pitch >= 0.9f - EPS && pitch <= 1.1f + EPS, "pitch " + pitch);
        assertArrayEquals(new float[]{-1, 2, -3}, position(sources[0]), EPS);
        assertEquals(AL_FALSE, alGetSourcei(sources[0], AL_SOURCE_RELATIVE));
    }

    @Test
    void unknownSoundsAreIgnoredWithoutTouchingThePool() {
        assertDoesNotThrow(() -> {
            player.playSound("missing");
            player.playSound("missing", 1f, 1f);
            player.playSoundWithVariation("missing", 1f);
            player.playSoundAt3D("missing", 1f, 0, 0, 0);
            player.playSoundAt3D("missing", 1f, 1f, 0, 0, 0);
            player.playSoundAt3DWithVariation("missing", 1f, 0, 0, 0);
        });
        for (int s : sources) assertEquals(AL_INITIAL, alGetSourcei(s, AL_SOURCE_STATE));
        assertEquals(0, pool.getNextSourceIndex("hit"), "cursor untouched");
    }

    @Test
    void mutedMasterPlaysAtZeroGain() {
        volume.setMasterVolume(0f);
        player.playSoundAt3D("hit", 1f, 0, 0, 0);
        assertEquals(0f, alGetSourcef(sources[0], AL_GAIN), EPS);
    }
}
