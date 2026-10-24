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
 * @param typeOrdinal     ordinal of {@code DamageType}, resisted per target type (server range-checks it)
 * @param hitKind         {@code HitKind} wire id (MELEE / ABILITY; PROJECTILE is refused). A MELEE
 *                        hit keys the target's invulnerability window on the item the SERVER holds
 *                        for this player, so only that weapon is blocked
 */
public record EntityDamageC2S(int targetNetworkId, float amount, byte sourceOrdinal, byte typeOrdinal,
                              byte hitKind)
        implements Packet {

    public static final PacketCodec<EntityDamageC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityDamageC2S p) {
            out.writeInt(p.targetNetworkId());
            out.writeFloat(p.amount());
            out.writeByte(p.sourceOrdinal());
            out.writeByte(p.typeOrdinal());
            out.writeByte(p.hitKind());
        }

        @Override
        public EntityDamageC2S decode(ByteBuf in) {
            return new EntityDamageC2S(in.readInt(), in.readFloat(), in.readByte(), in.readByte(),
                    in.readByte());
        }
    };
}
