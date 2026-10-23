package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server chunk flow control, after Minecraft's chunk batching (1.20.2,
 * {@code ServerboundChunkBatchReceivedPacket}): {@code processed} chunk snapshots were taken
 * off the client's queue (installed, or dropped as superseded / out of range / undecodable)
 * since the last ack, and {@code desiredChunksPerTick} is how many it can install per 50 ms
 * tick at its measured cost. The server streams at that rate and never has more than a
 * window of unacknowledged snapshots in flight, so a slow or stalled client is never buried.
 */
public record ChunkAckC2S(int processed, float desiredChunksPerTick) implements Packet {

    /** Bounds the reported count (hostile input) — far above any real per-tick volume. */
    public static final int MAX_PROCESSED = 65_536;

    public static final PacketCodec<ChunkAckC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ChunkAckC2S p) {
            out.writeInt(p.processed());
            out.writeFloat(p.desiredChunksPerTick());
        }

        @Override
        public ChunkAckC2S decode(ByteBuf in) {
            int processed = in.readInt();
            float desired = in.readFloat();
            if (processed < 0 || processed > MAX_PROCESSED || !Float.isFinite(desired) || desired < 0f) {
                throw new IllegalArgumentException("Invalid chunk ack: " + processed + " / " + desired);
            }
            return new ChunkAckC2S(processed, desired);
        }
    };
}
