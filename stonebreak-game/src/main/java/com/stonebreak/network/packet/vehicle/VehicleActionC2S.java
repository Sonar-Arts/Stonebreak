package com.stonebreak.network.packet.vehicle;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: one vehicle intent. The server owns every vehicle and its seats; it checks
 * reach and the held item itself.
 * <ul>
 *   <li>{@link #PLACE} — put the held dinghy down at ({@code x, y, z}) with its bow toward
 *       {@code yaw}; debits the server inventory, so it is numbered ({@code seq}) and always
 *       answered with a sync.</li>
 *   <li>{@link #BOARD} — sit in {@code networkId}'s first free seat (the oars first).</li>
 *   <li>{@link #DISMOUNT} — stand up out of whatever the player sits in.</li>
 *   <li>{@link #HIT} — strike {@code networkId}; enough hits break it into its item.</li>
 * </ul>
 */
public record VehicleActionC2S(byte action, int networkId, float x, float y, float z, float yaw, int seq)
        implements Packet, com.stonebreak.network.packet.SequencedIntent {

    public static final byte PLACE = 0;
    public static final byte BOARD = 1;
    public static final byte DISMOUNT = 2;
    public static final byte HIT = 3;

    public static VehicleActionC2S place(float x, float y, float z, float yaw, int seq) {
        return new VehicleActionC2S(PLACE, -1, x, y, z, yaw, seq);
    }

    public static VehicleActionC2S on(byte action, int networkId) {
        return new VehicleActionC2S(action, networkId, 0f, 0f, 0f, 0f, 0);
    }

    public static final PacketCodec<VehicleActionC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, VehicleActionC2S p) {
            out.writeByte(p.action());
            out.writeInt(p.networkId());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public VehicleActionC2S decode(ByteBuf in) {
            return new VehicleActionC2S(in.readByte(), in.readInt(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), ByteBufIO.readVarInt(in));
        }
    };
}
