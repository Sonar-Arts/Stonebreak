package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Client → server: this player's preferences and locally-earned progression — the JSON
 * {@code PlayerData} shape the old {@code PlayerDataC2S} carried, but now VALIDATED
 * server-side instead of trusted:
 * <ul>
 *   <li><b>Earned fields</b> (level, xp, skill levels, feats, ability scores, statistics)
 *       may only grow; discoveries may only be added to.</li>
 *   <li><b>Point pools</b> (CP/SP/FP/AP) are validated by conservation — spends and
 *       in-session undos pass, conjured points do not.</li>
 *   <li><b>Prefs</b> (health, game mode, flight, spectator) are clamped/cheats-gated;
 *       the hotbar selection is NOT carried here (that is {@link HotbarSelectC2S}) and
 *       the inventory field is ignored — the server owns the inventory.</li>
 * </ul>
 *
 * <p>Sent on the old PlayerDataC2S cadence (~3 s) and on disconnect. A refused packet
 * keeps the accepted baseline and re-asserts {@link PlayerDataS2C} to the sender.
 */
public record PlayerPrefsC2S(String json) implements Packet {

    /** Matches the old PlayerDataC2S budget. */
    private static final int MAX_JSON = 64 * 1024;

    public PlayerPrefsC2S {
        json = truncate(json);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_JSON ? s.substring(0, MAX_JSON) : s;
    }

    public static final PacketCodec<PlayerPrefsC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerPrefsC2S p) {
            byte[] bytes = p.json().getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.writeBytes(bytes);
        }

        @Override
        public PlayerPrefsC2S decode(ByteBuf in) {
            int len = in.readInt();
            if (len < 0 || len > MAX_JSON) {
                throw new IllegalArgumentException("PlayerPrefsC2S payload out of range: " + len);
            }
            byte[] bytes = new byte[len];
            in.readBytes(bytes);
            return new PlayerPrefsC2S(new String(bytes, StandardCharsets.UTF_8));
        }
    };
}
