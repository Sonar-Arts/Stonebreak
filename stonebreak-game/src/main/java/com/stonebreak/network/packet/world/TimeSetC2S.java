package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: set the authoritative world time (the /timeset command). The day/night
 * clock is server-owned — a client mutating only its display clock gets snapped back by the
 * next {@code TimeSyncS2C} — so the command must route here. The server validates (host-only
 * for now), applies to {@code ServerLevel.timeOfDay()}, and broadcasts an immediate
 * {@code TimeSyncS2C} so every client snaps to the new time at once.
 */
public record TimeSetC2S(long ticks) implements Packet {

    public static final PacketCodec<TimeSetC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, TimeSetC2S p) {
            out.writeLong(p.ticks());
        }

        @Override
        public TimeSetC2S decode(ByteBuf in) {
            return new TimeSetC2S(in.readLong());
        }
    };
}
