package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the sender died ({@code dead}) or respawned ({@code !dead}), with where
 * it stands at that edge. Sent once per edge on the reliable channel — never on the
 * droppable 20 Hz state stream — so the server's view of who is dead cannot be lost to a
 * join or chunk burst (#357). The position is the corpse on death and the respawn point on
 * respawn: a respawn that is processed before the next state report must not leave the
 * server holding a live player at the corpse, next to its own death drops.
 */
public record PlayerLifeC2S(boolean dead, float x, float y, float z) implements Packet {

    public static final PacketCodec<PlayerLifeC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerLifeC2S p) {
            out.writeBoolean(p.dead());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
        }

        @Override
        public PlayerLifeC2S decode(ByteBuf in) {
            return new PlayerLifeC2S(in.readBoolean(), in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
