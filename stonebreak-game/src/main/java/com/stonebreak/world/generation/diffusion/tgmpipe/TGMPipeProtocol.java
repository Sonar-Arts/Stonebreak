package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TerrainTile;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * TGMPipe's wire format: length-prefixed binary frames over the service process's
 * stdin/stdout. Mirror of {@code Models/DaedalusTGM-Exp/terrain_slm/tgmpipe/protocol.py} (the full
 * contract is documented there) — change both together and bump {@link #VERSION}.
 *
 * <p>Frame: {@code u32 length} (of everything after it), {@code u8 type}, body; little-endian.
 */
public final class TGMPipeProtocol {

    public static final int VERSION = 1;
    /** int16 planes per tile: block height, biome, water level, tunnel floor, tunnel roof, flow. */
    public static final int PLANES = 6;

    // Game -> service
    public static final int HELLO = 0x01;
    public static final int TILE = 0x02;
    public static final int CANCEL = 0x03;
    public static final int STATUS = 0x04;
    // Service -> game
    public static final int READY = 0x81;
    public static final int TILE_DATA = 0x82;
    public static final int TILE_ERROR = 0x83;
    public static final int STATUS_REPLY = 0x84;
    public static final int FATAL = 0x8F;

    static final int FLAG_CACHED = 0x01;
    static final int MAX_FRAME = 64 << 20;
    /** {@code u32 id, u8 flags, u16 rows, u16 cols, i32 i1, i32 j1}. */
    static final int TILE_HEAD_BYTES = 4 + 1 + 2 + 2 + 4 + 4;

    private TGMPipeProtocol() {}

    /** One decoded frame; {@code body} is little-endian and positioned at its start. */
    public record Frame(int type, ByteBuffer body) {
        public String text() {
            return StandardCharsets.UTF_8.decode(body.duplicate()).toString();
        }
    }

    /** Reads one frame, or returns null at a clean end of stream (the service exited). */
    public static Frame read(InputStream in) throws IOException {
        byte[] head = new byte[4];
        int first = in.read(head, 0, 4);
        if (first < 0) {
            return null;
        }
        readFully(in, head, first, 4 - first);
        int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 1 || length > MAX_FRAME) {
            throw new IOException("bad TGMPipe frame length " + Integer.toUnsignedString(length));
        }
        byte[] frame = new byte[length];
        readFully(in, frame, 0, length);
        ByteBuffer body = ByteBuffer.wrap(frame, 1, length - 1).slice().order(ByteOrder.LITTLE_ENDIAN);
        return new Frame(frame[0] & 0xFF, body);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(buf, off, len);
            if (n < 0) {
                throw new EOFException("TGMPipe stream ended mid-frame");
            }
            off += n;
            len -= n;
        }
    }

    // ───────────────────────────────────────────────────────── game -> service

    public static byte[] hello(String json) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        return frame(HELLO, 2 + text.length).putShort((short) VERSION).put(text).array();
    }

    public static byte[] tile(int id, long seed, int tileX, int tileZ, int lod, int priority) {
        return frame(TILE, 4 + 8 + 4 + 4 + 1 + 1)
                .putInt(id).putLong(seed).putInt(tileX).putInt(tileZ)
                .put((byte) lod).put((byte) priority).array();
    }

    public static byte[] cancel(int id) {
        return frame(CANCEL, 4).putInt(id).array();
    }

    public static byte[] status(int token) {
        return frame(STATUS, 4).putInt(token).array();
    }

    private static ByteBuffer frame(int type, int bodyBytes) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + bodyBytes).order(ByteOrder.LITTLE_ENDIAN);
        return buf.putInt(1 + bodyBytes).put((byte) type);
    }

    // ───────────────────────────────────────────────────────── service -> game

    /** Request id of a TILE_DATA, TILE_ERROR or STATUS_REPLY body, without consuming it. */
    public static int requestId(Frame frame) {
        return frame.body().getInt(0);
    }

    /** The message after the request id of a TILE_ERROR or STATUS_REPLY. */
    public static String textAfterId(Frame frame) {
        ByteBuffer rest = frame.body().duplicate().position(4);
        return StandardCharsets.UTF_8.decode(rest).toString();
    }

    /**
     * Decodes a TILE_DATA body into the tile it answers. Rows are the i axis (world X), columns
     * the j axis (world Z) — see {@link TerrainTile}. Tiles without tunnels or flow keep those
     * planes null rather than full of sentinels.
     */
    public static TerrainTile decodeTile(Frame frame, int tileX, int tileZ) {
        ByteBuffer buf = frame.body().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // request id
        buf.get();    // flags
        int rows = Short.toUnsignedInt(buf.getShort());
        int cols = Short.toUnsignedInt(buf.getShort());
        int i1 = buf.getInt();
        int j1 = buf.getInt();
        int cells = rows * cols;
        if (buf.remaining() != cells * 2 * PLANES) {
            throw new IllegalStateException("tile body holds " + buf.remaining() + " bytes, expected "
                    + PLANES + " planes of " + rows + "x" + cols);
        }
        short[][] planes = new short[PLANES][cells];
        var shorts = buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        for (short[] plane : planes) {
            shorts.get(plane);
        }
        boolean anyTunnel = !allEqual(planes[3], TerrainTile.NO_TUNNEL);
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + rows, j1 + cols, cols, rows,
                planes[0], planes[1], planes[2],
                anyTunnel ? planes[3] : null,
                anyTunnel ? planes[4] : null,
                allEqual(planes[5], TerrainTile.NO_FLOW) ? null : planes[5]);
    }

    public static boolean fromDiskCache(Frame frame) {
        return (frame.body().get(4) & FLAG_CACHED) != 0;
    }

    private static boolean allEqual(short[] plane, short value) {
        for (short v : plane) {
            if (v != value) {
                return false;
            }
        }
        return true;
    }
}
