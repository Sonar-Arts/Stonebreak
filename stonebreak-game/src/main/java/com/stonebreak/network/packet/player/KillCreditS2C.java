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
 */
public record KillCreditS2C(int entityTypeOrdinal, float damageDealt, boolean killed, int xpReward) implements Packet {

    public static final PacketCodec<KillCreditS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, KillCreditS2C p) {
            out.writeInt(p.entityTypeOrdinal());
            out.writeFloat(p.damageDealt());
            out.writeBoolean(p.killed());
            out.writeInt(p.xpReward());
        }

        @Override
        public KillCreditS2C decode(ByteBuf in) {
            return new KillCreditS2C(in.readInt(), in.readFloat(), in.readBoolean(), in.readInt());
        }
    };
}
