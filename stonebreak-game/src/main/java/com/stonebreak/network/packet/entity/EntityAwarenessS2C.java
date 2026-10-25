package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.mobs.entities.ai.AwarenessController.AwarenessState;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: a mob's stealth awareness state (#321). Perception runs only on the server's
 * mob; its client shadow never perceives anything, so without this the {@code ?}/{@code !}
 * markers, Shadow Step's unaware check and the flat-footed opener would all read a shadow frozen
 * at UNAWARE. Sent on change, with every spawn, and on the periodic teleport resync (which heals a
 * packet that beat its spawn to the client).
 *
 * <p>Wire: the state's ordinal as one byte. Append new states only, and bump the protocol version.
 */
public record EntityAwarenessS2C(int networkId, AwarenessState state) implements Packet {

    private static final AwarenessState[] STATES = AwarenessState.values();

    public static final PacketCodec<EntityAwarenessS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityAwarenessS2C p) {
            out.writeInt(p.networkId());
            out.writeByte(p.state().ordinal());
        }

        @Override
        public EntityAwarenessS2C decode(ByteBuf in) {
            int id = in.readInt();
            int ordinal = in.readUnsignedByte();
            // An unknown state (a newer server) reads as the safe default.
            return new EntityAwarenessS2C(id, ordinal < STATES.length ? STATES[ordinal] : AwarenessState.UNAWARE);
        }
    };
}
