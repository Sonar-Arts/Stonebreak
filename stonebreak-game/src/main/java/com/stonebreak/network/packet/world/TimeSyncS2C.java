package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client authoritative world-time sample. Sent non-droppably right after
 * {@code WelcomeS2C} (so a joining client starts at the server's actual time-of-day
 * instead of a NOON default), then droppably every ~5 s so each client's locally-ticking
 * clock converges on the server clock instead of drifting.
 *
 * <p>The client clock free-runs between samples; {@code TimeOfDay.nudgeTo} snaps on large
 * error and converges gently on small error so the sky never visibly jumps.
 *
 * @param worldTimeTicks the server's authoritative {@code TimeOfDay} tick counter
 * @param timeSpeed      time-scale multiplier the client should tick at (1.0 = normal)
 * @param frozen         true when the server clock is paused (client stops ticking too)
 */
public record TimeSyncS2C(long worldTimeTicks, float timeSpeed, boolean frozen) implements Packet {

    public static final PacketCodec<TimeSyncS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, TimeSyncS2C p) {
            out.writeLong(p.worldTimeTicks());
            out.writeFloat(p.timeSpeed());
            out.writeBoolean(p.frozen());
        }

        @Override
        public TimeSyncS2C decode(ByteBuf in) {
            return new TimeSyncS2C(in.readLong(), in.readFloat(), in.readBoolean());
        }
    };
}
