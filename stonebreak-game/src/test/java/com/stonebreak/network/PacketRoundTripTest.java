package com.stonebreak.network;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.stonebreak.network.packet.chat.ChatMessageC2S;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.entity.EntityAnimS2C;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import com.stonebreak.network.packet.handshake.DisconnectC2S;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KeepAliveC2S;
import com.stonebreak.network.packet.handshake.KeepAliveS2C;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.HotbarSelectC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.KillCreditS2C;
import com.stonebreak.network.packet.player.PlayerDataS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.network.packet.player.PlayerLeaveS2C;
import com.stonebreak.network.packet.player.PlayerStateC2S;
import com.stonebreak.network.packet.player.PlayerStateS2C;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockMetaS2C;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.packet.entity.EntityResyncC2S;
import com.stonebreak.network.packet.entity.ProjectileSpawnC2S;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.packet.world.ChunkHashesC2S;
import com.stonebreak.network.packet.world.ChunkResyncRequestC2S;
import com.stonebreak.network.packet.world.ChunkUnloadC2S;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.network.packet.world.SnowLayerC2S;
import com.stonebreak.network.packet.world.TimeSyncS2C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.net.protocol.PacketDirection.CLIENTBOUND;
import static com.openmason.engine.net.protocol.PacketDirection.SERVERBOUND;
import static com.openmason.engine.net.protocol.ProtocolPhase.PLAY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * §5d — every packet's codec round-trips, and the {@link StonebreakProtocol} registry maps
 * ids ↔ codecs without collisions. The "no leftover bytes" check catches field drift
 * between encode and decode.
 */
@Tag("regression")
class PacketRoundTripTest {

    private static <T extends Packet> T roundTrip(PacketCodec<T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, value);
            T decoded = codec.decode(buf);
            assertEquals(0, buf.readableBytes(),
                "codec left unread bytes for " + value.getClass().getSimpleName());
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void scalarPacketsRoundTrip() {
        assertEquals(new HandshakeC2S(1, "player"), roundTrip(HandshakeC2S.CODEC, new HandshakeC2S(1, "player")));
        assertEquals(new WelcomeS2C(7, 123456789L, TerrainGeneratorType.DIFFUSION, 1f, 64f, 2f), roundTrip(WelcomeS2C.CODEC, new WelcomeS2C(7, 123456789L, TerrainGeneratorType.DIFFUSION, 1f, 64f, 2f)));
        // Join secrets (issue #335): issued in the welcome, presented in the next handshake.
        assertEquals(new HandshakeC2S(1, "Bob", "Zx9-_secret"), roundTrip(HandshakeC2S.CODEC, new HandshakeC2S(1, "Bob", "Zx9-_secret")));
        assertEquals("", roundTrip(HandshakeC2S.CODEC, new HandshakeC2S(1, "Bob", null)).clientSecret());
        // A pre-v16 handshake (no secret) still decodes, so the server can kick it with
        // "Protocol mismatch" instead of the decoder closing the channel.
        ByteBuf legacy = Unpooled.buffer();
        legacy.writeInt(15);
        com.openmason.engine.net.protocol.ByteBufIO.writeString(legacy, "Bob",
                com.openmason.engine.net.protocol.ByteBufIO.MAX_USERNAME_CHARS);
        assertEquals(new HandshakeC2S(15, "Bob", ""), HandshakeC2S.CODEC.decode(legacy));
        legacy.release();
        assertEquals(new WelcomeS2C(7, 1L, TerrainGeneratorType.STANDARD, 1f, 2f, 3f, "Zx9-_secret"),
                roundTrip(WelcomeS2C.CODEC, new WelcomeS2C(7, 1L, TerrainGeneratorType.STANDARD, 1f, 2f, 3f, "Zx9-_secret")));
        assertEquals(new KickS2C("bye"), roundTrip(KickS2C.CODEC, new KickS2C("bye")));
        assertEquals(new DisconnectC2S("leaving"), roundTrip(DisconnectC2S.CODEC, new DisconnectC2S("leaving")));
        assertEquals(new BlockChangeC2S(10, 64, -5, (short) 3, (short) 1), roundTrip(BlockChangeC2S.CODEC, new BlockChangeC2S(10, 64, -5, (short) 3, (short) 1)));
        assertEquals(new BlockChangeC2S(10, 64, -5, (short) 43, (short) 0, "torch:state=Side;facing=WEST"),
                roundTrip(BlockChangeC2S.CODEC, new BlockChangeC2S(10, 64, -5, (short) 43, (short) 0, "torch:state=Side;facing=WEST")));
        assertEquals("", roundTrip(BlockChangeC2S.CODEC, new BlockChangeC2S(1, 2, 3, (short) 4, (short) 5, null)).placementState());
        assertEquals(new BlockChangeS2C(10, 64, -5, (short) 3), roundTrip(BlockChangeS2C.CODEC, new BlockChangeS2C(10, 64, -5, (short) 3)));
        assertEquals(new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0), roundTrip(PlayerStateC2S.CODEC, new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0)));
        assertEquals(new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0xFF), roundTrip(PlayerStateC2S.CODEC, new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0xFF)));
        assertEquals(new PlayerStateS2C(4, 1f, 2f, 3f, 90f, -10f, (byte) 0x3F), roundTrip(PlayerStateS2C.CODEC, new PlayerStateS2C(4, 1f, 2f, 3f, 90f, -10f, (byte) 0x3F)));
        assertEquals(new HotbarSelectC2S(5), roundTrip(HotbarSelectC2S.CODEC, new HotbarSelectC2S(5)));
        assertEquals(new com.stonebreak.network.packet.player.ConsumeHeldC2S(1032, 70_000),
            roundTrip(com.stonebreak.network.packet.player.ConsumeHeldC2S.CODEC,
                new com.stonebreak.network.packet.player.ConsumeHeldC2S(1032, 70_000)));
        assertEquals(new com.stonebreak.network.packet.player.PlayerLifeC2S(true, 1.5f, -64f, 3f),
            roundTrip(com.stonebreak.network.packet.player.PlayerLifeC2S.CODEC,
                new com.stonebreak.network.packet.player.PlayerLifeC2S(true, 1.5f, -64f, 3f)));
        assertEquals(new com.stonebreak.network.packet.player.PlayerLifeC2S(false, 0f, 70f, 0f),
            roundTrip(com.stonebreak.network.packet.player.PlayerLifeC2S.CODEC,
                new com.stonebreak.network.packet.player.PlayerLifeC2S(false, 0f, 70f, 0f)));
        assertEquals(new ContainerActionC2S(10, 64, -5, ContainerActionC2S.ContainerKind.FURNACE,
                ContainerActionC2S.Region.FURNACE_ING, (byte) 0, ContainerActionC2S.Action.PUT_ALL, (short) 8, true),
            roundTrip(ContainerActionC2S.CODEC, new ContainerActionC2S(10, 64, -5,
                ContainerActionC2S.ContainerKind.FURNACE, ContainerActionC2S.Region.FURNACE_ING,
                (byte) 0, ContainerActionC2S.Action.PUT_ALL, (short) 8, true)));
        assertEquals(new PlayerHeldItemS2C(2, 5), roundTrip(PlayerHeldItemS2C.CODEC, new PlayerHeldItemS2C(2, 5)));
        assertEquals(new GiveItemS2C(3, 64), roundTrip(GiveItemS2C.CODEC, new GiveItemS2C(3, 64)));
        assertEquals(new PlayerJoinS2C(2, "bob", 1f, 2f, 3f), roundTrip(PlayerJoinS2C.CODEC, new PlayerJoinS2C(2, "bob", 1f, 2f, 3f)));
        assertEquals(new PlayerLeaveS2C(2), roundTrip(PlayerLeaveS2C.CODEC, new PlayerLeaveS2C(2)));
        assertEquals(new ChatMessageC2S("hi"), roundTrip(ChatMessageC2S.CODEC, new ChatMessageC2S("hi")));
        assertEquals(new ChatMessageS2C(1, "bob", "hi"), roundTrip(ChatMessageS2C.CODEC, new ChatMessageS2C(1, "bob", "hi")));
        assertEquals(new EntitySpawnS2C(9, 1, 1f, 2f, 3f, 90f, "variant"), roundTrip(EntitySpawnS2C.CODEC, new EntitySpawnS2C(9, 1, 1f, 2f, 3f, 90f, "variant")));
        assertEquals(new EntityDespawnS2C(9), roundTrip(EntityDespawnS2C.CODEC, new EntityDespawnS2C(9)));
        assertEquals(new EntityDamageC2S(9, 4.5f, (byte) 1, (byte) 2, (byte) 0), roundTrip(EntityDamageC2S.CODEC, new EntityDamageC2S(9, 4.5f, (byte) 1, (byte) 2, (byte) 0)));
        assertEquals(new EntityDamageC2S(9, 4.5f, (byte) 9, (byte) 2, (byte) 1), roundTrip(EntityDamageC2S.CODEC, new EntityDamageC2S(9, 4.5f, (byte) 9, (byte) 2, (byte) 1)));
        assertEquals(new EntityMoveS2C(9, (short) 1, (short) 2, (short) 3, (short) 900), roundTrip(EntityMoveS2C.CODEC, new EntityMoveS2C(9, (short) 1, (short) 2, (short) 3, (short) 900)));
        assertEquals(new EntityTeleportS2C(9, 1f, 2f, 3f, 90f), roundTrip(EntityTeleportS2C.CODEC, new EntityTeleportS2C(9, 1f, 2f, 3f, 90f)));
        assertEquals(new EntityAnimS2C(9, "Grazing"), roundTrip(EntityAnimS2C.CODEC, new EntityAnimS2C(9, "Grazing")));
        assertEquals(new KeepAliveC2S(0x1122334455667788L), roundTrip(KeepAliveC2S.CODEC, new KeepAliveC2S(0x1122334455667788L)));
        assertEquals(new KeepAliveS2C(Long.MIN_VALUE, 250), roundTrip(KeepAliveS2C.CODEC, new KeepAliveS2C(Long.MIN_VALUE, 250)));
        assertEquals(new TimeSyncS2C(23_999L, 1.0f, false), roundTrip(TimeSyncS2C.CODEC, new TimeSyncS2C(23_999L, 1.0f, false)));
        assertEquals(new TimeSyncS2C(0L, 2.5f, true), roundTrip(TimeSyncS2C.CODEC, new TimeSyncS2C(0L, 2.5f, true)));
        assertEquals(new com.stonebreak.network.packet.world.TimeSetC2S(18_000L),
            roundTrip(com.stonebreak.network.packet.world.TimeSetC2S.CODEC,
                new com.stonebreak.network.packet.world.TimeSetC2S(18_000L)));
        for (boolean enabled : new boolean[] {true, false}) {
            assertEquals(new com.stonebreak.network.packet.world.CheatsSetC2S(enabled),
                roundTrip(com.stonebreak.network.packet.world.CheatsSetC2S.CODEC,
                    new com.stonebreak.network.packet.world.CheatsSetC2S(enabled)));
            assertEquals(new com.stonebreak.network.packet.world.CheatsStateS2C(enabled),
                roundTrip(com.stonebreak.network.packet.world.CheatsStateS2C.CODEC,
                    new com.stonebreak.network.packet.world.CheatsStateS2C(enabled)));
        }
        java.util.List<com.stonebreak.world.weather.WeatherZone> zones = java.util.List.of(
            new com.stonebreak.world.weather.WeatherZone(-1234.5, 6789.25, 1500f, com.stonebreak.world.weather.WeatherMode.STORM),
            new com.stonebreak.world.weather.WeatherZone(10, -20, 800f, com.stonebreak.world.weather.WeatherMode.CLEAR));
        for (com.stonebreak.world.weather.WeatherMode mode : com.stonebreak.world.weather.WeatherMode.values()) {
            assertEquals(new com.stonebreak.network.packet.world.WeatherSyncS2C(123_456.75, mode, zones),
                roundTrip(com.stonebreak.network.packet.world.WeatherSyncS2C.CODEC,
                    new com.stonebreak.network.packet.world.WeatherSyncS2C(123_456.75, mode, zones)));
            assertEquals(new com.stonebreak.network.packet.world.WeatherSetC2S(mode, mode.ordinal() % 2 == 0),
                roundTrip(com.stonebreak.network.packet.world.WeatherSetC2S.CODEC,
                    new com.stonebreak.network.packet.world.WeatherSetC2S(mode, mode.ordinal() % 2 == 0)));
        }
        assertEquals(new DropItemC2S(12, 64), roundTrip(DropItemC2S.CODEC, new DropItemC2S(12, 64)));
        // Intent seqs (protocol 13): placements, tosses, snow, toggles, death drops.
        assertEquals(new BlockChangeC2S(10, 64, -5, (short) 3, (short) 1, "", 2_000_000),
            roundTrip(BlockChangeC2S.CODEC, new BlockChangeC2S(10, 64, -5, (short) 3, (short) 1, "", 2_000_000)));
        assertEquals(new DropItemC2S(12, 64, 77), roundTrip(DropItemC2S.CODEC, new DropItemC2S(12, 64, 77)));
        assertEquals(new SnowLayerC2S(1, 2, 3, (byte) 4, 78), roundTrip(SnowLayerC2S.CODEC, new SnowLayerC2S(1, 2, 3, (byte) 4, 78)));
        assertEquals(new com.stonebreak.network.packet.world.BlockToggleC2S(1, 2, 3, 79),
            roundTrip(com.stonebreak.network.packet.world.BlockToggleC2S.CODEC,
                new com.stonebreak.network.packet.world.BlockToggleC2S(1, 2, 3, 79)));
        assertEquals(new com.stonebreak.network.packet.player.DeathDropC2S(80),
            roundTrip(com.stonebreak.network.packet.player.DeathDropC2S.CODEC,
                new com.stonebreak.network.packet.player.DeathDropC2S(80)));
        assertEquals(new com.stonebreak.network.packet.player.InventoryResyncC2S(),
            roundTrip(com.stonebreak.network.packet.player.InventoryResyncC2S.CODEC,
                new com.stonebreak.network.packet.player.InventoryResyncC2S()));
        assertEquals(new SnowLayerC2S(10, 64, -5, (byte) 8), roundTrip(SnowLayerC2S.CODEC, new SnowLayerC2S(10, 64, -5, (byte) 8)));
        assertEquals(new SnowLayerC2S(0, 0, 0, (byte) 0), roundTrip(SnowLayerC2S.CODEC, new SnowLayerC2S(0, 0, 0, (byte) 0)));
        assertEquals(new BlockStateS2C(10, 64, -5, "furnace:state=Lit;ing=B:1:3;fuel=I:20:2;out=B:5:1;burn=100;burnTotal=200;cook=40"),
            roundTrip(BlockStateS2C.CODEC, new BlockStateS2C(10, 64, -5, "furnace:state=Lit;ing=B:1:3;fuel=I:20:2;out=B:5:1;burn=100;burnTotal=200;cook=40")));
        assertEquals(new InventorySyncS2C("B:1:3|I:20:2|B:0:0", 7_000),
            roundTrip(InventorySyncS2C.CODEC, new InventorySyncS2C("B:1:3|I:20:2|B:0:0", 7_000)));
        assertEquals(new BlockStateS2C(10, 64, -5, "workbench:grid=", 300_000),
            roundTrip(BlockStateS2C.CODEC, new BlockStateS2C(10, 64, -5, "workbench:grid=", 300_000)));
        assertEquals(new ChunkResyncRequestC2S(-3, 12), roundTrip(ChunkResyncRequestC2S.CODEC, new ChunkResyncRequestC2S(-3, 12)));
        assertEquals(new KillCreditS2C(2, 7.5f, true, 15, 42, (byte) 7, (byte) 2, 1f, 65.5f, -3f), roundTrip(KillCreditS2C.CODEC, new KillCreditS2C(2, 7.5f, true, 15, 42, (byte) 7, (byte) 2, 1f, 65.5f, -3f)));
        assertEquals(new KillCreditS2C(0, 1f, false, 0, -1, (byte) 1, (byte) 0, 0f, 0f, 0f), roundTrip(KillCreditS2C.CODEC, new KillCreditS2C(0, 1f, false, 0, -1, (byte) 1, (byte) 0, 0f, 0f, 0f)));
        assertEquals(new EntityResyncC2S(), roundTrip(EntityResyncC2S.CODEC, new EntityResyncC2S()));
        assertEquals(new com.stonebreak.network.packet.player.DeathDropC2S(),
            roundTrip(com.stonebreak.network.packet.player.DeathDropC2S.CODEC,
                new com.stonebreak.network.packet.player.DeathDropC2S()));
        assertEquals(new com.stonebreak.network.packet.player.PlayerPrefsC2S(7, "{\"k\":1}"),
            roundTrip(com.stonebreak.network.packet.player.PlayerPrefsC2S.CODEC,
                new com.stonebreak.network.packet.player.PlayerPrefsC2S(7, "{\"k\":1}")));
        // #421: the provisional (respec window) flag survives the wire.
        assertEquals(new com.stonebreak.network.packet.player.PlayerPrefsC2S(8, true, "{}"),
            roundTrip(com.stonebreak.network.packet.player.PlayerPrefsC2S.CODEC,
                new com.stonebreak.network.packet.player.PlayerPrefsC2S(8, true, "{}")));
    }

    @Test
    void oversizedPlayerPrefsEncodeAsAnEmptyPayloadNotAnUndecodableFrame() {
        // 40k three-byte characters: under 64 K chars (the old char-based truncation let it
        // through) but 120 KB of UTF-8 — the decoder's byte cap used to kill the connection.
        String big = "\u20ac".repeat(40_000);
        assertFalse(com.stonebreak.network.packet.player.PlayerPrefsC2S.fits(big));
        com.stonebreak.network.packet.player.PlayerPrefsC2S decoded =
            roundTrip(com.stonebreak.network.packet.player.PlayerPrefsC2S.CODEC,
                new com.stonebreak.network.packet.player.PlayerPrefsC2S(3, big));
        assertEquals(3, decoded.seq(), "the seq survives so the server can still ack it");
        assertEquals("", decoded.json(), "oversized payload -> empty (refused + answered)");
        // Nothing is silently truncated into invalid JSON any more.
        String fits = "{\"k\":\"" + "a".repeat(1000) + "\"}";
        assertEquals(fits, new com.stonebreak.network.packet.player.PlayerPrefsC2S(1, fits).json());
    }

    @Test
    void projectileSpawnRoundTrips() {
        ProjectileSpawnC2S p = new ProjectileSpawnC2S(
            ProjectileSpawnC2S.KIND_NULL_SPIKE, 1f, 65f, -3f, 0.5f, 0.1f, -0.8f,
            new float[]{12.5f, 6f, 1f, 20f});
        ProjectileSpawnC2S decoded = roundTrip(ProjectileSpawnC2S.CODEC, p);
        assertEquals(p.kind(), decoded.kind());
        assertEquals(p.x(), decoded.x());
        assertEquals(p.vz(), decoded.vz());
        assertArrayEquals(p.params(), decoded.params());
        // Empty params (arrow/fire bolt) survive too.
        assertArrayEquals(new float[0],
            roundTrip(ProjectileSpawnC2S.CODEC, new ProjectileSpawnC2S(
                ProjectileSpawnC2S.KIND_ARROW, 0f, 0f, 0f, 0f, 30f, 0f, new float[0])).params());
        // An arrow carries its intent seq (protocol 13).
        assertEquals(81, roundTrip(ProjectileSpawnC2S.CODEC, new ProjectileSpawnC2S(
                ProjectileSpawnC2S.KIND_ARROW, 0f, 0f, 0f, 0f, 30f, 0f, new float[0], 81)).seq());
    }

    @Test
    void chunkHashesRoundTrip() {
        int[] entries = {1, -2, 0xDEADBEEF, 3, 4, 0};
        assertArrayEquals(entries,
            roundTrip(ChunkHashesC2S.CODEC, new ChunkHashesC2S(entries)).entries());
    }

    @Test
    void chunkUnloadRoundTripsAndRejectsOversizedLists() {
        int[] coords = {1, -2, Integer.MIN_VALUE, Integer.MAX_VALUE};
        assertArrayEquals(coords,
            roundTrip(ChunkUnloadC2S.CODEC, new ChunkUnloadC2S(coords)).coords());
        int[] tooMany = new int[(ChunkUnloadC2S.MAX_CHUNKS + 1) * 2];
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> roundTrip(ChunkUnloadC2S.CODEC, new ChunkUnloadC2S(tooMany)));
        ChunkUnloadC2S centred = roundTrip(ChunkUnloadC2S.CODEC, new ChunkUnloadC2S(coords, 40, -7));
        org.junit.jupiter.api.Assertions.assertEquals(40, centred.centerX());
        org.junit.jupiter.api.Assertions.assertEquals(-7, centred.centerZ());
    }

    @Test
    void chunkAckRoundTripsAndRejectsHostileValues() {
        var ack = roundTrip(com.stonebreak.network.packet.world.ChunkAckC2S.CODEC,
            new com.stonebreak.network.packet.world.ChunkAckC2S(12, 9.5f));
        org.junit.jupiter.api.Assertions.assertEquals(12, ack.processed());
        org.junit.jupiter.api.Assertions.assertEquals(9.5f, ack.desiredChunksPerTick());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> roundTrip(com.stonebreak.network.packet.world.ChunkAckC2S.CODEC,
                new com.stonebreak.network.packet.world.ChunkAckC2S(-1, 1f)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> roundTrip(com.stonebreak.network.packet.world.ChunkAckC2S.CODEC,
                new com.stonebreak.network.packet.world.ChunkAckC2S(1, Float.NaN)));
    }

    @Test
    void entityStatusEffectIntentRoundTrips() {
        var se = roundTrip(com.stonebreak.network.packet.entity.EntityStatusEffectC2S.CODEC,
            new com.stonebreak.network.packet.entity.EntityStatusEffectC2S(7, (byte) 4, 3.5f, 0.25f));
        assertEquals(new com.stonebreak.network.packet.entity.EntityStatusEffectC2S(7, (byte) 4, 3.5f, 0.25f), se);
    }

    @Test
    void entityStatusRoundTripsEveryEffectBitAndQuantizesHealth() {
        int all = com.stonebreak.mobs.entities.status.StatusEffectBits.sanitize(0xFFFF);
        var st = roundTrip(com.stonebreak.network.packet.entity.EntityStatusS2C.CODEC,
            new com.stonebreak.network.packet.entity.EntityStatusS2C(12, 0.3f, all));
        assertEquals(12, st.networkId());
        assertEquals(all, st.statusBits(), "every effect's bit survives the wire");
        assertEquals(0.3f, st.healthFraction(), 1e-4f);
        assertEquals(1f, roundTrip(com.stonebreak.network.packet.entity.EntityStatusS2C.CODEC,
            new com.stonebreak.network.packet.entity.EntityStatusS2C(1, 1f, 0)).healthFraction());
        assertEquals(0f, roundTrip(com.stonebreak.network.packet.entity.EntityStatusS2C.CODEC,
            new com.stonebreak.network.packet.entity.EntityStatusS2C(1, Float.NaN, 0)).healthFraction(),
            "a NaN fraction reads as empty, never garbage");
        assertEquals(1f, roundTrip(com.stonebreak.network.packet.entity.EntityStatusS2C.CODEC,
            new com.stonebreak.network.packet.entity.EntityStatusS2C(1, 7f, 0)).healthFraction(),
            "over-full clamps to full");
    }

    @Test
    void entityKnockbackRoundTrips() {
        var kb = roundTrip(com.stonebreak.network.packet.entity.EntityKnockbackC2S.CODEC,
            new com.stonebreak.network.packet.entity.EntityKnockbackC2S(7, 0.6f, -0.8f, 9f, 4f));
        org.junit.jupiter.api.Assertions.assertEquals(7, kb.targetNetworkId());
        org.junit.jupiter.api.Assertions.assertEquals(-0.8f, kb.dirZ());
        org.junit.jupiter.api.Assertions.assertEquals(4f, kb.vertical());
    }

    @Test
    void blockMetaRoundTrips() {
        int[] packed = {(0x123 << 16) | 1, (0x456 << 16) | 8, (0x789 << 16)};
        BlockMetaS2C decoded = roundTrip(BlockMetaS2C.CODEC,
            new BlockMetaS2C(1, -2, 3, BlockMetaS2C.KIND_SNOW_LAYERS, packed));
        assertEquals(1, decoded.sectionX());
        assertEquals(-2, decoded.sectionY());
        assertEquals(3, decoded.sectionZ());
        assertEquals(BlockMetaS2C.KIND_SNOW_LAYERS, decoded.metaKind());
        assertArrayEquals(packed, decoded.packed());
    }

    @Test
    void blockMetaWaterLevelRoundTrips() {
        // Water layer values: 1..7 flowing, 8 falling, 0 = removed/became-source.
        int[] packed = {(0x0F0 << 16) | 1, (0xFFF << 16) | 8, (0x001 << 16) | 7, (0x234 << 16)};
        BlockMetaS2C decoded = roundTrip(BlockMetaS2C.CODEC,
            new BlockMetaS2C(-4, 2, 9, BlockMetaS2C.KIND_WATER_LEVEL, packed));
        assertEquals(-4, decoded.sectionX());
        assertEquals(2, decoded.sectionY());
        assertEquals(9, decoded.sectionZ());
        assertEquals(BlockMetaS2C.KIND_WATER_LEVEL, decoded.metaKind());
        assertArrayEquals(packed, decoded.packed());
    }

    @Test
    void chunkDataRoundTrips() {
        byte[] payload = {1, 2, 3, 4, 5, -1, 127, -128};
        ChunkDataS2C decoded = roundTrip(ChunkDataS2C.CODEC, new ChunkDataS2C(3, -7, payload));
        assertEquals(3, decoded.chunkX());
        assertEquals(-7, decoded.chunkZ());
        assertArrayEquals(payload, decoded.payload());
        assertArrayEquals(new byte[0], decoded.metaPayload()); // 3-arg convenience = empty meta

        byte[] meta = {9, 8, 7};
        ChunkDataS2C withMeta = roundTrip(ChunkDataS2C.CODEC, new ChunkDataS2C(3, -7, payload, meta));
        assertArrayEquals(meta, withMeta.metaPayload());
    }

    @Test
    void chunkSnapshotIsEncodedForTheWire() {
        // #345: the server sends a storage snapshot; a TCP connection writes its encoded bytes.
        var storage = com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage.createEmpty(
                16, com.stonebreak.world.operations.WorldConfiguration.WORLD_HEIGHT, 16,
                com.stonebreak.blocks.BlockType.AIR);
        storage.set(3, 40, 5, com.stonebreak.blocks.BlockType.STONE);
        var blocks = com.openmason.engine.net.protocol.codec.ChunkBlockPayload.ofSnapshot(storage);

        ChunkDataS2C decoded = roundTrip(ChunkDataS2C.CODEC, new ChunkDataS2C(2, 9, blocks, new byte[0]));
        assertArrayEquals(blocks.encoded(), decoded.payload());
        assertEquals(null, decoded.blocks().snapshot(), "a wire carries bytes, not storage");
    }

    @Test
    void playerDataBlobsRoundTrip() {
        // PlayerData{C2S,S2C} carry an opaque JSON blob; byte[] needs array equality (record
        // equals() compares array identity, so assertEquals would falsely fail here).
        byte[] blob = "{\"inv\":[1,2,3]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(blob, roundTrip(PlayerDataS2C.CODEC, new PlayerDataS2C(blob)).json());
        // Empty blob (S2C "no saved data" sentinel) must survive the round-trip too.
        assertArrayEquals(new byte[0], roundTrip(PlayerDataS2C.CODEC, new PlayerDataS2C(new byte[0])).json());
    }

    @Test
    void multiBlockChangeRoundTrips() {
        int[] packed = {0x010203, 0x040506, 0x070809, 0xABCDEF};
        MultiBlockChangeS2C decoded = roundTrip(MultiBlockChangeS2C.CODEC, new MultiBlockChangeS2C(1, 2, 3, packed));
        assertEquals(1, decoded.sectionX());
        assertEquals(2, decoded.sectionY());
        assertEquals(3, decoded.sectionZ());
        assertArrayEquals(packed, decoded.packed());
    }

    @Test
    void itemStackPacketsKeepTheStackState() {
        // #342: every packet that carries a stack writes it with the one NetItemStack codec.
        var water = new com.stonebreak.network.packet.NetItemStack(1040, 1, "sb_wooden_bucket_water");
        assertEquals(new GiveItemS2C(water), roundTrip(GiveItemS2C.CODEC, new GiveItemS2C(water)));
        assertEquals("sb_wooden_bucket_water",
            roundTrip(GiveItemS2C.CODEC, new GiveItemS2C(water)).stack().state());
        assertEquals(new DropItemC2S(water, 81), roundTrip(DropItemC2S.CODEC, new DropItemC2S(water, 81)));
        var rod = new com.stonebreak.network.packet.NetItemStack(1031, 1, "Reeled in");
        assertEquals(new DropItemC2S(rod, 0), roundTrip(DropItemC2S.CODEC, new DropItemC2S(rod, 0)));
        org.junit.jupiter.api.Assertions.assertNull(
            roundTrip(DropItemC2S.CODEC, new DropItemC2S(12, 64)).stack().state(),
            "no state on the wire = the item's default state");
    }

    @Test
    void serverSideActionPacketsRoundTrip() {
        // #341: fishing and Mirrored Deceit are server actions now.
        var use = new com.stonebreak.network.packet.player.FishingRodC2S(1f, 65.5f, -3f, 0f, -0.5f, 1f, 82);
        assertEquals(use, roundTrip(com.stonebreak.network.packet.player.FishingRodC2S.CODEC, use));
        assertEquals(new com.stonebreak.network.packet.entity.DecoyCastC2S(),
            roundTrip(com.stonebreak.network.packet.entity.DecoyCastC2S.CODEC,
                new com.stonebreak.network.packet.entity.DecoyCastC2S()));

        PacketRegistry reg = StonebreakProtocol.registry();
        assertEquals(use, viaRegistry(reg, PLAY, SERVERBOUND, use));
        assertEquals(new com.stonebreak.network.packet.entity.DecoyCastC2S(),
            viaRegistry(reg, PLAY, SERVERBOUND, new com.stonebreak.network.packet.entity.DecoyCastC2S()));
        var water = new com.stonebreak.network.packet.NetItemStack(1040, 1, "sb_wooden_bucket_water");
        assertEquals(new GiveItemS2C(water), viaRegistry(reg, PLAY, CLIENTBOUND, new GiveItemS2C(water)));
        assertEquals(new DropItemC2S(water, 3), viaRegistry(reg, PLAY, SERVERBOUND, new DropItemC2S(water, 3)));
    }

    @Test
    void registryRoundTripsById() {
        PacketRegistry reg = StonebreakProtocol.registry();
        assertEquals(new HandshakeC2S(1, "p"), viaRegistry(reg, PLAY, SERVERBOUND, new HandshakeC2S(1, "p")));
        assertEquals(new WelcomeS2C(1, 2L, TerrainGeneratorType.STANDARD, 3f, 4f, 5f), viaRegistry(reg, PLAY, CLIENTBOUND, new WelcomeS2C(1, 2L, TerrainGeneratorType.STANDARD, 3f, 4f, 5f)));
        assertEquals(new BlockChangeC2S(1, 2, 3, (short) 4, (short) 5), viaRegistry(reg, PLAY, SERVERBOUND, new BlockChangeC2S(1, 2, 3, (short) 4, (short) 5)));
        assertEquals(new PlayerLeaveS2C(8), viaRegistry(reg, PLAY, CLIENTBOUND, new PlayerLeaveS2C(8)));
        assertEquals(new EntityDamageC2S(9, 4.5f, (byte) 1, (byte) 2, (byte) 1), viaRegistry(reg, PLAY, SERVERBOUND, new EntityDamageC2S(9, 4.5f, (byte) 1, (byte) 2, (byte) 1)));
        assertEquals(new com.stonebreak.network.packet.entity.EntityStatusEffectC2S(9, (byte) 1, 1.5f, 0f),
            viaRegistry(reg, PLAY, SERVERBOUND, new com.stonebreak.network.packet.entity.EntityStatusEffectC2S(9, (byte) 1, 1.5f, 0f)));
        assertEquals(new com.stonebreak.network.packet.entity.EntityStatusS2C(9, 1f, 0x41),
            viaRegistry(reg, PLAY, CLIENTBOUND, new com.stonebreak.network.packet.entity.EntityStatusS2C(9, 1f, 0x41)));
        assertEquals(new KeepAliveC2S(42L), viaRegistry(reg, PLAY, SERVERBOUND, new KeepAliveC2S(42L)));
        assertEquals(new DropItemC2S(3, 5), viaRegistry(reg, PLAY, SERVERBOUND, new DropItemC2S(3, 5)));
        assertEquals(new com.stonebreak.network.packet.player.InventoryResyncC2S(),
            viaRegistry(reg, PLAY, SERVERBOUND, new com.stonebreak.network.packet.player.InventoryResyncC2S()));
        assertEquals(new com.stonebreak.network.packet.world.BlockBreakStartC2S(-7, 300, 12),
            viaRegistry(reg, PLAY, SERVERBOUND, new com.stonebreak.network.packet.world.BlockBreakStartC2S(-7, 300, 12)));
        assertEquals(new KeepAliveS2C(42L, 17), viaRegistry(reg, PLAY, CLIENTBOUND, new KeepAliveS2C(42L, 17)));
        assertEquals(new TimeSyncS2C(6000L, 1f, false), viaRegistry(reg, PLAY, CLIENTBOUND, new TimeSyncS2C(6000L, 1f, false)));
        assertEquals(new com.stonebreak.network.packet.world.CheatsSetC2S(true),
            viaRegistry(reg, PLAY, SERVERBOUND, new com.stonebreak.network.packet.world.CheatsSetC2S(true)));
        assertEquals(new com.stonebreak.network.packet.world.CheatsStateS2C(true),
            viaRegistry(reg, PLAY, CLIENTBOUND, new com.stonebreak.network.packet.world.CheatsStateS2C(true)));
        assertEquals(new com.stonebreak.network.packet.world.WeatherSyncS2C(42.5, com.stonebreak.world.weather.WeatherMode.STORM, java.util.List.of()),
            viaRegistry(reg, PLAY, CLIENTBOUND,
                new com.stonebreak.network.packet.world.WeatherSyncS2C(42.5, com.stonebreak.world.weather.WeatherMode.STORM, java.util.List.of())));
        assertEquals(new com.stonebreak.network.packet.world.WeatherSetC2S(com.stonebreak.world.weather.WeatherMode.CLEAR, false),
            viaRegistry(reg, PLAY, SERVERBOUND,
                new com.stonebreak.network.packet.world.WeatherSetC2S(com.stonebreak.world.weather.WeatherMode.CLEAR, false)));
        assertEquals(new com.stonebreak.network.packet.player.ProgressS2C(9, 3, 40, 1, 2, 1, 5),
            viaRegistry(reg, PLAY, CLIENTBOUND,
                new com.stonebreak.network.packet.player.ProgressS2C(9, 3, 40, 1, 2, 1, 5)));
    }

    /** Encode/decode through the registry exactly as the pipeline does (id varint + codec). */
    private static Packet viaRegistry(PacketRegistry reg,
                                      com.openmason.engine.net.protocol.ProtocolPhase phase,
                                      com.openmason.engine.net.protocol.PacketDirection dir,
                                      Packet packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            int id = reg.idForClass(phase, dir, packet.getClass());
            ByteBufIO.writeVarInt(buf, id);
            @SuppressWarnings("unchecked")
            PacketCodec<Packet> enc = (PacketCodec<Packet>) reg.codecForClass(phase, dir, packet.getClass());
            enc.encode(buf, packet);

            int readId = ByteBufIO.readVarInt(buf);
            assertEquals(id, readId, "id mismatch for " + packet.getClass().getSimpleName());
            return reg.codecForId(phase, dir, readId).decode(buf);
        } finally {
            buf.release();
        }
    }
}
