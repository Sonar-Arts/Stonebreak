package com.openmason.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.openal.AL10.AL_BITS;
import static org.lwjgl.openal.AL10.AL_CHANNELS;
import static org.lwjgl.openal.AL10.AL_FREQUENCY;
import static org.lwjgl.openal.AL10.AL_NO_ERROR;
import static org.lwjgl.openal.AL10.AL_SIZE;
import static org.lwjgl.openal.AL10.alDeleteBuffers;
import static org.lwjgl.openal.AL10.alGetBufferi;
import static org.lwjgl.openal.AL10.alGetError;
import static org.lwjgl.openal.AL10.alIsBuffer;
import static org.lwjgl.openal.AL10.alSourcePlay;

/** The production {@link AudioLoader} uploading into a real (loopback, device-free) OpenAL context. */
@Tag("integration")
class OpenAlUploadTest {

    private LoopbackAl al;
    private final AudioLoader loader = new AudioLoader();

    @BeforeEach
    void open() {
        al = LoopbackAl.open();
    }

    @AfterEach
    void close() {
        al.close();
    }

    @Test
    void sfxBecomesAMono16BufferAtTheSourceRate() {
        byte[] file = wav(pcmLe(22050, 16, 2), constant16(100, (short) 10, (short) 20));
        AudioLoader.LoadResult r = loader.loadSound("sfx", new ByteArrayInputStream(file));
        assertTrue(r.success, r.errorMessage);
        assertTrue(alIsBuffer(r.buffer));
        assertEquals(1, alGetBufferi(r.buffer, AL_CHANNELS));
        assertEquals(16, alGetBufferi(r.buffer, AL_BITS));
        assertEquals(22050, alGetBufferi(r.buffer, AL_FREQUENCY));
        assertEquals(100 * 2, alGetBufferi(r.buffer, AL_SIZE));
        alDeleteBuffers(r.buffer);
    }

    @Test
    void musicBecomesAStereo16Buffer() {
        byte[] file = wav(pcmLe(48000, 16, 2), constant16(100, (short) 10, (short) 20));
        AudioLoader.LoadResult r = loader.loadMusic("track", new ByteArrayInputStream(file));
        assertTrue(r.success, r.errorMessage);
        assertEquals(2, alGetBufferi(r.buffer, AL_CHANNELS));
        assertEquals(48000, alGetBufferi(r.buffer, AL_FREQUENCY));
        assertEquals(100 * 4, alGetBufferi(r.buffer, AL_SIZE));
        alDeleteBuffers(r.buffer);
    }

    @Test
    void classpathResourceLoadsIntoARealBuffer() {
        AudioLoader.LoadResult r = loader.loadSound("blip", "/audio/blip.wav");
        assertTrue(r.success, r.errorMessage);
        assertEquals(22050, alGetBufferi(r.buffer, AL_FREQUENCY));
        assertEquals(64 * 2, alGetBufferi(r.buffer, AL_SIZE));
        alDeleteBuffers(r.buffer);
    }

    /**
     * alGetError reports the first error raised since it was last read, by ANY caller. The upload
     * used to check it without clearing it first, so an unrelated earlier mistake (here: playing
     * a source name that doesn't exist) made a perfectly valid sound fail to load — and leaked
     * the buffer it had just generated.
     */
    @Test
    @Tag("regression")
    void staleErrorFromAnUnrelatedCallDoesNotFailAValidLoad() {
        alSourcePlay(987654); // AL_INVALID_NAME, left unread
        AudioLoader.LoadResult r = loader.loadSound("sfx",
                new ByteArrayInputStream(wav(pcmLe(44100, 16, 1), constant16(10, (short) 1))));
        assertTrue(r.success, r.errorMessage);
        assertTrue(alIsBuffer(r.buffer));
        assertEquals(AL_NO_ERROR, alGetError());
        alDeleteBuffers(r.buffer);
    }
}
