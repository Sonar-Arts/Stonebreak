package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → attacker: stat/XP credit for an authoritative hit YOU dealt (melee intent or a
 * server-simulated projectile you launched). Sent per accepted hit; {@code killed} marks
 * lethal ones. Replaces the old host-only local credit — every client, host included, is
 * credited through this uniform path, so remote kills no longer credit nobody (and never
 * mis-credit the host).
 *
 * @param entityTypeOrdinal victim's {@code EntityType} ordinal (per-type kill stats)
 * @param damageDealt       effective damage applied after server-side multipliers
 * @param killed            true when the hit was lethal
 * @param xpReward          XP to grant on a kill (0 otherwise)
 * @param targetNetworkId   victim's network id, for its hurt/death voice on the client shadow
 * @param x                 damage-number anchor (victim head height); carried rather than read
 * @param y                 from the shadow because a lethal hit can despawn it first
 * @param z
 */
public record KillCreditS2C(int entityTypeOrdinal, float damageDealt, boolean killed, int xpReward,
                            int targetNetworkId, float x, float y, float z) implements Packet {

    /** Credit for a hit that landed on {@code victim}, anchored at its head. */
    public static KillCreditS2C forHit(com.stonebreak.mobs.entities.LivingEntity victim,
                                       float dealt, boolean killed) {
        org.joml.Vector3f p = victim.getPosition();
        return new KillCreditS2C(victim.getType().ordinal(), dealt, killed,
            killed ? victim.getXpReward() : 0, victim.getNetworkId(),
            p.x, p.y + victim.getHeight() * 0.9f, p.z);
    }

    public static final PacketCodec<KillCreditS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, KillCreditS2C p) {
            out.writeInt(p.entityTypeOrdinal());
            out.writeFloat(p.damageDealt());
            out.writeBoolean(p.killed());
            out.writeInt(p.xpReward());
            out.writeInt(p.targetNetworkId());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
        }

        @Override
        public KillCreditS2C decode(ByteBuf in) {
            return new KillCreditS2C(in.readInt(), in.readFloat(), in.readBoolean(), in.readInt(),
                in.readInt(), in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
