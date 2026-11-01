package com.openmason.engine.audio;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.audio.AudioFixtures.pcm16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SoundSystem} on a machine where OpenAL could not open a device (no sound card, headless
 * CI, a broken driver). Nothing may reach OpenAL — before the guard, the game's first eager load
 * ({@code GameBootstrap.configureSoundSystem}) hit {@code alGenBuffers} with no capabilities set
 * and died with an ExceptionInInitializerError, and every frame's listener update would have too.
 *
 * <p>The fakes record any attempt to touch the backend, so these assertions hold regardless of
 * whether another test in the JVM has created AL capabilities.
 */
@Tag("regression")
class SoundSystemWithoutDeviceTest {

    /** A context whose device could not be opened. */
    private static final class NoDevice extends OpenALContext {
        int initializeCalls;
        int cleanupCalls;

        @Override
        public boolean initialize() {
            initializeCalls++;
            return false;
        }

        @Override
        public void cleanup() {
            cleanupCalls++;
        }
    }

    /** Fails the test on any listener call: each one is a direct AL call in production. */
    private static final class ForbiddenListener extends AudioListener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void setListenerPosition(float x, float y, float z) {
            calls.add("position");
        }

        @Override
        public void setListenerOrientation(float fx, float fy, float fz, float ux, float uy, float uz) {
            calls.add("orientation");
        }

        @Override
        public void setListenerOrientation(Vector3f forward, Vector3f up) {
            calls.add("orientation(vec)");
        }

        @Override
        public void setListenerFromCamera(Vector3f position, Vector3f front, Vector3f up) {
            calls.add("camera");
        }

        @Override
        public void initializeDefaultListener() {
            calls.add("default");
        }
    }

    private final List<String> uploads = new ArrayList<>();
    private NoDevice context;
    private ForbiddenListener listener;
    private SoundSystem sound;

    @BeforeEach
    void setUp() {
        context = new NoDevice();
        listener = new ForbiddenListener();
        AudioLoader loader = new AudioLoader((name, pcm, channels, rate) -> {
            uploads.add(name);
            return AudioLoader.LoadResult.success(1);
        });
        sound = new SoundSystem(context, loader, listener);
        sound.initialize();
    }

    private static ByteArrayInputStream validWav() {
        return new ByteArrayInputStream(wav(pcmLe(44100, 16, 1), pcm16((short) 1, (short) 2, (short) 3)));
    }

    @Test
    void failedInitializeSkipsTheDefaultListenerSetup() {
        assertEquals(1, context.initializeCalls);
        assertTrue(listener.calls.isEmpty(), listener.calls::toString);
    }

    @Test
    void loadingAValidSoundIsSkippedNotUploaded() {
        sound.loadSound("blockpickup", validWav());
        assertTrue(uploads.isEmpty(), "no device: nothing may be uploaded");
        assertFalse(sound.isSoundLoaded("blockpickup"));
    }

    @Test
    void loadingFromAClasspathPathIsSkippedToo() {
        sound.loadSound("blip", "/audio/blip.wav");
        assertTrue(uploads.isEmpty());
        assertFalse(sound.isSoundLoaded("blip"));
    }

    @Test
    void loadingMusicIsSkippedNotUploaded() {
        sound.loadMusic("theme", validWav());
        assertTrue(uploads.isEmpty());
        assertFalse(sound.isMusicLoaded("theme"));
    }

    @Test
    void everyPlaybackCallIsASilentNoOp() {
        sound.loadSound("blockpickup", validWav());
        Vector3f at = new Vector3f(1, 2, 3);
        assertDoesNotThrow(() -> {
            sound.playSound("blockpickup");
            sound.playSound("blockpickup", 0.5f, 1.2f);
            sound.playSoundWithVolume("blockpickup", 0.7f);
            sound.playSoundWithVariation("blockpickup", 0.7f);
            sound.playSoundAt3D("blockpickup", 0.5f, 1, 2, 3);
            sound.playSoundAt3D("blockpickup", 0.5f, at);
            sound.playSoundAt3D("blockpickup", 0.5f, 1.1f, 1, 2, 3);
            sound.playSoundAt3D("blockpickup", 0.5f, 1.1f, at);
            sound.playSoundAt3DWithVariation("blockpickup", 0.5f, 1, 2, 3);
            sound.playSoundAt3DWithVariation("blockpickup", 0.5f, at);
            sound.playMusic("theme");
            sound.stopMusic();
        });
        assertFalse(sound.isMusicPlaying());
    }

    @Test
    void perFrameListenerUpdatesAreNotForwardedToOpenAl() {
        Vector3f p = new Vector3f(1, 64, 1);
        sound.setListenerPosition(1, 2, 3);
        sound.setListenerOrientation(new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        sound.setListenerFromCamera(p, new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        assertTrue(listener.calls.isEmpty(), listener.calls::toString);
    }

    @Test
    void diagnosticsDoNotQueryOpenAl() {
        // Both used to call alGetError/alGetInteger unconditionally (the F3/chat diagnostics).
        assertDoesNotThrow(sound::testBasicFunctionality);
        assertDoesNotThrow(sound::diagnoseOpenAL3D);
    }

    @Test
    void volumeBookkeepingStillWorksWithoutADevice() {
        sound.setMasterVolume(0.5f);
        sound.setEnvironmentGain(2f);
        sound.setMusicVolume(-1f);
        assertEquals(0.5f, sound.getMasterVolume(), 1e-6f);
        assertEquals(1f, sound.getEnvironmentGain(), 1e-6f);
        assertEquals(0f, sound.getMusicVolume(), 1e-6f);
        sound.setMusicVolume(0.3f);
        assertEquals(0.3f, sound.getMusicVolume(), 1e-6f);
    }

    @Test
    void cleanupWithNothingLoadedOnlyReleasesTheContext() {
        assertDoesNotThrow(sound::cleanup);
        assertEquals(1, context.cleanupCalls);
    }
}
