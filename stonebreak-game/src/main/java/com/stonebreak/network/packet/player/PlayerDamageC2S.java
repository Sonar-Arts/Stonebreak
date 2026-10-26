package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the local player hit another player (PvP, issue #343). The player-target twin
 * of {@code EntityDamageC2S}: players have no network id, so the target is named by player id.
 * Refused unless the world allows PvP; otherwise validated by the same rules (melee reach from
 * the server's own positions, swing cooldown, held-weapon cap; ability profile) and applied to
 * the target's server-owned health. The result comes back as {@code KillCreditS2C}.
 *
 * @param targetPlayerId the hit player's id
 * @param amount         damage the client computed; server-capped
 * @param sourceOrdinal  ordinal of {@code LivingEntity.DamageSource}
 * @param typeOrdinal    ordinal of {@code DamageType}
 * @param hitKind        {@code HitKind} wire id (MELEE / ABILITY)
 */
public record PlayerDamageC2S(int targetPlayerId, float amount, byte sourceOrdinal, byte typeOrdinal,
                              byte hitKind) implements Packet {

    public static final PacketCodec<PlayerDamageC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerDamageC2S p) {
            out.writeInt(p.targetPlayerId());
            out.writeFloat(p.amount());
            out.writeByte(p.sourceOrdinal());
            out.writeByte(p.typeOrdinal());
            out.writeByte(p.hitKind());
        }

        @Override
        public PlayerDamageC2S decode(ByteBuf in) {
            return new PlayerDamageC2S(in.readInt(), in.readFloat(), in.readByte(), in.readByte(),
                    in.readByte());
        }
    };
}
