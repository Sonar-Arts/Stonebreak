package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.weather.WeatherMode;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: set the world's weather mode (the /weather command). Like
 * {@code TimeSetC2S}, the server validates (host-only), applies, persists and
 * broadcasts an immediate {@code WeatherSyncS2C}.
 */
public record WeatherSetC2S(WeatherMode mode) implements Packet {

    public static final PacketCodec<WeatherSetC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WeatherSetC2S p) {
            out.writeByte(p.mode().ordinal());
        }

        @Override
        public WeatherSetC2S decode(ByteBuf in) {
            return new WeatherSetC2S(WeatherMode.byId(in.readUnsignedByte()));
        }
    };
}
