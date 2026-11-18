package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: a batch of cube terrain for the client's REMOTE stream (cubic voxel runtime, #447).
 *
 * <ul>
 *   <li>{@link Column}s: {@code CubeWorld.encodeColumn} records (surface tracker + generated runs) — they come
 *       FIRST, because a REMOTE world has no generator surface callback: the column record is what tells the
 *       client which cells are KNOWN AIR (above the column's top) and where the surface band is.</li>
 *   <li>{@link CubeRecord}s: {@code CubeWorld.encode(PARTS_NETWORK)} bytes (blocks + every NETWORK layer: water,
 *       block-state ids, snow; no blob) plus the cube's PAYLOAD states (furnace contents, crafting grids — the
 *       full strings the state layer only holds a variant id for). State ids in the bytes are the SERVER's
 *       {@code BlockStateRegistry} ids, mirrored by {@link BlockStateNamesS2C} before any cube that uses them.</li>
 * </ul>
 *
 * <p>Over TCP the whole body is one {@link WireCompression} blob (zstd/deflate per the client's handshake mask;
 * raw when small - a uniform air or stone cube is ~30 bytes and is sent as is). A LOCAL (singleplayer) channel
 * passes the packet object and never encodes it.
 */
public record CubeDataS2C(List<Column> columns, List<CubeRecord> cubes) implements Packet {

    /** Most raw body bytes one batch may carry (the server targets far less, see ServerChunkHandler). */
    public static final int MAX_RAW_BYTES = 4 * 1024 * 1024;
    public static final int MAX_COLUMNS = 4096;
    public static final int MAX_CUBES = 4096;
    /** One cube record: blocks (8 x 8 KiB direct) + 8 layers at most; far above any real record. */
    public static final int MAX_RECORD_BYTES = 1024 * 1024;
    public static final int MAX_STATES = 32 * 32 * 32;
    public static final int MAX_STATE_CHARS = 4096;

    /** A column record of column cube (cx, cz). */
    public record Column(long cx, long cz, byte[] bytes) {
    }

    /** One cube: engine network bytes + payload states ({@code stateCells[i]} = cube cell index). */
    public record CubeRecord(long cx, long cy, long cz, byte[] bytes, int[] stateCells, String[] stateValues) {
        public CubeRecord {
            if (stateCells.length != stateValues.length) {
                throw new IllegalArgumentException("state cells/values differ in length");
            }
        }

        public int payloadBytes() {
            int n = bytes.length + 32;
            for (String s : stateValues) {
                n += s.length() + 4;
            }
            return n;
        }
    }

    public CubeDataS2C {
        columns = List.copyOf(columns);
        cubes = List.copyOf(cubes);
    }

    /** Approximate raw body size (pacing). */
    public int rawSize() {
        int n = 8;
        for (Column c : columns) n += c.bytes().length + 24;
        for (CubeRecord r : cubes) n += r.payloadBytes();
        return n;
    }

    public static final PacketCodec<CubeDataS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CubeDataS2C p) {
            encode(out, p, 0);
        }

        @Override
        public void encode(ByteBuf out, CubeDataS2C p, int peerCompression) {
            byte[] raw = body(p);
            WireCompression.writeBlob(out, raw, MAX_RAW_BYTES, peerCompression);
        }

        @Override
        public CubeDataS2C decode(ByteBuf in) {
            byte[] raw = WireCompression.readBlob(in, MAX_RAW_BYTES);
            ByteBuf b = Unpooled.wrappedBuffer(raw);
            CubeDataS2C p = parse(b);
            if (b.isReadable()) {
                throw new IllegalArgumentException("Trailing bytes in cube batch");
            }
            return p;
        }
    };

    static byte[] body(CubeDataS2C p) {
        ByteBuf b = Unpooled.buffer(Math.max(64, p.rawSize()));
        try {
            NetCoords.writeCount(b, p.columns().size());
            for (Column c : p.columns()) {
                NetCoords.writeVarLong(b, c.cx());
                NetCoords.writeVarLong(b, c.cz());
                ByteBufIO.writeByteArray(b, c.bytes(), MAX_RECORD_BYTES);
            }
            NetCoords.writeCount(b, p.cubes().size());
            for (CubeRecord r : p.cubes()) {
                NetCoords.writeBlock(b, r.cx(), r.cy(), r.cz());
                ByteBufIO.writeByteArray(b, r.bytes(), MAX_RECORD_BYTES);
                NetCoords.writeCount(b, r.stateCells().length);
                for (int i = 0; i < r.stateCells().length; i++) {
                    NetCoords.writeCount(b, r.stateCells()[i]);
                    ByteBufIO.writeString(b, r.stateValues()[i], MAX_STATE_CHARS);
                }
            }
            if (b.readableBytes() > MAX_RAW_BYTES) {
                throw new IllegalStateException("Cube batch exceeds " + MAX_RAW_BYTES + " bytes");
            }
            byte[] raw = new byte[b.readableBytes()];
            b.readBytes(raw);
            return raw;
        } finally {
            b.release();
        }
    }

    static CubeDataS2C parse(ByteBuf b) {
        int nc = NetCoords.readCount(b, MAX_COLUMNS, "column");
        List<Column> columns = new ArrayList<>(nc);
        for (int i = 0; i < nc; i++) {
            long cx = NetCoords.readVarLong(b);
            long cz = NetCoords.readVarLong(b);
            columns.add(new Column(cx, cz, ByteBufIO.readByteArray(b, MAX_RECORD_BYTES)));
        }
        int n = NetCoords.readCount(b, MAX_CUBES, "cube");
        List<CubeRecord> cubes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long cx = NetCoords.readVarLong(b);
            long cy = NetCoords.readVarLong(b);
            long cz = NetCoords.readVarLong(b);
            byte[] bytes = ByteBufIO.readByteArray(b, MAX_RECORD_BYTES);
            int ns = NetCoords.readCount(b, MAX_STATES, "payload state");
            int[] cells = new int[ns];
            String[] values = new String[ns];
            for (int k = 0; k < ns; k++) {
                cells[k] = NetCoords.readCount(b, MAX_STATES - 1, "state cell");
                values[k] = ByteBufIO.readString(b, MAX_STATE_CHARS);
            }
            cubes.add(new CubeRecord(cx, cy, cz, bytes, cells, values));
        }
        return new CubeDataS2C(columns, cubes);
    }
}
