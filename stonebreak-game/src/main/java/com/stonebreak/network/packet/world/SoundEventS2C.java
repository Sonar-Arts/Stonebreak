package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → nearby clients: play a data-driven sound event at a world position (issue #344) — the
 * sounds no other replicated state implies: another player's block break/place/mining hits and a
 * mob's hurt/death voice. The sample is resolved on the client from the subject's own asset
 * ({@code sounds[]} of the block's SBO or the entity type's SBE), so the wire names only the
 * subject and the event; an event the asset does not bind plays nothing.
 *
 * <p>Never sent to the player who caused it: their client already played it when it predicted the
 * action. Droppable — a lost sound is just not heard.
 *
 * @param subject   {@link #BLOCK} or {@link #ENTITY}; anything else is ignored
 * @param subjectId block id for {@link #BLOCK}, {@code EntityType} ordinal for {@link #ENTITY}
 * @param event     event name ({@code break}/{@code hit}/{@code place}, {@code hurt}/{@code death}, ...)
 */
public record SoundEventS2C(byte subject, int subjectId, String event, float x, float y, float z)
        implements Packet {

    public static final byte BLOCK = 0;
    public static final byte ENTITY = 1;

    /** Longest event name on the wire; the standard ones are a few characters. */
    public static final int MAX_EVENT_CHARS = 32;

    public static SoundEventS2C block(int blockId, String event, float x, float y, float z) {
        return new SoundEventS2C(BLOCK, blockId, event, x, y, z);
    }

    public static SoundEventS2C entity(int entityTypeOrdinal, String event, float x, float y, float z) {
        return new SoundEventS2C(ENTITY, entityTypeOrdinal, event, x, y, z);
    }

    public static final PacketCodec<SoundEventS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, SoundEventS2C p) {
            out.writeByte(p.subject());
            ByteBufIO.writeVarInt(out, p.subjectId());
            ByteBufIO.writeString(out, p.event(), MAX_EVENT_CHARS);
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
        }

        @Override
        public SoundEventS2C decode(ByteBuf in) {
            return new SoundEventS2C(in.readByte(), ByteBufIO.readVarInt(in),
                    ByteBufIO.readString(in, MAX_EVENT_CHARS),
                    in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
