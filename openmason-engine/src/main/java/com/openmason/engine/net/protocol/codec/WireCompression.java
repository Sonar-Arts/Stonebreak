package com.openmason.engine.net.protocol.codec;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.net.protocol.ByteBufIO;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compressed byte blobs on the wire (issue #346): chunk payloads over TCP. A blob is one codec
 * byte, then — when compressed — the raw length as a varint, then the bytes as a bounded
 * byte array.
 *
 * <p>Codecs are negotiated, not assumed: the native kernels (zstd) are optional on both ends,
 * so a peer advertises the codecs it can decode as a bitmask ({@link #localDecodeMask}) and the
 * sender picks from it ({@link #choose}). zstd when both ends have the kernels; DEFLATE, which
 * every JDK can decode, otherwise. A peer that advertises nothing gets raw blobs.
 *
 * <p>Blobs under {@link #MIN_COMPRESS_BYTES}, and blobs that do not shrink, go raw.
 */
public final class WireCompression {

    public static final int NONE = 0;
    public static final int ZSTD = 1;
    public static final int DEFLATE = 2;

    /** Smaller blobs are sent raw: the frame overhead eats the gain. */
    public static final int MIN_COMPRESS_BYTES = 256;
    /** zstd's default level: ~5× on terrain at a fraction of a millisecond per chunk. */
    static final int ZSTD_LEVEL = 3;

    private WireCompression() {}

    /** The mask bit for {@code codec}. */
    public static int bit(int codec) {
        return 1 << codec;
    }

    /** Codecs this process can decode: DEFLATE always, zstd when the native kernels loaded. */
    public static int localDecodeMask() {
        return bit(DEFLATE) | (CendaKernels.isAvailable() ? bit(ZSTD) : 0);
    }

    /** The codec to send a {@code rawLength}-byte blob with, to a peer decoding {@code peerMask}. */
    public static int choose(int peerMask, int rawLength) {
        if (rawLength < MIN_COMPRESS_BYTES) {
            return NONE;
        }
        if ((peerMask & bit(ZSTD)) != 0 && CendaKernels.isAvailable()) {
            return ZSTD;
        }
        return (peerMask & bit(DEFLATE)) != 0 ? DEFLATE : NONE;
    }

    /**
     * {@code raw} compressed with {@code codec}, or null when the codec is NONE, failed, or
     * did not make it smaller (the caller then sends it raw).
     */
    public static byte[] compress(int codec, byte[] raw) {
        byte[] out = switch (codec) {
            case ZSTD -> CendaKernels.zstdCompress(raw, ZSTD_LEVEL);
            case DEFLATE -> deflate(raw);
            default -> null;
        };
        return out != null && out.length < raw.length ? out : null;
    }

    /** Writes {@code raw} as a blob for a peer decoding {@code peerMask}. */
    public static void writeBlob(ByteBuf out, byte[] raw, int maxRawBytes, int peerMask) {
        int codec = choose(peerMask, raw.length);
        writeBlob(out, raw, codec == NONE ? null : compress(codec, raw), codec, maxRawBytes);
    }

    /**
     * Writes a blob whose compressed form is already known ({@code compressed} under
     * {@code codec}); a null {@code compressed} writes {@code raw} as is.
     */
    public static void writeBlob(ByteBuf out, byte[] raw, byte[] compressed, int codec, int maxRawBytes) {
        if (compressed == null || codec == NONE) {
            out.writeByte(NONE);
            ByteBufIO.writeByteArray(out, raw, maxRawBytes);
            return;
        }
        if (raw.length > maxRawBytes) {
            throw new io.netty.handler.codec.EncoderException(
                "Byte array exceeds " + maxRawBytes + " (got " + raw.length + ")");
        }
        out.writeByte(codec);
        ByteBufIO.writeVarInt(out, raw.length);
        ByteBufIO.writeByteArray(out, compressed, maxRawBytes);
    }

    /** Reads a blob written by {@link #writeBlob}; its raw form may not exceed {@code maxRawBytes}. */
    public static byte[] readBlob(ByteBuf in, int maxRawBytes) {
        if (!in.isReadable()) {
            throw new DecoderException("Truncated packet: missing compression codec");
        }
        int codec = in.readUnsignedByte();
        if (codec == NONE) {
            return ByteBufIO.readByteArray(in, maxRawBytes);
        }
        int rawLength = ByteBufIO.readVarInt(in);
        if (rawLength <= 0 || rawLength > maxRawBytes) {
            throw new DecoderException("Compressed blob raw length out of range: " + rawLength);
        }
        byte[] compressed = ByteBufIO.readByteArray(in, maxRawBytes);
        byte[] raw = switch (codec) {
            case ZSTD -> {
                if (!CendaKernels.isAvailable()) {
                    throw new DecoderException("zstd blob received but the native kernels are not loaded");
                }
                yield CendaKernels.zstdDecompress(compressed, rawLength);
            }
            case DEFLATE -> inflate(compressed, rawLength);
            default -> throw new DecoderException("Unknown compression codec " + codec);
        };
        if (raw == null) {
            throw new DecoderException("Corrupt " + (codec == ZSTD ? "zstd" : "deflate")
                + " blob (" + compressed.length + " -> " + rawLength + " bytes)");
        }
        return raw;
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buf = new byte[raw.length]; // never worth sending a larger result
            int n = 0;
            while (!deflater.finished()) {
                if (n == buf.length) {
                    return null;
                }
                n += deflater.deflate(buf, n, buf.length - n);
            }
            return java.util.Arrays.copyOf(buf, n);
        } finally {
            deflater.end();
        }
    }

    /** Exactly {@code rawLength} bytes, or null when the stream is corrupt or the wrong size. */
    private static byte[] inflate(byte[] compressed, int rawLength) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[rawLength];
            int n = 0;
            while (n < rawLength) {
                int got = inflater.inflate(out, n, rawLength - n);
                if (got == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }
            if (n == rawLength && !inflater.finished()) {
                // The output filled up before the stream's trailer was read: one more byte
                // must not exist (a bomb stops here at rawLength).
                if (inflater.inflate(new byte[1]) != 0) {
                    return null;
                }
            }
            if (n != rawLength || !inflater.finished()) {
                return null;
            }
            return out;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
