package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server cube flow control (cubic voxel runtime, #447; successor of the column {@code ChunkAckC2S},
 * after Minecraft's chunk batching): {@code processedCubes} cube records ({@code processedKiB} KiB of raw
 * record bytes) left the client's install queue since the last ack, and {@code desiredCubesPerTick} is how
 * many it can install per 50 ms tick at its measured cost. The server streams at that rate and keeps at most a
 * window of unacknowledged cubes AND bytes in flight — cube payloads range from ~30 bytes (uniform) to tens of
 * KiB (surface terrain), so a count alone would not bound the wire.
 */
public record CubeAckC2S(int processedCubes, int processedKiB, float desiredCubesPerTick) implements Packet {

    /** Bounds the reported counts (hostile input) — far above any real per-tick volume. */
    public static final int MAX_PROCESSED = 1 << 20;

    public static final PacketCodec<CubeAckC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CubeAckC2S p) {
            out.writeInt(p.processedCubes());
            out.writeInt(p.processedKiB());
            out.writeFloat(p.desiredCubesPerTick());
        }

        @Override
        public CubeAckC2S decode(ByteBuf in) {
            int cubes = in.readInt();
            int kib = in.readInt();
            float desired = in.readFloat();
            if (cubes < 0 || cubes > MAX_PROCESSED || kib < 0 || kib > MAX_PROCESSED
                    || !Float.isFinite(desired) || desired < 0f) {
                throw new IllegalArgumentException("Invalid cube ack: " + cubes + " / " + kib + " / " + desired);
            }
            return new CubeAckC2S(cubes, kib, desired);
        }
    };
}
