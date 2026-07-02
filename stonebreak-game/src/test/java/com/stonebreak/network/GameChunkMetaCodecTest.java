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
        assertEquals(0, GameChunkMetaCodec.encode(Map.of(), Map.of()).length);
        assertEquals(0, GameChunkMetaCodec.encode(null, null).length);
        assertTrue(GameChunkMetaCodec.decode(new byte[0]).isEmpty());
        assertTrue(GameChunkMetaCodec.decode(null).isEmpty());
    }

    @Test
    void snowAndStatesRoundTrip() throws Exception {
        Map<Integer, Integer> snow = new HashMap<>();
        snow.put(LocalBlockKey.pack(0, 0, 0), 1);
        snow.put(LocalBlockKey.pack(15, 255, 15), 8);
        snow.put(LocalBlockKey.pack(7, 64, 3), 4);
        Map<Integer, String> states = new HashMap<>();
        states.put(LocalBlockKey.pack(2, 70, 9), "furnace_lit;in=IRON_ORE*3");
        states.put(LocalBlockKey.pack(5, 71, 9), "");

        byte[] blob = GameChunkMetaCodec.encode(snow, states);
        GameChunkMetaCodec.ChunkMeta meta = GameChunkMetaCodec.decode(blob);
        assertEquals(snow, meta.snowLayers());
        assertEquals(states, meta.blockStates());
    }

    @Test
    void layersClampTo1Through8() throws Exception {
        Map<Integer, Integer> snow = Map.of(LocalBlockKey.pack(1, 2, 3), 200);
        GameChunkMetaCodec.ChunkMeta meta = GameChunkMetaCodec.decode(GameChunkMetaCodec.encode(snow, null));
        assertEquals(8, meta.snowLayers().get(LocalBlockKey.pack(1, 2, 3)));
    }

    @Test
    void truncatedBlobThrows() {
        byte[] blob = GameChunkMetaCodec.encode(
            Map.of(LocalBlockKey.pack(1, 2, 3), 5), Map.of());
        byte[] truncated = new byte[blob.length - 2];
        System.arraycopy(blob, 0, truncated, 0, truncated.length);
        assertThrows(Exception.class, () -> GameChunkMetaCodec.decode(truncated));
    }
}
