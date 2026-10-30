package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → every in-world client: a player's health as the server holds it (issue #343). Player
 * health is server-owned; clients mirror it. Sent reliably, once per tick at most, whenever the
 * health, the maximum or the life state changed, and to a joining client for everyone already in.
 *
 * <p>The player it names applies it to its own body (and reacts to {@code cause}: combat hits
 * feed the Berserker's Rage); everyone else hides a dead figure until its respawn.
 *
 * @param cause what changed it since the last packet ({@link #CAUSE_NONE} for a heal, a
 *              respawn or a snapshot; otherwise the damage cause of the last hit)
 */
public record PlayerHealthS2C(int playerId, float health, float maxHealth, boolean dead, byte cause)
        implements Packet {

    public static final byte CAUSE_NONE = 0;
    public static final byte CAUSE_FALL = 1;
    public static final byte CAUSE_CACTUS = 2;
    public static final byte CAUSE_MOB = 3;
    public static final byte CAUSE_PLAYER = 4;
    public static final byte CAUSE_SELF = 5;

    /** Whether a hit of {@code cause} came from an attacker (Rage builds, dodge i-frames negate it). */
    public static boolean isCombat(byte cause) {
        return cause == CAUSE_MOB || cause == CAUSE_PLAYER;
    }

    public static final PacketCodec<PlayerHealthS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerHealthS2C p) {
            out.writeInt(p.playerId());
            out.writeFloat(p.health());
            out.writeFloat(p.maxHealth());
            out.writeBoolean(p.dead());
            out.writeByte(p.cause());
        }

        @Override
        public PlayerHealthS2C decode(ByteBuf in) {
            return new PlayerHealthS2C(in.readInt(), in.readFloat(), in.readFloat(), in.readBoolean(),
                    in.readByte());
        }
    };
}
