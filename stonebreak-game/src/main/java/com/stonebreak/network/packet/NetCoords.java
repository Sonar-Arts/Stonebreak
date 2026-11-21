package com.stonebreak.network.packet;

import io.netty.buffer.ByteBuf;

/**
 * 64-bit coordinates on the wire (cubic voxel runtime, #445/#447). Block and cube coordinates are
 * signed {@code long}s written as zigzag LEB128 varints (1 byte near the origin, at most 10); world
 * POSITIONS are an int64 cell plus a float offset inside it, so a position keeps sub-millimetre
 * precision at any distance (a plain float stops resolving centimetres past ~2^17 blocks).
 *
 * <p>Never pack coordinates into one long with bit budgets: every coordinate travels as its own value.
 */
public final class NetCoords {

    private NetCoords() {}

    // ─── varlong (zigzag) ────────────────────────────────────────────────────────

    public static void writeVarLong(ByteBuf out, long value) {
        long v = (value << 1) ^ (value >> 63); // zigzag
        while ((v & ~0x7FL) != 0) {
            out.writeByte((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.writeByte((int) v);
    }

    public static long readVarLong(ByteBuf in) {
        long v = 0;
        int shift = 0;
        while (true) {
            if (shift >= 70) {
                throw new IllegalArgumentException("VarLong too large");
            }
            if (!in.isReadable()) {
                throw new IllegalArgumentException("Truncated VarLong");
            }
            byte b = in.readByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (v >>> 1) ^ -(v & 1);
            }
            shift += 7;
        }
    }

    /** Unsigned varint helpers for counts (same encoding as the engine's ByteBufIO varint). */
    public static void writeCount(ByteBuf out, int count) {
        com.openmason.engine.net.protocol.ByteBufIO.writeVarInt(out, count);
    }

    /** A count in [0, max]; anything else is a malformed packet. */
    public static int readCount(ByteBuf in, int max, String what) {
        int n = com.openmason.engine.net.protocol.ByteBufIO.readVarInt(in);
        if (n < 0 || n > max) {
            throw new IllegalArgumentException("Invalid " + what + " count: " + n);
        }
        return n;
    }

    // ─── block / cube coordinates ───────────────────────────────────────────────

    public static void writeBlock(ByteBuf out, long x, long y, long z) {
        writeVarLong(out, x);
        writeVarLong(out, y);
        writeVarLong(out, z);
    }

    // ─── world positions: int64 cell + float offset ─────────────────────────────

    /** Writes a world position axis as its cell ({@code floor}) and the float offset in [0, 1). */
    public static void writePosition(ByteBuf out, double value) {
        if (!Double.isFinite(value)) {
            value = 0.0;
        }
        double cell = Math.floor(value);
        float offset = (float) (value - cell);
        if (offset >= 1f) { // rounding at the top of the cell
            cell += 1.0;
            offset = 0f;
        }
        writeVarLong(out, (long) cell);
        out.writeFloat(offset);
    }

    /** Reads an axis written by {@link #writePosition}; a non-finite or out-of-cell offset is malformed. */
    public static double readPosition(ByteBuf in) {
        long cell = readVarLong(in);
        float offset = in.readFloat();
        if (!Float.isFinite(offset) || offset < 0f || offset >= 1f) {
            throw new IllegalArgumentException("Invalid position offset: " + offset);
        }
        return (double) cell + offset;
    }

    public static void writePosition(ByteBuf out, double x, double y, double z) {
        writePosition(out, x);
        writePosition(out, y);
        writePosition(out, z);
    }
}
