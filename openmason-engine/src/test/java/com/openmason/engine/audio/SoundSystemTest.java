package com.openmason.engine.audio;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_CHANNELS;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_ORIENTATION;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.alGenSources;
import static org.lwjgl.openal.AL10.alGetBufferi;
import static org.lwjgl.openal.AL10.alGetListener3f;
import static org.lwjgl.openal.AL10.alGetListenerfv;
import static org.lwjgl.openal.AL10.alGetSourcef;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alIsSource;

/**
 * The façade end to end on a loopback device: registration bookkeeping (SFX pool vs music
 * channel), the volume chain music x master x ducking applied live, and listener forwarding.
 */
@Tag("integration")
class SoundSystemTest {

    private static final float EPS = 1e-5f;
    private static final int MUSIC_FRAMES = LoopbackAl.RATE; // 1 s

    private LoopbackAl.Context context;
    private SoundSystem sound;

    @BeforeEach
    void open() {
        context = new LoopbackAl.Context();
        sound = new SoundSystem(context, new AudioLoader(), new AudioListener());
        sound.initialize();
    }

    @AfterEach
    void close() {
        sound.cleanup();
    }

    private static ByteArrayInputStream stereo(int frames) {
        return new ByteArrayInputStream(wav(pcmLe(LoopbackAl.RATE, 16, 2), constant16(frames, (short) 10, (short) -10)));
    }

    private int musicSource() {
        // The only source whose buffer is stereo is the music channel's (SFX are forced mono).
        // OpenAL Soft hands out source names sequentially, so scan down from a fresh one.
        int probe = alGenSources();
        for (int s = probe - 1; s > 0; s--) {
            if (alIsSource(s)) {
                int buf = alGetSourcei(s, AL_BUFFER);
                if (buf != 0 && alGetBufferi(buf, AL_CHANNELS) == 2) return s;
            }
        }
        throw new AssertionError("no music source");
    }

    @Test
    void initializeSetsUpTheDefaultListener() {
        float[] o = new float[6];
        alGetListenerfv(AL_ORIENTATION, o);
        assertArrayEquals(new float[]{0, 0, -1, 0, 1, 0}, o, EPS);
    }

    @Test
    void soundsAndMusicAreRegisteredInSeparateNamespaces() {
        sound.loadSound("click", stereo(100));
        sound.loadMusic("theme", stereo(100));

        assertTrue(sound.isSoundLoaded("click"));
        assertFalse(sound.isMusicLoaded("click"));
        assertTrue(sound.isMusicLoaded("theme"));
        assertFalse(sound.isSoundLoaded("theme"));
    }

    @Test
    void failedDecodeRegistersNothing() {
        sound.loadSound("junk", new ByteArrayInputStream("garbage".getBytes()));
        sound.loadMusic("junk", new ByteArrayInputStream("garbage".getBytes()));
        sound.loadSound("missing", (java.io.InputStream) null);
        assertFalse(sound.isSoundLoaded("junk"));
        assertFalse(sound.isMusicLoaded("junk"));
        assertFalse(sound.isSoundLoaded("missing"));
    }

    @Test
    void classpathPathLoadRegistersTheSound() {
        sound.loadSound("blip", "/audio/blip.wav");
        assertTrue(sound.isSoundLoaded("blip"));
    }

    @Test
    void musicStartsAtMusicVolumeTimesEffectiveMasterVolume() {
        sound.loadMusic("theme", stereo(MUSIC_FRAMES));
        sound.setMasterVolume(0.5f);
        sound.setEnvironmentGain(0.8f);
        sound.setMusicVolume(0.5f);

        sound.playMusic("theme");

        assertTrue(sound.isMusicPlaying());
        assertEquals(0.5f * 0.5f * 0.8f, alGetSourcef(musicSource(), AL_GAIN), EPS);
    }

    @Test
    void volumeChangesApplyLiveToPlayingMusic() {
        sound.loadMusic("theme", stereo(MUSIC_FRAMES));
        sound.playMusic("theme");
        int src = musicSource();

        sound.setMusicVolume(0.5f);
        assertEquals(0.5f, alGetSourcef(src, AL_GAIN), EPS);

        sound.setMasterVolume(0.5f);
        assertEquals(0.25f, alGetSourcef(src, AL_GAIN), EPS);

        sound.setEnvironmentGain(0.5f); // e.g. head goes underwater
        assertEquals(0.125f, alGetSourcef(src, AL_GAIN), EPS);

        sound.setEnvironmentGain(1f);
        assertEquals(0.25f, alGetSourcef(src, AL_GAIN), EPS);
    }

    @Test
    void musicVolumeIsClampedBeforeReachingOpenAl() {
        sound.loadMusic("theme", stereo(MUSIC_FRAMES));
        sound.playMusic("theme");
        sound.setMusicVolume(4f);
        assertEquals(1f, sound.getMusicVolume(), EPS);
        assertEquals(1f, alGetSourcef(musicSource(), AL_GAIN), EPS);
        sound.setMusicVolume(-2f);
        assertEquals(0f, alGetSourcef(musicSource(), AL_GAIN), EPS);
    }

    @Test
    void musicPlaybackEndsAndCanBeStopped() {
        sound.loadMusic("short", stereo(1000));
        sound.playMusic("short");
        assertTrue(sound.isMusicPlaying());
        context.al().render(2000);
        assertFalse(sound.isMusicPlaying());

        sound.playMusic("short");
        sound.stopMusic();
        assertFalse(sound.isMusicPlaying());
    }

    @Test
    void sfxGainIsBakedInAtPlayTimeWhileMusicGainIsLive() {
        sound.loadSound("click", stereo(MUSIC_FRAMES));
        sound.setMasterVolume(0.3f);
        sound.playSoundAt3D("click", 1f, new Vector3f(1, 2, 3));

        // Find the pool source that just played: gain = 1 x 0.3 (idle pool sources sit at 0.5).
        int played = -1;
        int probe = alGenSources();
        for (int s = probe - 1; s > 0; s--) {
            if (alIsSource(s) && Math.abs(alGetSourcef(s, AL_GAIN) - 0.3f) < EPS) played = s;
        }
        assertTrue(played > 0);

        sound.setMasterVolume(1f);
        assertEquals(0.3f, alGetSourcef(played, AL_GAIN), EPS, "already-playing SFX keep their gain");
    }

    @Test
    void listenerCallsAreForwardedOnceInitialized() {
        sound.setListenerPosition(4, 5, 6);
        float[] x = new float[1], y = new float[1], z = new float[1];
        alGetListener3f(AL_POSITION, x, y, z);
        assertArrayEquals(new float[]{4, 5, 6}, new float[]{x[0], y[0], z[0]}, EPS);

        sound.setListenerFromCamera(new Vector3f(7, 8, 9), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
        alGetListener3f(AL_POSITION, x, y, z);
        assertArrayEquals(new float[]{7, 8, 9}, new float[]{x[0], y[0], z[0]}, EPS);

        sound.setListenerOrientation(new Vector3f(0, 0, 1), new Vector3f(0, 1, 0));
        float[] o = new float[6];
        alGetListenerfv(AL_ORIENTATION, o);
        assertArrayEquals(new float[]{0, 0, 1, 0, 1, 0}, o, EPS);
    }

    @Test
    void diagnosticsReportLoadedSoundsAndTheHealthyContext() {
        sound.loadSound("blockpickup", stereo(10));
        String out = captureStdout(() -> {
            sound.testBasicFunctionality();
            sound.diagnoseOpenAL3D();
        });
        assertTrue(out.contains("Loaded sound buffers: [blockpickup]"), out);
        assertTrue(out.contains("Blockpickup sound is properly loaded"), out);
        assertTrue(out.contains("OpenAL is functioning correctly"), out);
        assertTrue(out.contains("Max Distance: 50.0"), out);
        assertTrue(out.contains("No OpenAL errors detected"), out);
    }

    @Test
    void diagnosticsListWhatIsLoadedWhenThePickupSoundIsMissing() {
        sound.loadSound("other", stereo(10));
        String err = captureStderr(sound::testBasicFunctionality);
        assertTrue(err.contains("Blockpickup sound failed to load"), err);
        assertTrue(err.contains("  - 'other'"), err);
    }

    @Test
    void cleanupReleasesEverythingAndTheContext() {
        sound.loadSound("click", stereo(10));
        sound.loadMusic("theme", stereo(10));
        sound.playMusic("theme");
        sound.cleanup();
        assertFalse(context.isInitialized());
        assertFalse(sound.isSoundLoaded("click"));
        assertFalse(sound.isMusicLoaded("theme"));
        // Second cleanup (the @AfterEach) must be harmless; reopen nothing.
    }

    private static String captureStdout(Runnable r) {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static String captureStderr(Runnable r) {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setErr(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
