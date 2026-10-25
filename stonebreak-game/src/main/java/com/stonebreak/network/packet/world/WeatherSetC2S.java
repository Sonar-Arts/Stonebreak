package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.weather.WeatherMode;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the /weather command. Like {@code TimeSetC2S}, the server
 * validates (host-only), applies, persists and broadcasts an immediate
 * {@code WeatherSyncS2C}. A local set holds the region around the sender at
 * {@code mode} (AUTO lifts the zones there); a world set changes the weather
 * everywhere and lifts every zone.
 *
 * @param mode  the weather to set
 * @param world true for the whole world, false for the region around the sender
 */
public record WeatherSetC2S(WeatherMode mode, boolean world) implements Packet {

    public static final PacketCodec<WeatherSetC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WeatherSetC2S p) {
            out.writeByte(p.mode().ordinal());
            out.writeBoolean(p.world());
        }

        @Override
        public WeatherSetC2S decode(ByteBuf in) {
            return new WeatherSetC2S(WeatherMode.byId(in.readUnsignedByte()), in.readBoolean());
        }
    };
}
