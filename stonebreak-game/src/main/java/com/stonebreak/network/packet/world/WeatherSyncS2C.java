package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.weather.WeatherMode;
import com.stonebreak.world.weather.WeatherState;
import com.stonebreak.world.weather.WeatherZone;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client authoritative weather sample. Sent non-droppably right after
 * {@code WelcomeS2C} and whenever the mode changes, then droppably every ~5 s
 * beside {@code TimeSyncS2C}. Clouds, rain and lightning are all derived from
 * the world seed plus this clock, mode and zones, so nothing else about the weather
 * ever crosses the wire.
 *
 * @param weatherSeconds the server's monotonic weather clock
 * @param mode           the world's weather mode
 * @param zones          regions held at their own weather ({@code /weather <mode>})
 */
public record WeatherSyncS2C(double weatherSeconds, WeatherMode mode, List<WeatherZone> zones) implements Packet {

    public static final PacketCodec<WeatherSyncS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WeatherSyncS2C p) {
            out.writeDouble(p.weatherSeconds());
            out.writeByte(p.mode().ordinal());
            out.writeByte(p.zones().size());
            for (WeatherZone z : p.zones()) {
                out.writeDouble(z.x());
                out.writeDouble(z.z());
                out.writeFloat(z.radius());
                out.writeByte(z.mode().ordinal());
            }
        }

        @Override
        public WeatherSyncS2C decode(ByteBuf in) {
            double seconds = in.readDouble();
            WeatherMode mode = WeatherMode.byId(in.readUnsignedByte());
            int count = Math.min(in.readUnsignedByte(), WeatherState.MAX_ZONES);
            List<WeatherZone> zones = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                zones.add(new WeatherZone(in.readDouble(), in.readDouble(), in.readFloat(),
                        WeatherMode.byId(in.readUnsignedByte())));
            }
            return new WeatherSyncS2C(seconds, mode, List.copyOf(zones));
        }
    };
}
