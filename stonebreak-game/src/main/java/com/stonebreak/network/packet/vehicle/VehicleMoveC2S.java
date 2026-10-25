package com.stonebreak.network.packet.vehicle;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server, at the player-state rate: where the boat this player is rowing is, and how
 * hard each oar pulls ({@code OarInput} wire form, half steps). The rower's client simulates its
 * boat the way it simulates its own body; the server refuses a boat reported away from its rower
 * (whose own movement it checks) and replicates the rest (the oars through the boat's visual
 * state). Droppable — the next one supersedes it.
 */
public record VehicleMoveC2S(int networkId, float x, float y, float z, float yaw,
                             byte oarLeft, byte oarRight) implements Packet {

    public static final PacketCodec<VehicleMoveC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, VehicleMoveC2S p) {
            out.writeInt(p.networkId());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
            out.writeByte(p.oarLeft());
            out.writeByte(p.oarRight());
        }

        @Override
        public VehicleMoveC2S decode(ByteBuf in) {
            return new VehicleMoveC2S(in.readInt(), in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readByte(), in.readByte());
        }
    };
}
