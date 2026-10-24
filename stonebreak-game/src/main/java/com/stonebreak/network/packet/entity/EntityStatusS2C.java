package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the mob state class mechanics read off a shadow (#371) — its health
 * fraction and the set of active status effects ({@code StatusEffectBits}). Sent after the
 * spawn when not at the default (full health, no effects) and then only on change.
 *
 * <p>Wire: the fraction is quantized to an unsigned 16-bit value ({@link #quantize}); the
 * bitset is an unsigned 16-bit field ({@code StatusEffectBits.WIRE_BITS}).
 *
 * @param healthFraction health / maxHealth, 0..1
 * @param statusBits     bit {@code StatusEffectBits.wireId(type)} set per active effect
 */
public record EntityStatusS2C(int networkId, float healthFraction, int statusBits) implements Packet {

    private static final int HEALTH_STEPS = 0xFFFF;

    /** The wire value of a health fraction (clamped to 0..1; NaN reads as 0). */
    public static int quantize(float fraction) {
        if (!(fraction > 0f)) {
            return 0;
        }
        return Math.round(Math.min(1f, fraction) * HEALTH_STEPS);
    }

    /** The fraction a wire value stands for. */
    public static float dequantize(int quantized) {
        return (quantized & HEALTH_STEPS) / (float) HEALTH_STEPS;
    }

    public static final PacketCodec<EntityStatusS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityStatusS2C p) {
            out.writeInt(p.networkId());
            out.writeShort(quantize(p.healthFraction()));
            out.writeShort(p.statusBits() & 0xFFFF);
        }

        @Override
        public EntityStatusS2C decode(ByteBuf in) {
            return new EntityStatusS2C(in.readInt(), dequantize(in.readUnsignedShort()),
                    in.readUnsignedShort());
        }
    };
}
