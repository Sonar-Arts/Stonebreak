package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.SequencedIntent;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the player used the fishing rod in the selected hotbar slot (issue #341).
 * The SERVER decides what that means: with its bobber for this player out, it reels in —
 * rolling a catch when a fish is biting and spawning the drop in the server world — and
 * otherwise casts a new bobber from {@code (x,y,z)} along {@code (dx,dy,dz)}. Either way it
 * flips the rod's state on its own inventory replica and answers with a sync, so the sync
 * never reverts a cast. {@code seq} numbers the request ({@link SequencedIntent}): the client
 * predicts the rod state flip on its mirror.
 */
public record FishingRodC2S(float x, float y, float z, float dx, float dy, float dz, int seq)
        implements Packet, SequencedIntent {

    public static final PacketCodec<FishingRodC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, FishingRodC2S p) {
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.dx());
            out.writeFloat(p.dy());
            out.writeFloat(p.dz());
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public FishingRodC2S decode(ByteBuf in) {
            return new FishingRodC2S(in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat(), ByteBufIO.readVarInt(in));
        }
    };
}
