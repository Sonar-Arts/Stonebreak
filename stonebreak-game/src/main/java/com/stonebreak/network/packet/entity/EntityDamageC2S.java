package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.mobs.entities.Knockback;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the local player dealt damage to a replicated entity. The server is
 * authoritative — it validates range/amount and applies the damage to the real entity;
 * the client never mutates its shadow's health directly.
 *
 * @param targetNetworkId network id of the entity that was hit
 * @param amount          damage the client computed (melee/ability/projectile); server-clamped
 * @param sourceOrdinal   ordinal of {@code LivingEntity.DamageSource} (server allow-lists it)
 * @param typeOrdinal     ordinal of {@code DamageType}, resisted per target type (server range-checks it)
 * @param melee           a swing of the held item: the server keys the target's invulnerability
 *                        window on the item IT holds for this player, so only that weapon is blocked
 * @param knockback       extra knockback applied when the hit lands (server-clamped), or null for none
 */
public record EntityDamageC2S(int targetNetworkId, float amount, byte sourceOrdinal, byte typeOrdinal,
                              boolean melee, Knockback knockback)
        implements Packet {

    public static final PacketCodec<EntityDamageC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityDamageC2S p) {
            out.writeInt(p.targetNetworkId());
            out.writeFloat(p.amount());
            out.writeByte(p.sourceOrdinal());
            out.writeByte(p.typeOrdinal());
            out.writeBoolean(p.melee());
            Knockback kb = p.knockback();
            out.writeBoolean(kb != null);
            if (kb != null) {
                out.writeFloat(kb.dirX());
                out.writeFloat(kb.dirZ());
                out.writeFloat(kb.horizontal());
                out.writeFloat(kb.vertical());
            }
        }

        @Override
        public EntityDamageC2S decode(ByteBuf in) {
            int target = in.readInt();
            float amount = in.readFloat();
            byte source = in.readByte();
            byte type = in.readByte();
            boolean melee = in.readBoolean();
            Knockback kb = in.readBoolean()
                ? new Knockback(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat())
                : null;
            return new EntityDamageC2S(target, amount, source, type, melee, kb);
        }
    };
}
