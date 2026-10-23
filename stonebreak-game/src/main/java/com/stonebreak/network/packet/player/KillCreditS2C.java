package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → attacker: the result of an authoritative hit YOU dealt (melee/ability intent or a
 * server-simulated projectile you launched). Sent per hit that applied damage; a hit the
 * server refused, or one swallowed by the target's invulnerability, sends nothing. Replaces
 * the old host-only local credit — every client, host included, is credited through this
 * uniform path, so remote kills no longer credit nobody (and never mis-credit the host).
 *
 * <p>It is also the attacker's hit feedback (#374/#371): the damage number and hurt sound
 * play from it, melee results drive Rage and lifesteal, and arrow results build the Ranger's
 * Quarry — all keyed off the target's network id.
 *
 * @param entityTypeOrdinal victim's {@code EntityType} ordinal (per-type kill stats)
 * @param damageDealt       effective damage applied after server-side multipliers
 * @param killed            true when the hit was lethal
 * @param xpReward          XP to grant on a kill (0 otherwise)
 * @param targetNetworkId   victim's network id (-1 when it has none)
 * @param sourceOrdinal     {@code LivingEntity.DamageSource} ordinal the server applied
 * @param hitKind           {@code HitKind} wire id of the hit
 */
public record KillCreditS2C(int entityTypeOrdinal, float damageDealt, boolean killed, int xpReward,
                            int targetNetworkId, byte sourceOrdinal, byte hitKind) implements Packet {

    public static final PacketCodec<KillCreditS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, KillCreditS2C p) {
            out.writeInt(p.entityTypeOrdinal());
            out.writeFloat(p.damageDealt());
            out.writeBoolean(p.killed());
            out.writeInt(p.xpReward());
            out.writeInt(p.targetNetworkId());
            out.writeByte(p.sourceOrdinal());
            out.writeByte(p.hitKind());
        }

        @Override
        public KillCreditS2C decode(ByteBuf in) {
            return new KillCreditS2C(in.readInt(), in.readFloat(), in.readBoolean(), in.readInt(),
                    in.readInt(), in.readByte(), in.readByte());
        }
    };
}
