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
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.KillCreditS2C;
import com.stonebreak.network.packet.player.PlayerDataC2S;
import com.stonebreak.network.packet.player.PlayerDataS2C;
import com.stonebreak.network.packet.player.PlayerHeldItemC2S;
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
import com.stonebreak.network.packet.world.FurnaceSlotsC2S;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.network.packet.world.SnowLayerC2S;
import com.stonebreak.network.packet.world.TimeSyncS2C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.net.protocol.PacketDirection.CLIENTBOUND;
import static com.openmason.engine.net.protocol.PacketDirection.SERVERBOUND;
import static com.openmason.engine.net.protocol.ProtocolPhase.PLAY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * §5d — every packet's codec round-trips, and the {@link StonebreakProtocol} registry maps
 * ids ↔ codecs without collisions. The "no leftover bytes" check catches field drift
 * between encode and decode.
 */
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
        assertEquals(new WelcomeS2C(7, 123456789L, 1f, 64f, 2f), roundTrip(WelcomeS2C.CODEC, new WelcomeS2C(7, 123456789L, 1f, 64f, 2f)));
        assertEquals(new KickS2C("bye"), roundTrip(KickS2C.CODEC, new KickS2C("bye")));
        assertEquals(new DisconnectC2S("leaving"), roundTrip(DisconnectC2S.CODEC, new DisconnectC2S("leaving")));
        assertEquals(new BlockChangeC2S(10, 64, -5, (short) 3, (short) 1), roundTrip(BlockChangeC2S.CODEC, new BlockChangeC2S(10, 64, -5, (short) 3, (short) 1)));
        assertEquals(new BlockChangeS2C(10, 64, -5, (short) 3), roundTrip(BlockChangeS2C.CODEC, new BlockChangeS2C(10, 64, -5, (short) 3)));
        assertEquals(new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0), roundTrip(PlayerStateC2S.CODEC, new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0)));
        assertEquals(new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0xFF), roundTrip(PlayerStateC2S.CODEC, new PlayerStateC2S(1f, 2f, 3f, 90f, -10f, (byte) 0xFF)));
        assertEquals(new PlayerStateS2C(4, 1f, 2f, 3f, 90f, -10f, (byte) 0x3F), roundTrip(PlayerStateS2C.CODEC, new PlayerStateS2C(4, 1f, 2f, 3f, 90f, -10f, (byte) 0x3F)));
        assertEquals(new PlayerHeldItemC2S(5), roundTrip(PlayerHeldItemC2S.CODEC, new PlayerHeldItemC2S(5)));
        assertEquals(new PlayerHeldItemS2C(2, 5), roundTrip(PlayerHeldItemS2C.CODEC, new PlayerHeldItemS2C(2, 5)));
        assertEquals(new GiveItemS2C(3, 64), roundTrip(GiveItemS2C.CODEC, new GiveItemS2C(3, 64)));
        assertEquals(new PlayerJoinS2C(2, "bob", 1f, 2f, 3f), roundTrip(PlayerJoinS2C.CODEC, new PlayerJoinS2C(2, "bob", 1f, 2f, 3f)));
        assertEquals(new PlayerLeaveS2C(2), roundTrip(PlayerLeaveS2C.CODEC, new PlayerLeaveS2C(2)));
        assertEquals(new ChatMessageC2S("hi"), roundTrip(ChatMessageC2S.CODEC, new ChatMessageC2S("hi")));
        assertEquals(new ChatMessageS2C(1, "bob", "hi"), roundTrip(ChatMessageS2C.CODEC, new ChatMessageS2C(1, "bob", "hi")));
        assertEquals(new EntitySpawnS2C(9, 1, 1f, 2f, 3f, 90f, "variant"), roundTrip(EntitySpawnS2C.CODEC, new EntitySpawnS2C(9, 1, 1f, 2f, 3f, 90f, "variant")));
        assertEquals(new EntityDespawnS2C(9), roundTrip(EntityDespawnS2C.CODEC, new EntityDespawnS2C(9)));
        assertEquals(new EntityDamageC2S(9, 4.5f, (byte) 1), roundTrip(EntityDamageC2S.CODEC, new EntityDamageC2S(9, 4.5f, (byte) 1)));
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
        assertEquals(new DropItemC2S(12, 64), roundTrip(DropItemC2S.CODEC, new DropItemC2S(12, 64)));
        assertEquals(new SnowLayerC2S(10, 64, -5, (byte) 8), roundTrip(SnowLayerC2S.CODEC, new SnowLayerC2S(10, 64, -5, (byte) 8)));
        assertEquals(new SnowLayerC2S(0, 0, 0, (byte) 0), roundTrip(SnowLayerC2S.CODEC, new SnowLayerC2S(0, 0, 0, (byte) 0)));
        assertEquals(new BlockStateS2C(10, 64, -5, "furnace:state=Lit;ing=B:1:3;fuel=I:20:2;out=B:5:1;burn=100;burnTotal=200;cook=40"),
            roundTrip(BlockStateS2C.CODEC, new BlockStateS2C(10, 64, -5, "furnace:state=Lit;ing=B:1:3;fuel=I:20:2;out=B:5:1;burn=100;burnTotal=200;cook=40")));
        assertEquals(new FurnaceSlotsC2S(10, 64, -5, "B:1:3|I:20:2|B:0:0"),
            roundTrip(FurnaceSlotsC2S.CODEC, new FurnaceSlotsC2S(10, 64, -5, "B:1:3|I:20:2|B:0:0")));
        assertEquals(new ChunkResyncRequestC2S(-3, 12), roundTrip(ChunkResyncRequestC2S.CODEC, new ChunkResyncRequestC2S(-3, 12)));
        assertEquals(new KillCreditS2C(2, 7.5f, true, 15), roundTrip(KillCreditS2C.CODEC, new KillCreditS2C(2, 7.5f, true, 15)));
        assertEquals(new KillCreditS2C(0, 1f, false, 0), roundTrip(KillCreditS2C.CODEC, new KillCreditS2C(0, 1f, false, 0)));
        assertEquals(new EntityResyncC2S(), roundTrip(EntityResyncC2S.CODEC, new EntityResyncC2S()));
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
    }

    @Test
    void chunkHashesRoundTrip() {
        int[] entries = {1, -2, 0xDEADBEEF, 3, 4, 0};
        assertArrayEquals(entries,
            roundTrip(ChunkHashesC2S.CODEC, new ChunkHashesC2S(entries)).entries());
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
    void playerDataBlobsRoundTrip() {
        // PlayerData{C2S,S2C} carry an opaque JSON blob; byte[] needs array equality (record
        // equals() compares array identity, so assertEquals would falsely fail here).
        byte[] blob = "{\"inv\":[1,2,3]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(blob, roundTrip(PlayerDataC2S.CODEC, new PlayerDataC2S(blob)).json());
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
    void registryRoundTripsById() {
        PacketRegistry reg = StonebreakProtocol.registry();
        assertEquals(new HandshakeC2S(1, "p"), viaRegistry(reg, PLAY, SERVERBOUND, new HandshakeC2S(1, "p")));
        assertEquals(new WelcomeS2C(1, 2L, 3f, 4f, 5f), viaRegistry(reg, PLAY, CLIENTBOUND, new WelcomeS2C(1, 2L, 3f, 4f, 5f)));
        assertEquals(new BlockChangeC2S(1, 2, 3, (short) 4, (short) 5), viaRegistry(reg, PLAY, SERVERBOUND, new BlockChangeC2S(1, 2, 3, (short) 4, (short) 5)));
        assertEquals(new PlayerLeaveS2C(8), viaRegistry(reg, PLAY, CLIENTBOUND, new PlayerLeaveS2C(8)));
        assertEquals(new EntityDamageC2S(9, 4.5f, (byte) 1), viaRegistry(reg, PLAY, SERVERBOUND, new EntityDamageC2S(9, 4.5f, (byte) 1)));
        assertEquals(new KeepAliveC2S(42L), viaRegistry(reg, PLAY, SERVERBOUND, new KeepAliveC2S(42L)));
        assertEquals(new DropItemC2S(3, 5), viaRegistry(reg, PLAY, SERVERBOUND, new DropItemC2S(3, 5)));
        assertEquals(new KeepAliveS2C(42L, 17), viaRegistry(reg, PLAY, CLIENTBOUND, new KeepAliveS2C(42L, 17)));
        assertEquals(new TimeSyncS2C(6000L, 1f, false), viaRegistry(reg, PLAY, CLIENTBOUND, new TimeSyncS2C(6000L, 1f, false)));
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
