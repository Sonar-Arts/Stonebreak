package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: one of the local player's abilities put a status effect (stun, root,
 * bleed, ...) on a replicated entity. Applying it to the client's shadow did nothing — a
 * shadow never ticks, so the effect neither expired nor reached the real mob (#339). The
 * server applies it to the real entity after its own checks (effect allowed from clients,
 * the player's class, range from the server's position, clamped duration/magnitude, rate);
 * the result reaches every client through {@code EntityStatusS2C}.
 *
 * @param effectId  {@code StatusEffectBits.wireId} of the effect
 * @param duration  seconds (server-clamped per effect)
 * @param magnitude effect strength (server-clamped per effect)
 */
public record EntityStatusEffectC2S(int targetNetworkId, byte effectId, float duration, float magnitude)
        implements Packet {

    public static final PacketCodec<EntityStatusEffectC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityStatusEffectC2S p) {
            out.writeInt(p.targetNetworkId());
            out.writeByte(p.effectId());
            out.writeFloat(p.duration());
            out.writeFloat(p.magnitude());
        }

        @Override
        public EntityStatusEffectC2S decode(ByteBuf in) {
            return new EntityStatusEffectC2S(in.readInt(), in.readByte(), in.readFloat(), in.readFloat());
        }
    };
}
