package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → player: credit for a statistic the SERVER decided (issue #180). Crafting and fishing
 * catches resolve server-side, but the statistics live on the client's {@code PlayerStats} and
 * reach the server through the monotone prefs report — so, like {@link KillCreditS2C} for kills,
 * the server tells the player what to count.
 *
 * @param stat   a {@code STAT_*} constant
 * @param amount how much to add (positive)
 */
public record StatCreditS2C(byte stat, int amount) implements Packet {

    /** Items taken out of a crafting grid (the recipe output count, per craft). */
    public static final byte STAT_ITEMS_CRAFTED = 0;
    /** Bass reeled in. */
    public static final byte STAT_BASS_CAUGHT = 1;

    public static final PacketCodec<StatCreditS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, StatCreditS2C p) {
            out.writeByte(p.stat());
            out.writeInt(p.amount());
        }

        @Override
        public StatCreditS2C decode(ByteBuf in) {
            return new StatCreditS2C(in.readByte(), in.readInt());
        }
    };
}
