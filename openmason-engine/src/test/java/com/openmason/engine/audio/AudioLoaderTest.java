package com.openmason.engine.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.openmason.engine.audio.AudioFixtures.aiff;
import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcm16;
import static com.openmason.engine.audio.AudioFixtures.pcm24;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.rawWav;
import static com.openmason.engine.audio.AudioFixtures.samples16;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoding half of {@link AudioLoader}: every input format is normalised to 16-bit signed
 * little-endian PCM at the source rate — mono for 3D SFX, up to stereo for music — before it
 * reaches OpenAL. Runs against a recording {@link AudioLoader.PcmUploader}, so no device.
 */
class AudioLoaderTest {

    /** What the loader would have handed to OpenAL. */
    private record Upload(String name, short[] samples, int bytes, int channels, int sampleRate) {
    }

    private final List<Upload> uploads = new ArrayList<>();
    private final AudioLoader loader = new AudioLoader((name, pcm, channels, rate) -> {
        uploads.add(new Upload(name, samples16(pcm), pcm.remaining(), channels, rate));
        return AudioLoader.LoadResult.success(1000 + uploads.size());
    });

    private Upload loadSfx(byte[] file) {
        AudioLoader.LoadResult r = loader.loadSound("sfx", new ByteArrayInputStream(file));
        assertTrue(r.success, () -> "load failed: " + r.errorMessage);
        assertEquals(1, uploads.size());
        return uploads.getFirst();
    }

    private Upload loadMusic(byte[] file) {
        AudioLoader.LoadResult r = loader.loadMusic("track", new ByteArrayInputStream(file));
        assertTrue(r.success, () -> "load failed: " + r.errorMessage);
        assertEquals(1, uploads.size());
        return uploads.getFirst();
    }

    // ---- format normalisation ----

    @Test
    void mono16BitWavPassesThroughSampleExact() {
        short[] samples = {0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE};
        Upload u = loadSfx(wav(pcmLe(22050, 16, 1), pcm16(samples)));
        assertEquals(1, u.channels());
        assertEquals(22050, u.sampleRate());
        assertArrayEquals(samples, u.samples());
    }

    @Test
    void successResultCarriesTheUploadersBufferHandle() {
        AudioLoader.LoadResult r = loader.loadSound("x", new ByteArrayInputStream(wav(pcmLe(8000, 16, 1), pcm16((short) 5))));
        assertTrue(r.success);
        assertEquals(1001, r.buffer);
        assertEquals(null, r.errorMessage);
    }

    @Test
    void sampleRateIsPreservedNotResampled() {
        for (int rate : new int[]{8000, 11025, 22050, 44100, 48000, 96000}) {
            uploads.clear();
            Upload u = loadSfx(wav(pcmLe(rate, 16, 1), constant16(10, (short) 7)));
            assertEquals(rate, u.sampleRate());
            assertEquals(10, u.samples().length, "frame count must not change at " + rate + " Hz");
        }
    }

    @Test
    void stereoSfxIsDownmixedToMonoByAveragingChannels() {
        Upload u = loadSfx(wav(pcmLe(44100, 16, 2), constant16(4, (short) 1000, (short) 3000)));
        assertEquals(1, u.channels());
        assertEquals(4 * 2, u.bytes(), "one 16-bit sample per frame");
        for (short s : u.samples()) assertEquals(2000, s);
    }

    @Test
    void outOfPhaseStereoCancelsInTheMonoDownmix() {
        Upload u = loadSfx(wav(pcmLe(44100, 16, 2), constant16(3, (short) 1000, (short) -1000)));
        for (short s : u.samples()) assertEquals(0, s);
    }

    @Test
    void multichannelSfxIsDownmixedToMono() {
        Upload u = loadSfx(wav(pcmLe(48000, 16, 4), constant16(5, (short) 100, (short) 200, (short) 300, (short) 400)));
        assertEquals(1, u.channels());
        assertEquals(5, u.samples().length);
        for (short s : u.samples()) assertEquals(250, s, 1);
    }

    @Test
    void stereoMusicKeepsBothChannelsInterleaved() {
        short[] interleaved = {1000, -1000, 2000, -2000, 3000, -3000};
        Upload u = loadMusic(wav(pcmLe(44100, 16, 2), pcm16(interleaved)));
        assertEquals(2, u.channels());
        assertArrayEquals(interleaved, u.samples());
    }

    @Test
    void monoMusicStaysMono() {
        Upload u = loadMusic(wav(pcmLe(32000, 16, 1), pcm16((short) 9, (short) -9)));
        assertEquals(1, u.channels());
        assertArrayEquals(new short[]{9, -9}, u.samples());
    }

    @Test
    void musicWithMoreThanTwoChannelsIsReducedToStereo() {
        Upload u = loadMusic(wav(pcmLe(48000, 16, 4), constant16(3, (short) 1, (short) 2, (short) 3, (short) 4)));
        assertEquals(2, u.channels());
        assertEquals(3 * 2, u.samples().length);
        // Java Sound's channel mixer keeps the first two channels (front L/R) and drops the rest.
        assertArrayEquals(new short[]{1, 2, 1, 2, 1, 2}, u.samples());
    }

    @Test
    void twentyFourBitStereoLikeTheShippedFootstepsDecodesToItsTop16Bits() {
        Upload u = loadSfx(wav(pcmLe(44100, 24, 2), pcm24(0x123456, 0x123456, -0x123456, -0x123456)));
        assertEquals(1, u.channels());
        assertEquals(44100, u.sampleRate());
        assertEquals(2, u.samples().length);
        assertEquals(0x1234, u.samples()[0], 1);
        assertEquals(-0x1234, u.samples()[1], 1);
    }

    @Test
    void unsigned8BitIsRecentredAndWidened() {
        AudioFormat u8 = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 8000, 8, 1, 1, 8000, false);
        Upload u = loadSfx(wav(u8, new byte[]{(byte) 0x80, (byte) 0xFF, 0x00}));
        assertEquals(8000, u.sampleRate());
        assertEquals(0, u.samples()[0], "0x80 is silence in unsigned 8-bit");
        assertEquals(Short.MAX_VALUE, u.samples()[1], 1);
        assertEquals(Short.MIN_VALUE, u.samples()[2]);
    }

    @Test
    void bigEndianAiffIsByteSwappedToLittleEndian() {
        AudioFormat be = new AudioFormat(22050, 16, 1, true, true);
        Upload u = loadSfx(aiff(be, new byte[]{0x01, 0x02, (byte) 0xFF, (byte) 0xFE}));
        assertArrayEquals(new short[]{0x0102, -2}, u.samples());
    }

    @Test
    void floatWavIsConvertedToSigned16() {
        AudioFormat f32 = new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 44100, 32, 1, 4, 44100, false);
        byte[] frames = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0.5f).putFloat(-1f).putFloat(0f).array();
        Upload u = loadSfx(wav(f32, frames));
        assertEquals(16384, u.samples()[0], 2);
        assertEquals(Short.MIN_VALUE, u.samples()[1]);
        assertEquals(0, u.samples()[2]);
    }

    @Test
    void muLawWavIsExpandedToLinearPcm() {
        AudioFormat ulaw = new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false);
        Upload u = loadSfx(wav(ulaw, new byte[]{(byte) 0xFF, 0x00, (byte) 0x80}));
        assertEquals(0, u.samples()[0], "0xFF is mu-law zero");
        assertTrue(u.samples()[1] < -30000, "0x00 is mu-law negative full scale");
        assertTrue(u.samples()[2] > 30000, "0x80 is mu-law positive full scale");
    }

    // ---- truncated / malformed input ----

    @Test
    void truncatedDataChunkDecodesTheFramesThatArePresent() {
        // Header claims 1000 bytes of audio, file carries three samples.
        Upload u = loadSfx(rawWav(1, 8000, 16, 1000, pcm16((short) 1, (short) 2, (short) 3)));
        assertArrayEquals(new short[]{1, 2, 3}, u.samples());
    }

    @Test
    void trailingPartialFrameIsDroppedSoOpenAlGetsWholeFrames() {
        Upload u = loadSfx(rawWav(1, 8000, 16, 7, new byte[]{1, 0, 2, 0, 3, 0, 4}));
        assertEquals(0, u.bytes() % 2, "OpenAL rejects a MONO16 buffer with an odd byte count");
        assertArrayEquals(new short[]{1, 2, 3}, u.samples());
    }

    @Test
    void stereoMusicWithAPartialTrailingFrameUploadsWholeStereoFrames() {
        byte[] data = Arrays.copyOf(pcm16((short) 1, (short) 2, (short) 3, (short) 4), 8 + 3);
        Upload u = loadMusic(rawWav(2, 8000, 16, data.length, data));
        assertEquals(0, u.bytes() % 4);
        assertArrayEquals(new short[]{1, 2, 3, 4}, u.samples());
    }

    @Test
    void wavWithAnEmptyDataChunkUploadsAnEmptyBuffer() {
        Upload u = loadSfx(rawWav(1, 8000, 16, 0, new byte[0]));
        assertEquals(0, u.bytes());
    }

    @Test
    void garbageBytesFailAsUnsupportedWithoutUploading() {
        AudioLoader.LoadResult r = loader.loadSound("junk", new ByteArrayInputStream("not audio at all".getBytes()));
        assertFalse(r.success);
        assertEquals(-1, r.buffer);
        assertTrue(r.errorMessage.startsWith("Unsupported audio file format"), r.errorMessage);
        assertTrue(uploads.isEmpty());
    }

    @Test
    void emptyStreamFailsWithoutUploading() {
        AudioLoader.LoadResult r = loader.loadMusic("nothing", new ByteArrayInputStream(new byte[0]));
        assertFalse(r.success);
        assertTrue(uploads.isEmpty());
    }

    @Test
    void headerCutShortFailsWithoutUploading() {
        byte[] cut = Arrays.copyOf(rawWav(1, 8000, 16, 0, new byte[0]), 20);
        AudioLoader.LoadResult r = loader.loadSound("cut", new ByteArrayInputStream(cut));
        assertFalse(r.success);
        assertTrue(uploads.isEmpty());
    }

    @Test
    void ioErrorWhileReadingIsReportedAsAFailureNotThrown() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk on fire");
            }
        };
        AudioLoader.LoadResult r = loader.loadSound("io", failing);
        assertFalse(r.success);
        assertTrue(r.errorMessage.contains("disk on fire"), r.errorMessage);
        assertTrue(uploads.isEmpty());
    }

    @Test
    void nullStreamsFailNamingTheSound() {
        AudioLoader.LoadResult sfx = loader.loadSound("footstep", (InputStream) null);
        AudioLoader.LoadResult music = loader.loadMusic("theme", null);
        assertFalse(sfx.success);
        assertFalse(music.success);
        assertTrue(sfx.errorMessage.contains("footstep"), sfx.errorMessage);
        assertTrue(music.errorMessage.contains("theme"), music.errorMessage);
        assertTrue(uploads.isEmpty());
    }

    // ---- stream ownership ----

    /** Counts close() calls on the caller's stream. */
    private static final class CloseTracking extends FilterInputStream {
        int closes;

        CloseTracking(byte[] data) {
            super(new ByteArrayInputStream(data));
        }

        @Override
        public void close() throws IOException {
            closes++;
            super.close();
        }
    }

    @Test
    void callerStreamIsClosedAfterASuccessfulLoad() {
        CloseTracking in = new CloseTracking(wav(pcmLe(8000, 16, 1), pcm16((short) 1)));
        assertTrue(loader.loadSound("a", in).success);
        assertTrue(in.closes >= 1);
    }

    @Test
    void callerStreamIsClosedAfterAFailedDecode() {
        CloseTracking sfx = new CloseTracking("junk".getBytes());
        CloseTracking music = new CloseTracking("junk".getBytes());
        assertFalse(loader.loadSound("a", sfx).success);
        assertFalse(loader.loadMusic("b", music).success);
        assertTrue(sfx.closes >= 1);
        assertTrue(music.closes >= 1);
    }

    @Test
    void uploaderFailureIsReturnedAsIs() {
        AudioLoader failing = new AudioLoader((n, pcm, ch, rate) -> AudioLoader.LoadResult.failure("backend says no"));
        AudioLoader.LoadResult r = failing.loadSound("a", new ByteArrayInputStream(wav(pcmLe(8000, 16, 1), pcm16((short) 1))));
        assertFalse(r.success);
        assertEquals("backend says no", r.errorMessage);
    }

    @Test
    void decodedBufferHandedToTheBackendIsDirectAndFlipped() {
        ByteBuffer[] seen = new ByteBuffer[1];
        AudioLoader capturing = new AudioLoader((n, pcm, ch, rate) -> {
            seen[0] = pcm;
            return AudioLoader.LoadResult.success(1);
        });
        capturing.loadSound("a", new ByteArrayInputStream(wav(pcmLe(8000, 16, 1), pcm16((short) 1, (short) 2))));
        assertNotNull(seen[0]);
        assertTrue(seen[0].isDirect(), "LWJGL needs an off-heap buffer");
        assertEquals(0, seen[0].position());
        assertEquals(4, seen[0].limit());
    }

    // ---- classpath resource lookup ----

    @Test
    void classpathResourceIsFoundWithOrWithoutLeadingSlash() {
        for (String path : new String[]{"/audio/blip.wav", "audio/blip.wav"}) {
            uploads.clear();
            AudioLoader.LoadResult r = loader.loadSound("blip", path);
            assertTrue(r.success, () -> path + ": " + r.errorMessage);
            Upload u = uploads.getFirst();
            assertEquals(22050, u.sampleRate());
            assertEquals(64, u.samples().length);
            assertEquals(-16384, u.samples()[0]);
            assertEquals(63 * 512 - 16384, u.samples()[63]);
        }
    }

    @Test
    void missingClasspathResourceFailsWhenNoFallbackSampleIsPresent() {
        // The engine's test classpath has no /sounds/GrassWalk.wav, the loader's hardcoded fallback.
        AudioLoader.LoadResult r = loader.loadSound("ghost", "/audio/does-not-exist.wav");
        assertFalse(r.success);
        assertEquals("Could not find sound file at any path!", r.errorMessage);
        assertTrue(uploads.isEmpty());
    }
}
