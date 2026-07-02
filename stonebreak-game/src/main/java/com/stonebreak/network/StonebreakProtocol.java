package com.stonebreak.network;

import com.openmason.engine.net.protocol.PacketRegistry;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.entity.EntityAnimS2C;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntityResyncC2S;
import com.stonebreak.network.packet.entity.ProjectileSpawnC2S;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KeepAliveC2S;
import com.stonebreak.network.packet.handshake.KeepAliveS2C;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.KillCreditS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.player.PlayerDataC2S;
import com.stonebreak.network.packet.player.PlayerDataS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.packet.player.ViewDistanceC2S;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockMetaS2C;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.packet.world.ChunkHashesC2S;
import com.stonebreak.network.packet.world.ChunkResyncRequestC2S;
import com.stonebreak.network.packet.world.FurnaceSlotsC2S;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.network.packet.world.SnowLayerC2S;
import com.stonebreak.network.packet.world.TimeSyncS2C;

import static com.openmason.engine.net.protocol.PacketDirection.CLIENTBOUND;
import static com.openmason.engine.net.protocol.PacketDirection.SERVERBOUND;
import static com.openmason.engine.net.protocol.ProtocolPhase.PLAY;

/**
 * Registers every concrete Stonebreak packet into a {@link PacketRegistry}, keyed by
 * ({@code phase}, {@code direction}). Ids are explicit per-slot constants (varint on the
 * wire) so gaps can be left for future / dedicated-server-only packets without shifting
 * existing ids.
 *
 * <p>The protocol currently uses a single active phase ({@code PLAY}); the handshake
 * packets live in it too. A real HANDSHAKE→PLAY transition is deferred (the cross-thread
 * decode-phase flip is racy), so all packets register under {@code PLAY} for now.
 *
 * <p>Built once at class load; the registry is then read-only and thread-safe.
 */
public final class StonebreakProtocol {

    private static final PacketRegistry REGISTRY = build();

    /** The shared, fully-populated registry. */
    public static PacketRegistry registry() {
        return REGISTRY;
    }

    private static PacketRegistry build() {
        PacketRegistry r = new PacketRegistry();

        // ── serverbound (C2S) ──────────────────────────────────────────────
        r.register(PLAY, SERVERBOUND, 1, BlockChangeC2S.class, BlockChangeC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 2, PlayerStateC2S.class, PlayerStateC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 3, PlayerHeldItemC2S.class, PlayerHeldItemC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 4, ChatMessageC2S.class, ChatMessageC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 5, DisconnectC2S.class, DisconnectC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 6, HandshakeC2S.class, HandshakeC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 7, PlayerDataC2S.class, PlayerDataC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 8, ViewDistanceC2S.class, ViewDistanceC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 9, EntityDamageC2S.class, EntityDamageC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 10, KeepAliveC2S.class, KeepAliveC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 11, ChunkResyncRequestC2S.class, ChunkResyncRequestC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 12, SnowLayerC2S.class, SnowLayerC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 13, FurnaceSlotsC2S.class, FurnaceSlotsC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 14, ProjectileSpawnC2S.class, ProjectileSpawnC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 15, ChunkHashesC2S.class, ChunkHashesC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 16, DropItemC2S.class, DropItemC2S.CODEC);
        r.register(PLAY, SERVERBOUND, 17, EntityResyncC2S.class, EntityResyncC2S.CODEC);

        // ── clientbound (S2C) ──────────────────────────────────────────────
        r.register(PLAY, CLIENTBOUND, 1, ChunkDataS2C.class, ChunkDataS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 2, BlockChangeS2C.class, BlockChangeS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 3, MultiBlockChangeS2C.class, MultiBlockChangeS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 4, PlayerStateS2C.class, PlayerStateS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 5, PlayerHeldItemS2C.class, PlayerHeldItemS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 6, GiveItemS2C.class, GiveItemS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 7, PlayerJoinS2C.class, PlayerJoinS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 8, PlayerLeaveS2C.class, PlayerLeaveS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 9, ChatMessageS2C.class, ChatMessageS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 10, EntitySpawnS2C.class, EntitySpawnS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 11, EntityDespawnS2C.class, EntityDespawnS2C.CODEC);
        // id 12 retired (EntityStateS2C — decoded, never emitted; removed in protocol v2).
        r.register(PLAY, CLIENTBOUND, 13, EntityMoveS2C.class, EntityMoveS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 14, EntityTeleportS2C.class, EntityTeleportS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 15, KickS2C.class, KickS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 16, WelcomeS2C.class, WelcomeS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 17, PlayerDataS2C.class, PlayerDataS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 18, EntityAnimS2C.class, EntityAnimS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 19, TimeSyncS2C.class, TimeSyncS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 20, KeepAliveS2C.class, KeepAliveS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 21, BlockMetaS2C.class, BlockMetaS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 22, BlockStateS2C.class, BlockStateS2C.CODEC);
        r.register(PLAY, CLIENTBOUND, 23, KillCreditS2C.class, KillCreditS2C.CODEC);

        return r;
    }

    private StonebreakProtocol() {}
}
