package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the authoritative appearance variant of a replicated entity
 * (e.g. a sheep's {@code "Sheared"} SBE variant after shearing). The spawn
 * snapshot already carries the current variant in its
 * {@link EntitySpawnS2C} metadata; this packet replicates mid-session changes
 * so every client's shadow swaps without a despawn/respawn round trip.
 * Unknown variants fall back to the base model client-side (idempotent).
 */
public record EntityVariantS2C(int networkId, String variant) implements Packet {

    public static final PacketCodec<EntityVariantS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityVariantS2C p) {
            out.writeInt(p.networkId());
            ByteBufIO.writeString(out, p.variant(), ByteBufIO.MAX_METADATA_CHARS);
        }

        @Override
        public EntityVariantS2C decode(ByteBuf in) {
            return new EntityVariantS2C(in.readInt(), ByteBufIO.readString(in, ByteBufIO.MAX_METADATA_CHARS));
        }
    };
}
