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
 *
 * <p>{@code seq} numbers each report (issue #334): every processed report — accepted or
 * refused — is answered with a {@link ProgressS2C} carrying it as {@code ackSeq}, so the
 * client can tell an echo of an older report from the answer to its latest one.
 *
 * <p>{@code provisional} is true while the client's character-panel session (save-or-discard)
 * is open: its spends may still be undone, so the server keeps them refundable down to the
 * last commit. A non-provisional report closes that window and commits what it carries
 * (issue #421; see {@code SpendFloor}).
 *
 * <p>The payload is bounded in UTF-8 BYTES, the unit the decoder checks: an oversized JSON is
 * encoded as an empty payload (which the server refuses and answers) instead of a frame the
 * decoder would reject by closing the connection, or a character-truncated, invalid JSON.
 */
public record PlayerPrefsC2S(int seq, boolean provisional, String json) implements Packet {

    /** Matches the old PlayerDataC2S budget (UTF-8 bytes). */
    public static final int MAX_JSON_BYTES = 64 * 1024;

    public PlayerPrefsC2S {
        json = json == null ? "" : json;
    }

    /** A committing (non-provisional) report. */
    public PlayerPrefsC2S(int seq, String json) {
        this(seq, false, json);
    }

    /** True when {@code json} fits the wire budget (UTF-8 bytes). */
    public static boolean fits(String json) {
        return json == null || json.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES;
    }

    public static final PacketCodec<PlayerPrefsC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerPrefsC2S p) {
            byte[] bytes = p.json().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_BYTES) {
                bytes = new byte[0]; // never a frame the decoder rejects; the server refuses it
            }
            out.writeInt(p.seq());
            out.writeBoolean(p.provisional());
            out.writeInt(bytes.length);
            out.writeBytes(bytes);
        }

        @Override
        public PlayerPrefsC2S decode(ByteBuf in) {
            int seq = in.readInt();
            boolean provisional = in.readBoolean();
            int len = in.readInt();
            if (len < 0 || len > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("PlayerPrefsC2S payload out of range: " + len);
            }
            byte[] bytes = new byte[len];
            in.readBytes(bytes);
            return new PlayerPrefsC2S(seq, provisional, new String(bytes, StandardCharsets.UTF_8));
        }
    };
}
