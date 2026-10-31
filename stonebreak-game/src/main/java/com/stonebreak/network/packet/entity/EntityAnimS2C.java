package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the authoritative animation/behavior state of a replicated entity, as the
 * SBE state name (e.g. {@code "Idle"}, {@code "Walking"}, {@code "Grazing"}, {@code "Wingflap"}).
 * Known names cross the wire as a one-byte code ({@link #KNOWN_STATES}).
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

    /**
     * States sent as their index in this table — one byte — instead of a string (issue #346):
     * every SBE clip name {@code MobStateMapping} produces, then the non-mob visual states
     * (fire-bolt impact, fishing-bobber phases). Anything else (a dinghy's seat/oar string)
     * goes as {@link #STRING_TAG} plus the string. Append only, and bump the protocol version.
     */
    static final String[] KNOWN_STATES = {
        "Idle", "Walking", "Grazing", "Wingflap", "Stab", "Smash", "DrawBow", "ReleaseBow",
        "idle", "walking", "flying",
        "Impact", "cast", "float", "bite",
        "Float", "PeekLean", "JerkBack", "Woosh", "Cast", "Scratch", "Dissolve",
    };
    /** Tag byte for a state outside {@link #KNOWN_STATES}; a string follows. */
    static final int STRING_TAG = 0xFF;

    private static final java.util.Map<String, Integer> KNOWN_INDEX = indexOf(KNOWN_STATES);

    private static java.util.Map<String, Integer> indexOf(String[] states) {
        java.util.Map<String, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < states.length; i++) {
            index.put(states[i], i);
        }
        return java.util.Map.copyOf(index);
    }

    /** The one-byte code of {@code state}, or -1 when it travels as a string. */
    static int knownIndex(String state) {
        Integer i = state != null ? KNOWN_INDEX.get(state) : null;
        return i != null ? i : -1;
    }

    public static final PacketCodec<EntityAnimS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityAnimS2C p) {
            out.writeInt(p.networkId());
            int known = knownIndex(p.state());
            if (known >= 0) {
                out.writeByte(known);
            } else {
                out.writeByte(STRING_TAG);
                ByteBufIO.writeString(out, p.state(), ByteBufIO.MAX_METADATA_CHARS);
            }
        }

        @Override
        public EntityAnimS2C decode(ByteBuf in) {
            int networkId = in.readInt();
            int tag = in.readUnsignedByte();
            if (tag == STRING_TAG) {
                return new EntityAnimS2C(networkId, ByteBufIO.readString(in, ByteBufIO.MAX_METADATA_CHARS));
            }
            if (tag >= KNOWN_STATES.length) {
                throw new IllegalArgumentException("Unknown animation state code " + tag);
            }
            return new EntityAnimS2C(networkId, KNOWN_STATES[tag]);
        }
    };
}
