package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.weather.WeatherMode;
import io.netty.buffer.ByteBuf;

/**
 * Server → client authoritative weather sample. Sent non-droppably right after
 * {@code WelcomeS2C} and whenever the mode changes, then droppably every ~5 s
 * beside {@code TimeSyncS2C}. Clouds, rain and lightning are all derived from
 * the world seed plus this clock and mode, so nothing else about the weather
 * ever crosses the wire.
 *
 * @param weatherSeconds the server's monotonic weather clock
 * @param mode           the world's weather mode
 */
public record WeatherSyncS2C(double weatherSeconds, WeatherMode mode) implements Packet {

    public static final PacketCodec<WeatherSyncS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WeatherSyncS2C p) {
            out.writeDouble(p.weatherSeconds());
            out.writeByte(p.mode().ordinal());
        }

        @Override
        public WeatherSyncS2C decode(ByteBuf in) {
            return new WeatherSyncS2C(in.readDouble(), WeatherMode.byId(in.readUnsignedByte()));
        }
    };
}
