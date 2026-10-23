package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the local player dealt damage to a replicated entity. The server is
 * authoritative — it validates the claim by its {@code hitKind} (melee: reach from the
 * server's own position, swing cooldown, held-weapon cap; ability: the class's ability
 * profile) and applies the damage to the real entity; the client never mutates its
 * shadow's health directly. The applied result comes back as {@code KillCreditS2C}.
 *
 * @param targetNetworkId network id of the entity that was hit
 * @param amount          damage the client computed (melee/ability); server-capped
 * @param sourceOrdinal   ordinal of {@code LivingEntity.DamageSource} (server allow-lists it)
 * @param hitKind         {@code HitKind} wire id (MELEE / ABILITY; PROJECTILE is refused)
 */
public record EntityDamageC2S(int targetNetworkId, float amount, byte sourceOrdinal, byte hitKind)
        implements Packet {

    public static final PacketCodec<EntityDamageC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityDamageC2S p) {
            out.writeInt(p.targetNetworkId());
            out.writeFloat(p.amount());
            out.writeByte(p.sourceOrdinal());
            out.writeByte(p.hitKind());
        }

        @Override
        public EntityDamageC2S decode(ByteBuf in) {
            return new EntityDamageC2S(in.readInt(), in.readFloat(), in.readByte(), in.readByte());
        }
    };
}
