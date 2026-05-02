package com.stonebreak.network.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Wire format: [int32 length][byte type][payload bytes...]
 * Length covers (type + payload), in big-endian.
 */
public final class PacketCodec {

    private static final byte T_HANDSHAKE_C2S    = 1;
    private static final byte T_WELCOME_S2C      = 2;
    private static final byte T_CHUNK_DATA_S2C   = 3;
    private static final byte T_BLOCK_CHANGE_C2S = 4;
    private static final byte T_BLOCK_CHANGE_S2C = 5;
    private static final byte T_PLAYER_STATE_C2S = 6;
    private static final byte T_PLAYER_STATE_S2C = 7;
    private static final byte T_PLAYER_JOIN_S2C  = 8;
    private static final byte T_PLAYER_LEAVE_S2C = 9;
    private static final byte T_DISCONNECT_C2S   = 10;

    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024; // 8 MiB safety cap

    private PacketCodec() {}

    public static void write(DataOutputStream out, Packet packet) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        DataOutputStream body = new DataOutputStream(buf);
        switch (packet) {
            case Packet.HandshakeC2S p -> {
                body.writeByte(T_HANDSHAKE_C2S);
                body.writeUTF(p.username());
            }
            case Packet.WelcomeS2C p -> {
                body.writeByte(T_WELCOME_S2C);
                body.writeInt(p.playerId());
                body.writeLong(p.worldSeed());
                body.writeFloat(p.spawnX());
                body.writeFloat(p.spawnY());
                body.writeFloat(p.spawnZ());
            }
            case Packet.ChunkDataS2C p -> {
                body.writeByte(T_CHUNK_DATA_S2C);
                body.writeInt(p.chunkX());
                body.writeInt(p.chunkZ());
                body.writeInt(p.payload().length);
                body.write(p.payload());
            }
            case Packet.BlockChangeC2S p -> {
                body.writeByte(T_BLOCK_CHANGE_C2S);
                body.writeInt(p.x());
                body.writeInt(p.y());
                body.writeInt(p.z());
                body.writeShort(p.blockTypeId());
            }
            case Packet.BlockChangeS2C p -> {
                body.writeByte(T_BLOCK_CHANGE_S2C);
                body.writeInt(p.x());
                body.writeInt(p.y());
                body.writeInt(p.z());
                body.writeShort(p.blockTypeId());
            }
            case Packet.PlayerStateC2S p -> {
                body.writeByte(T_PLAYER_STATE_C2S);
                body.writeFloat(p.x());
                body.writeFloat(p.y());
                body.writeFloat(p.z());
                body.writeFloat(p.yaw());
                body.writeFloat(p.pitch());
            }
            case Packet.PlayerStateS2C p -> {
                body.writeByte(T_PLAYER_STATE_S2C);
                body.writeInt(p.playerId());
                body.writeFloat(p.x());
                body.writeFloat(p.y());
                body.writeFloat(p.z());
                body.writeFloat(p.yaw());
                body.writeFloat(p.pitch());
            }
            case Packet.PlayerJoinS2C p -> {
                body.writeByte(T_PLAYER_JOIN_S2C);
                body.writeInt(p.playerId());
                body.writeUTF(p.username());
                body.writeFloat(p.x());
                body.writeFloat(p.y());
                body.writeFloat(p.z());
            }
            case Packet.PlayerLeaveS2C p -> {
                body.writeByte(T_PLAYER_LEAVE_S2C);
                body.writeInt(p.playerId());
            }
            case Packet.DisconnectC2S p -> {
                body.writeByte(T_DISCONNECT_C2S);
                body.writeUTF(p.reason());
            }
        }
        body.flush();
        byte[] bytes = buf.toByteArray();
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    public static Packet read(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + len);
        }
        byte[] frame = new byte[len];
        in.readFully(frame);
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(frame));
        byte type = body.readByte();
        try {
            return switch (type) {
                case T_HANDSHAKE_C2S    -> new Packet.HandshakeC2S(body.readUTF());
                case T_WELCOME_S2C      -> new Packet.WelcomeS2C(
                        body.readInt(), body.readLong(),
                        body.readFloat(), body.readFloat(), body.readFloat());
                case T_CHUNK_DATA_S2C   -> {
                    int cx = body.readInt();
                    int cz = body.readInt();
                    int payloadLen = body.readInt();
                    if (payloadLen < 0 || payloadLen > MAX_FRAME_BYTES) {
                        throw new IOException("Invalid chunk payload length: " + payloadLen);
                    }
                    byte[] payload = new byte[payloadLen];
                    body.readFully(payload);
                    yield new Packet.ChunkDataS2C(cx, cz, payload);
                }
                case T_BLOCK_CHANGE_C2S -> new Packet.BlockChangeC2S(
                        body.readInt(), body.readInt(), body.readInt(), body.readShort());
                case T_BLOCK_CHANGE_S2C -> new Packet.BlockChangeS2C(
                        body.readInt(), body.readInt(), body.readInt(), body.readShort());
                case T_PLAYER_STATE_C2S -> new Packet.PlayerStateC2S(
                        body.readFloat(), body.readFloat(), body.readFloat(),
                        body.readFloat(), body.readFloat());
                case T_PLAYER_STATE_S2C -> new Packet.PlayerStateS2C(
                        body.readInt(),
                        body.readFloat(), body.readFloat(), body.readFloat(),
                        body.readFloat(), body.readFloat());
                case T_PLAYER_JOIN_S2C  -> new Packet.PlayerJoinS2C(
                        body.readInt(), body.readUTF(),
                        body.readFloat(), body.readFloat(), body.readFloat());
                case T_PLAYER_LEAVE_S2C -> new Packet.PlayerLeaveS2C(body.readInt());
                case T_DISCONNECT_C2S   -> new Packet.DisconnectC2S(body.readUTF());
                default -> throw new IOException("Unknown packet type: " + type);
            };
        } catch (EOFException eof) {
            throw new IOException("Truncated packet (type=" + type + ")", eof);
        }
    }
}
