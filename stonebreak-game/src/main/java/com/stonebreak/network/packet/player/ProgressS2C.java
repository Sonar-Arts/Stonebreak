package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the authoritative progression fields (issue #334). Level and xp live
 * server-side and advance only through server-granted kill XP; the point pools are
 * computed from the server's level minus the validated spend ledger. Sent on acceptance
 * of a {@link PlayerPrefsC2S} and on a server-side level-up so the client mirror
 * converges without ever applying client-claimed values.
 *
 * <p>{@code ackSeq} is the {@link PlayerPrefsC2S#seq()} of the last prefs report the server
 * processed (accepted or refused; 0 before the first). The client applies the pools only when
 * the echo answers its LATEST report — an echo of an older one carries pools computed from a
 * spend ledger the client has since changed, and applying them would let it spend twice.
 *
 * @param ackSeq       seq of the last processed {@link PlayerPrefsC2S}
 * @param level        server-owned level
 * @param xp           server-owned xp into the current level
 * @param remainingCp  unspent class points (server-computed)
 * @param remainingSp  unspent skill points (server-computed)
 * @param remainingFp  unspent feat points (server-computed)
 * @param remainingAp  unspent ability points (server-held pool)
 */
public record ProgressS2C(int ackSeq, int level, int xp, int remainingCp, int remainingSp,
                          int remainingFp, int remainingAp) implements Packet {

    public static final PacketCodec<ProgressS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ProgressS2C p) {
            out.writeInt(p.ackSeq());
            out.writeInt(p.level());
            out.writeInt(p.xp());
            out.writeInt(p.remainingCp());
            out.writeInt(p.remainingSp());
            out.writeInt(p.remainingFp());
            out.writeInt(p.remainingAp());
        }

        @Override
        public ProgressS2C decode(ByteBuf in) {
            return new ProgressS2C(in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt());
        }
    };
}
