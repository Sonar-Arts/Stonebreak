package com.openmason.engine.net.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import java.nio.charset.StandardCharsets;

/**
 * Bounded read/write helpers over a Netty {@link ByteBuf}.
 *
 * <p>Centralizes the wire-safety caps that protect the decoder against hostile or
 * corrupt payloads (oversized strings, arrays, frames). Every variable-length read is
 * bounds-checked: a violation throws {@link DecoderException} <em>before</em> allocating,
 * so a malformed packet fails fast instead of exhausting memory. Over-limit writes throw
 * {@link EncoderException} (a programming error on our side).
 *
 * <p>Strings are length-prefixed UTF-8 (varint <em>byte</em> length); ids, lengths, and
 * counts are unsigned LEB128 varints.
 *
 * <p>Caps are ported verbatim from the legacy {@code PacketCodec}.
 */
public final class ByteBufIO {

    // ─── Wire-safety caps ────────────────────────────────────────────────────
    /** Maximum framed packet size (enforced by the pipeline's frame decoder). */
    public static final int MAX_FRAME_BYTES         = 8 * 1024 * 1024; // 8 MiB
    /** Maximum encoded chunk blob size. */
    public static final int MAX_CHUNK_BYTES         = 200 * 1024;
    /** Maximum entries in a single multi-block-change packet (one full section). */
    public static final int MAX_MULTI_BLOCK_ENTRIES = 4096;
    public static final int MAX_USERNAME_CHARS      = 32;
    public static final int MAX_CHAT_CHARS          = 256;
    public static final int MAX_REASON_CHARS        = 128;
    public static final int MAX_METADATA_CHARS      = 256;

    private ByteBufIO() {}

    // ─── Varint (unsigned LEB128) ─────────────────────────────────────────────

    public static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int readVarInt(ByteBuf in) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (shift >= 35) {
                throw new DecoderException("VarInt too large");
            }
            byte b = readByte(in);
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    // ─── Strings (UTF-8, varint byte length, char-count bounded) ──────────────

    public static void writeString(ByteBuf out, String s, int maxChars) {
        if (s == null) {
            s = "";
        }
        if (s.length() > maxChars) {
            throw new EncoderException("String exceeds " + maxChars + " chars (got " + s.length() + ")");
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    public static String readString(ByteBuf in, int maxChars) {
        int byteLen = readVarInt(in);
        // UTF-8 is at most 4 bytes/char; reject obviously oversized byte lengths early.
        if (byteLen < 0 || byteLen > maxChars * 4) {
            throw new DecoderException("String byte length out of range: " + byteLen);
        }
        ensureReadable(in, byteLen);
        String s = in.toString(in.readerIndex(), byteLen, StandardCharsets.UTF_8);
        in.skipBytes(byteLen);
        if (s.length() > maxChars) {
            throw new DecoderException("String exceeds " + maxChars + " chars (got " + s.length() + ")");
        }
        return s;
    }

    // ─── Byte arrays (varint length, bounded) ─────────────────────────────────

    public static void writeByteArray(ByteBuf out, byte[] data, int maxLen) {
        if (data.length > maxLen) {
            throw new EncoderException("Byte array exceeds " + maxLen + " (got " + data.length + ")");
        }
        writeVarInt(out, data.length);
        out.writeBytes(data);
    }

    public static byte[] readByteArray(ByteBuf in, int maxLen) {
        int len = readVarInt(in);
        if (len < 0 || len > maxLen) {
            throw new DecoderException("Byte array length out of range: " + len);
        }
        ensureReadable(in, len);
        byte[] data = new byte[len];
        in.readBytes(data);
        return data;
    }

    // ─── Guards ───────────────────────────────────────────────────────────────

    private static byte readByte(ByteBuf in) {
        ensureReadable(in, 1);
        return in.readByte();
    }

    private static void ensureReadable(ByteBuf in, int n) {
        if (n < 0 || in.readableBytes() < n) {
            throw new DecoderException("Truncated packet: need " + n + ", have " + in.readableBytes());
        }
    }
}
