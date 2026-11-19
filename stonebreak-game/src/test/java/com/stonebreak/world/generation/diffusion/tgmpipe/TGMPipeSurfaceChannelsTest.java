package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.SurfaceChannel;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TGMPipe v3 surface block: channels by name, unknown names skipped, all-NO_DATA planes dropped. */
class TGMPipeSurfaceChannelsTest {
    private static final int N = 3;

    private static TGMPipeProtocol.Frame tile(String[] names, int[][] values, int extraBytes) {
        int cells = N * N;
        int head = 1;
        for (String n : names) {
            head += 1 + n.length() + 8;
        }
        ByteBuffer b = ByteBuffer.allocate(17 + TGMPipeProtocol.PLANES * cells * 2 + head + names.length * cells + extraBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(7).put((byte) 0).putShort((short) N).putShort((short) N).putInt(10).putInt(20);
        for (int p = 0; p < TGMPipeProtocol.PLANES; p++) {
            for (int k = 0; k < cells; k++) {
                b.putShort((short) (p == 3 || p == 4 || p == 5 ? -1 : k));
            }
        }
        b.put((byte) names.length);
        for (String n : names) {
            byte[] a = n.getBytes(StandardCharsets.US_ASCII);
            b.put((byte) a.length).put(a).putFloat(1f / 254f).putFloat(0f);
        }
        for (int[] plane : values) {
            for (int v : plane) {
                b.put((byte) v);
            }
        }
        for (int k = 0; k < extraBytes; k++) {
            b.put((byte) 0);   // trailing garbage after the surface block
        }
        b.flip();
        return new TGMPipeProtocol.Frame(TGMPipeProtocol.TILE_DATA, b.order(ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void decodesKnownChannelsByNameAndSkipsUnknownOnes() {
        int[] cover = {0, 10, 20, 30, 40, 50, 60, 70, 254};
        int[] unknown = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] height = {255, 255, 4, 255, 255, 255, 255, 255, 255};
        TerrainTile t = TGMPipeProtocol.decodeTile(
                tile(new String[]{"future_channel", "canopy_cover", "canopy_height"}, new int[][]{unknown, cover, height}, 0), 0, 0);
        assertTrue(t.surface().has(SurfaceChannel.CANOPY_COVER));
        assertEquals(20, t.surfaceAt(SurfaceChannel.CANOPY_COVER, 10, 22));     // row 0 = x 10, col 2 = z 22
        assertEquals(254, t.surfaceAt(SurfaceChannel.CANOPY_COVER, 12, 22));
        assertEquals(4, t.surfaceAt(SurfaceChannel.CANOPY_HEIGHT, 10, 22));
        assertEquals(SurfaceChannel.NO_DATA, t.surfaceAt(SurfaceChannel.CANOPY_HEIGHT, 10, 20));
        assertEquals(SurfaceChannel.NO_DATA, t.surfaceAt(SurfaceChannel.BOULDER_DENSITY, 10, 20));
        assertEquals(2, t.heightAt(10, 22));
    }

    @Test
    void emptyBlockAndAllNoDataPlanesMeanNoChannel() {
        TerrainTile none = TGMPipeProtocol.decodeTile(tile(new String[0], new int[0][], 0), 0, 0);
        assertTrue(none.surface().isEmpty());
        int[] empty = new int[N * N];
        java.util.Arrays.fill(empty, 255);
        TerrainTile t = TGMPipeProtocol.decodeTile(tile(new String[]{"clearing"}, new int[][]{empty}, 0), 0, 0);
        assertFalse(t.surface().has(SurfaceChannel.CLEARING));
    }

    @Test
    void rejectsTrailingBytes() {
        assertThrows(IllegalStateException.class,
                () -> TGMPipeProtocol.decodeTile(tile(new String[0], new int[0][], 2), 0, 0));
    }

    @Test
    void slotsAreUnique() {
        boolean[] seen = new boolean[SurfaceChannel.SLOTS];
        for (SurfaceChannel c : SurfaceChannel.values()) {
            assertFalse(seen[c.slot()], c + " reuses a slot");
            seen[c.slot()] = true;
            assertEquals(c, SurfaceChannel.byWireName(c.wireName()));
        }
    }
}
