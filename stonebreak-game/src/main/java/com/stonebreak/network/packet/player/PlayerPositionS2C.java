package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: put the local player at this position (issue #336). Sent when the server
 * refused a reported move — a jump further than the player could have travelled — so the
 * client returns to where the server holds it instead of drifting out of sync.
 */
public record PlayerPositionS2C(float x, float y, float z) implements Packet {

    public static final PacketCodec<PlayerPositionS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerPositionS2C p) {
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
        }

        @Override
        public PlayerPositionS2C decode(ByteBuf in) {
            return new PlayerPositionS2C(in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
