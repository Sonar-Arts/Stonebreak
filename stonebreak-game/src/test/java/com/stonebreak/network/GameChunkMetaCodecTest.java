package com.stonebreak.network;

import com.stonebreak.network.bridge.GameChunkMetaCodec;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips for the game-side chunk metadata blob riding in ChunkDataS2C. */
class GameChunkMetaCodecTest {

    @Test
    void emptyMetaEncodesToZeroBytes() throws Exception {
        assertEquals(0, GameChunkMetaCodec.encode(Map.of(), Map.of(), Map.of()).length);
        assertEquals(0, GameChunkMetaCodec.encode(null, null, null).length);
        assertTrue(GameChunkMetaCodec.decode(new byte[0]).isEmpty());
        assertTrue(GameChunkMetaCodec.decode(null).isEmpty());
    }

    @Test
    void snowStatesAndWaterRoundTrip() throws Exception {
        Map<Integer, Integer> snow = new HashMap<>();
        snow.put(LocalBlockKey.pack(0, 0, 0), 1);
        snow.put(LocalBlockKey.pack(15, 255, 15), 8);
        snow.put(LocalBlockKey.pack(7, 64, 3), 4);
        Map<Integer, String> states = new HashMap<>();
        states.put(LocalBlockKey.pack(2, 70, 9), "furnace_lit;in=IRON_ORE*3");
        states.put(LocalBlockKey.pack(5, 71, 9), "");
        Map<Integer, Integer> water = new HashMap<>();
        water.put(LocalBlockKey.pack(0, 62, 0), 1);   // flowing level 1
        water.put(LocalBlockKey.pack(9, 63, 12), 7);  // flowing level 7
        water.put(LocalBlockKey.pack(15, 40, 15), 8); // falling

        byte[] blob = GameChunkMetaCodec.encode(snow, states, water);
        GameChunkMetaCodec.ChunkMeta meta = GameChunkMetaCodec.decode(blob);
        assertEquals(snow, meta.snowLayers());
        assertEquals(states, meta.blockStates());
        assertEquals(water, meta.waterLevels());
    }

    @Test
    void waterOnlyRoundTrip() throws Exception {
        Map<Integer, Integer> water = Map.of(LocalBlockKey.pack(3, 60, 4), 5);
        GameChunkMetaCodec.ChunkMeta meta =
            GameChunkMetaCodec.decode(GameChunkMetaCodec.encode(null, null, water));
        assertEquals(water, meta.waterLevels());
        assertTrue(meta.snowLayers().isEmpty());
        assertTrue(meta.blockStates().isEmpty());
    }

    @Test
    void v1BlobDecodesWithEmptyWaterSection() throws Exception {
        // Hand-crafted v1 blob: version byte 1, one snow entry, zero block states,
        // no trailing water section — the pre-water wire format.
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
        out.writeByte(1);       // version
        out.writeInt(1);        // snowCount
        out.writeByte(4);       // localX
        out.writeShort(70);     // y
        out.writeByte(11);      // localZ
        out.writeByte(3);       // layers
        out.writeInt(0);        // blockStateCount

        GameChunkMetaCodec.ChunkMeta meta = GameChunkMetaCodec.decode(buffer.toByteArray());
        assertEquals(Map.of(LocalBlockKey.pack(4, 70, 11), 3), meta.snowLayers());
        assertTrue(meta.blockStates().isEmpty());
        assertTrue(meta.waterLevels().isEmpty());
    }

    @Test
    void layersClampTo1Through8() throws Exception {
        Map<Integer, Integer> snow = Map.of(LocalBlockKey.pack(1, 2, 3), 200);
        GameChunkMetaCodec.ChunkMeta meta = GameChunkMetaCodec.decode(GameChunkMetaCodec.encode(snow, null, null));
        assertEquals(8, meta.snowLayers().get(LocalBlockKey.pack(1, 2, 3)));
    }

    @Test
    void waterValuesClampTo1Through8() throws Exception {
        Map<Integer, Integer> water = Map.of(LocalBlockKey.pack(1, 2, 3), 200);
        GameChunkMetaCodec.ChunkMeta meta = GameChunkMetaCodec.decode(GameChunkMetaCodec.encode(null, null, water));
        assertEquals(8, meta.waterLevels().get(LocalBlockKey.pack(1, 2, 3)));
    }

    @Test
    void truncatedBlobThrows() {
        byte[] blob = GameChunkMetaCodec.encode(
            Map.of(LocalBlockKey.pack(1, 2, 3), 5), Map.of(), Map.of());
        byte[] truncated = new byte[blob.length - 2];
        System.arraycopy(blob, 0, truncated, 0, truncated.length);
        assertThrows(Exception.class, () -> GameChunkMetaCodec.decode(truncated));
    }
}
