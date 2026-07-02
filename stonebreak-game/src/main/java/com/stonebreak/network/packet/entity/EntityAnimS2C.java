package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the authoritative animation/behavior state of a replicated entity, as the
 * SBE state name (e.g. {@code "Idle"}, {@code "Walking"}, {@code "Grazing"}, {@code "Wingflap"}).
 *
 * <p>In the two-world model the client renders mobs as network shadows that never run AI, so
 * their behavior state would otherwise be frozen at the constructor default. The server sends
 * this whenever a tracked entity's state changes (and once per entity on spawn / peer join);
 * the client maps it back onto the shadow's AI so the renderer, debug wireframe, and debug
 * overlay all reflect the real state. Position is replicated separately via
 * {@link EntityMoveS2C} / {@link EntityTeleportS2C}.
 *
 * <p>FUTURE (animation mixing): this packet carries only the single BASE state. When mob
 * clips gain authored OVERLAY layers ({@code AnimLayerMeta} in the .omanim manifest, e.g. a
 * wing-flap over walking), replicating them minimally means appending an optional overlay
 * state string + active flag here (bump {@code ProtocolVersion}, mirror in
 * {@code ServerEntityHandler}'s anim packet emission and {@code ClientWorldView}'s apply
 * path, feeding an {@code OverlayAnimState} on the shadow). Remote-player attack overlays
 * are a separate gap: attack was never replicated at all — that needs a new
 * {@code PlayerActionS2C(networkId, overlayState, active)} packet driving an
 * {@code OverlayAnimState} on {@code RemotePlayer}.
 */
public record EntityAnimS2C(int networkId, String state) implements Packet {

    public static final PacketCodec<EntityAnimS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityAnimS2C p) {
            out.writeInt(p.networkId());
            ByteBufIO.writeString(out, p.state(), ByteBufIO.MAX_METADATA_CHARS);
        }

        @Override
        public EntityAnimS2C decode(ByteBuf in) {
            return new EntityAnimS2C(
                in.readInt(),
                ByteBufIO.readString(in, ByteBufIO.MAX_METADATA_CHARS));
        }
    };
}
