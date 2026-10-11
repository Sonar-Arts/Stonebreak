package com.openmason.engine.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** In-memory audio file builders for the audio tests (no files, no device). */
final class AudioFixtures {

    private AudioFixtures() {
    }

    /** Signed little-endian PCM format with {@code bits} per sample. */
    static AudioFormat pcmLe(float rate, int bits, int channels) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits, channels,
                channels * bits / 8, rate, false);
    }

    /** Encodes raw frames as a complete WAV file through Java Sound's own writer. */
    static byte[] wav(AudioFormat format, byte[] frames) {
        return write(format, frames, AudioFileFormat.Type.WAVE);
    }

    /** Encodes raw frames as an AIFF file (big-endian samples). */
    static byte[] aiff(AudioFormat format, byte[] frames) {
        return write(format, frames, AudioFileFormat.Type.AIFF);
    }

    private static byte[] write(AudioFormat format, byte[] frames, AudioFileFormat.Type type) {
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(frames), format,
                frames.length / format.getFrameSize())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(in, type, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Hand-written canonical 44-byte-header PCM WAV whose data chunk DECLARES
     * {@code declaredDataBytes} but carries {@code data} (lets tests build truncated files).
     */
    static byte[] rawWav(int channels, int rate, int bits, int declaredDataBytes, byte[] data) {
        int blockAlign = channels * bits / 8;
        ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + declaredDataBytes).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * blockAlign).putShort((short) blockAlign).putShort((short) bits);
        b.put("data".getBytes()).putInt(declaredDataBytes).put(data);
        return b.array();
    }

    /** Interleaved 16-bit little-endian samples. */
    static byte[] pcm16(short... samples) {
        ByteBuffer b = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) b.putShort(s);
        return b.array();
    }

    /** Interleaved 24-bit little-endian samples (values are 24-bit signed). */
    static byte[] pcm24(int... samples) {
        byte[] out = new byte[samples.length * 3];
        for (int i = 0; i < samples.length; i++) {
            out[i * 3] = (byte) samples[i];
            out[i * 3 + 1] = (byte) (samples[i] >> 8);
            out[i * 3 + 2] = (byte) (samples[i] >> 16);
        }
        return out;
    }

    /** Decodes 16-bit little-endian PCM bytes back to samples. */
    static short[] samples16(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[data.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = b.getShort();
        return out;
    }

    /** Same as {@link #samples16(byte[])} for a direct buffer, without moving its position. */
    static short[] samples16(ByteBuffer data) {
        byte[] copy = new byte[data.remaining()];
        data.duplicate().get(copy);
        return samples16(copy);
    }

    /** {@code n} frames of a constant 16-bit value per channel. */
    static byte[] constant16(int frames, short... perChannel) {
        short[] s = new short[frames * perChannel.length];
        for (int f = 0; f < frames; f++) {
            System.arraycopy(perChannel, 0, s, f * perChannel.length, perChannel.length);
        }
        return pcm16(s);
    }
}
