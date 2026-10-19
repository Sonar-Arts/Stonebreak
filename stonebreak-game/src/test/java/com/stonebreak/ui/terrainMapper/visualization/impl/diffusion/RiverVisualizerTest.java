package com.stonebreak.ui.terrainMapper.visualization.impl.diffusion;

import com.stonebreak.ui.terrainMapper.visualization.PreviewChannel;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverVisualizerTest {

    private static final short NO_WATER = TerrainTile.NO_WATER;
    private static final short NO_TUNNEL = TerrainTile.NO_TUNNEL;
    private static final short NO_FLOW = TerrainTile.NO_FLOW;
    private final RiverVisualizer rivers = new RiverVisualizer(null);

    @Test
    void codesEveryKindOfColumn() {
        assertEquals(RiverVisualizer.DRY, RiverVisualizer.code(NO_WATER, NO_TUNNEL, NO_TUNNEL, NO_FLOW));
        assertEquals(5, RiverVisualizer.code(70, NO_TUNNEL, NO_TUNNEL, 5));
        assertEquals(RiverVisualizer.STILL, RiverVisualizer.code(64, NO_TUNNEL, NO_TUNNEL, NO_FLOW));
        assertEquals(RiverVisualizer.UNDERCUT, RiverVisualizer.code(NO_WATER, 69, 72, NO_FLOW));
        // A tunnel wins over flow: an overhang is flowing water too, but the 3D bank is the point.
        assertEquals(RiverVisualizer.OVERHANG, RiverVisualizer.code(70, 66, 72, 3));
        // A malformed tunnel (roof not above floor) is no tunnel.
        assertEquals(2, RiverVisualizer.code(70, 69, 69, 2));
    }

    @Test
    void everyFeatureHasItsOwnColourAndDryIsTheBackground() {
        Set<Integer> colours = new HashSet<>();
        for (int c = 0; c <= RiverVisualizer.OVERHANG; c++) {
            colours.add(rivers.colorFor(c));
        }
        assertEquals(RiverVisualizer.OVERHANG + 1, colours.size(), "flow octants and features must not share colours");
        assertNotEquals(rivers.colorFor(RiverVisualizer.DRY), rivers.colorFor(0));
        assertTrue(!colours.contains(rivers.colorFor(RiverVisualizer.DRY)));
    }

    @Test
    void describesTheColumnUnderTheCursor() {
        assertEquals("dry", rivers.formatValue(RiverVisualizer.DRY));
        assertEquals("river flowing +X", rivers.formatValue(0));
        assertEquals("river flowing -X -Z", rivers.formatValue(5));
        assertTrue(rivers.formatValue(RiverVisualizer.UNDERCUT).startsWith("undercut"));
        assertTrue(rivers.formatValue(RiverVisualizer.OVERHANG).startsWith("overhang"));
    }

    @Test
    void readsTheRiverChannelUntouched() {
        assertEquals(PreviewChannel.RIVER, rivers.channel());
        assertEquals(7f, rivers.normalize(7f));
        assertEquals((float) RiverVisualizer.DRY, rivers.normalize(RiverVisualizer.DRY));
    }
}
