package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: a mob hurt this player — a goblin's swing landing on its impact beat, or a
 * goblin's arrow. The server decides the hit (it owns the mobs); the player's health lives on
 * their own client, which applies it through the ordinary damage path, so dodge i-frames, Rage
 * and the stealth break all hold. A resulting death flows back as {@code PlayerLifeC2S}.
 *
 * @param amount     damage to apply
 * @param knockbackX horizontal shove, blocks per second along X (0 for none)
 * @param knockbackZ horizontal shove, blocks per second along Z
 */
public record PlayerHurtS2C(float amount, float knockbackX, float knockbackZ) implements Packet {

    public static final PacketCodec<PlayerHurtS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerHurtS2C p) {
            out.writeFloat(p.amount());
            out.writeFloat(p.knockbackX());
            out.writeFloat(p.knockbackZ());
        }

        @Override
        public PlayerHurtS2C decode(ByteBuf in) {
            return new PlayerHurtS2C(in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
