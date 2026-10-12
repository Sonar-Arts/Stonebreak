package com.openmason.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_LOOPING;
import static org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE;
import static org.lwjgl.openal.AL10.AL_TRUE;
import static org.lwjgl.openal.AL10.alGenSources;
import static org.lwjgl.openal.AL10.alGetSourcef;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alIsBuffer;
import static org.lwjgl.openal.AL10.alIsSource;

/**
 * The single 2D music source: lazy creation, track switching, live gain and end-of-track
 * detection (what MusicManager polls to advance its playlist). Playback time only advances
 * when the loopback device renders, so "the track ended" is deterministic.
 */
@Tag("integration")
class MusicChannelTest {

    private static final int FRAMES = 4410; // 0.1 s at 44.1 kHz

    private LoopbackAl al;
    private MusicChannel music;

    @BeforeEach
    void open() {
        al = LoopbackAl.open();
        music = new MusicChannel();
    }

    @AfterEach
    void close() {
        music.cleanup();
        al.close();
    }

    private static int track() {
        AudioLoader.LoadResult r = new AudioLoader().loadMusic("t",
                new ByteArrayInputStream(wav(pcmLe(LoopbackAl.RATE, 16, 2), constant16(FRAMES, (short) 50, (short) -50))));
        assertTrue(r.success, r.errorMessage);
        return r.buffer;
    }

    /** The channel's source, found by elimination: the next generated name is past it. */
    private static int currentSourceFor(int buffer) {
        int probe = alGenSources();
        for (int s = probe - 1; s > 0; s--) {
            if (alIsSource(s) && alGetSourcei(s, AL_BUFFER) == buffer) return s;
        }
        throw new AssertionError("no source bound to buffer " + buffer);
    }

    @Test
    void nothingPlaysBeforeATrackIsStarted() {
        assertFalse(music.isPlaying());
        assertDoesNotThrow(() -> {
            music.stop();
            music.setGain(0.5f); // no source yet: must be a no-op, not an AL error
        });
    }

    @Test
    void unknownTrackDoesNotStartAnything() {
        music.addTrack("known", track());
        music.play("unknown", 1f);
        assertFalse(music.isPlaying());
    }

    @Test
    void playStartsTheTrackOnAListenerRelativeNonLoopingSource() {
        int buf = track();
        music.addTrack("theme", buf);
        assertTrue(music.isTrackLoaded("theme"));

        music.play("theme", 0.4f);

        assertTrue(music.isPlaying());
        int src = currentSourceFor(buf);
        assertEquals(0.4f, alGetSourcef(src, AL_GAIN), 1e-6f);
        assertEquals(AL_TRUE, alGetSourcei(src, AL_SOURCE_RELATIVE));
        assertEquals(AL_FALSE, alGetSourcei(src, AL_LOOPING));
    }

    @Test
    void isPlayingTurnsFalseOnceTheTrackHasBeenFullyRendered() {
        music.addTrack("theme", track());
        music.play("theme", 1f);

        al.render(FRAMES / 2);
        assertTrue(music.isPlaying(), "half way through");

        al.render(FRAMES); // well past the end
        assertFalse(music.isPlaying(), "a finished track must report not playing so the playlist advances");
    }

    @Test
    void playingAnotherTrackRebindsTheSameSourceMidPlayback() {
        int a = track();
        int b = track();
        music.addTrack("a", a);
        music.addTrack("b", b);

        music.play("a", 1f);
        int src = currentSourceFor(a);
        al.render(FRAMES / 4);
        music.play("b", 0.5f);

        assertEquals(b, alGetSourcei(src, AL_BUFFER), "one source, rebound to the new track");
        assertEquals(0.5f, alGetSourcef(src, AL_GAIN), 1e-6f);
        assertTrue(music.isPlaying());
    }

    @Test
    void replayingAFinishedTrackStartsItAgain() {
        music.addTrack("theme", track());
        music.play("theme", 1f);
        al.render(FRAMES * 2);
        assertFalse(music.isPlaying());
        music.play("theme", 1f);
        assertTrue(music.isPlaying());
    }

    @Test
    void setGainAppliesLiveToTheCurrentTrack() {
        int buf = track();
        music.addTrack("theme", buf);
        music.play("theme", 1f);
        music.setGain(0.25f);
        assertEquals(0.25f, alGetSourcef(currentSourceFor(buf), AL_GAIN), 1e-6f);
        assertTrue(music.isPlaying());
    }

    @Test
    void stopEndsPlayback() {
        music.addTrack("theme", track());
        music.play("theme", 1f);
        music.stop();
        assertFalse(music.isPlaying());
    }

    @Test
    void cleanupReleasesTheSourceAndEveryTrackBuffer() {
        int a = track();
        int b = track();
        music.addTrack("a", a);
        music.addTrack("b", b);
        music.play("a", 1f);
        int src = currentSourceFor(a);

        music.cleanup();

        assertFalse(alIsSource(src));
        assertFalse(alIsBuffer(a));
        assertFalse(alIsBuffer(b));
        assertFalse(music.isTrackLoaded("a"));
        assertFalse(music.isPlaying());
    }
}
