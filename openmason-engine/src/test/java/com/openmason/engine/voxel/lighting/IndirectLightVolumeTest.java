package com.openmason.engine.voxel.lighting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndirectLightVolumeTest {
    private static final int SIZE = 15;

    @Test void emptySpaceDoesNotInventReflectedLight() {
        IndirectLightVolume volume = new IndirectLightVolume(SIZE);
        volume.rebuild(5.5f, 5.5f, 5.5f, 9.5f);
        for (float value : volume.data()) assertEquals(0, value);
    }

    @Test void illuminatedFloorReflectsWeakLightThatDecaysThroughAir() {
        IndirectLightVolume volume = new IndirectLightVolume(SIZE);
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) volume.setCell(x, 2, z, true, true);
        volume.rebuild(7.5f, 4.6f, 7.5f, 9.5f);
        assertTrue(volume.get(7, 3, 7) > .05f);
        assertTrue(volume.get(7, 3, 7) > volume.get(7, 4, 7));
        assertTrue(volume.get(7, 4, 7) > volume.get(7, 5, 7));
        assertEquals(0, volume.get(7, 2, 7), "solid floor contains no propagated light");
        for (float value : volume.data()) assertTrue(value >= 0 && value <= IndirectLightVolume.REFLECTANCE);
    }

    @Test void solidWallAndSealedRoomCannotReceiveLightThroughTheirWalls() {
        IndirectLightVolume volume = new IndirectLightVolume(SIZE);
        for (int y = 0; y < SIZE; y++) for (int z = 0; z < SIZE; z++) volume.setCell(7, y, z, true, true);
        volume.rebuild(5.5f, 7.5f, 7.5f, 9.5f);
        assertTrue(volume.get(6, 7, 7) > .05f);
        for (int x = 7; x < SIZE; x++) for (int y = 0; y < SIZE; y++) for (int z = 0; z < SIZE; z++)
            assertEquals(0, volume.get(x, y, z), "wall must block seeds and propagation");
    }

    @Test void fillTravelsAroundAnExposedCornerAndFadesBehindIt() {
        IndirectLightVolume volume = new IndirectLightVolume(SIZE);
        for (int y = 0; y < SIZE; y++) for (int z = 4; z <= 8; z++) volume.setCell(7, y, z, true, true);
        volume.rebuild(5.5f, 7.5f, 6.5f, 9.5f);
        float directlyReflected = volume.get(6, 7, 4);
        float aroundCorner = volume.get(8, 7, 4);
        assertTrue(aroundCorner > 0, "dim fill reaches behind the wall around its edge");
        assertTrue(aroundCorner < directlyReflected * .4f, "the detour loses energy");
        assertEquals(0, volume.get(9, 7, 6), "bounded bounce does not flood deep shadows");
        for (int y = 0; y < SIZE; y++) for (int z = 4; z <= 8; z++) volume.setCell(7, y, z, false, false);
        volume.rebuild(5.5f, 7.5f, 6.5f, 9.5f);
        for (float value : volume.data()) assertEquals(0, value, "removed surfaces leave no stale light");
    }

    @Test void unknownRegionsBlockButNeverReflectAndEnclosedSourcesEmitNothing() {
        IndirectLightVolume volume = new IndirectLightVolume(SIZE);
        for (int y = 0; y < SIZE; y++) for (int z = 0; z < SIZE; z++) volume.setCell(7, y, z, true, false);
        volume.rebuild(5.5f, 7.5f, 7.5f, 9.5f);
        for (float value : volume.data()) assertEquals(0, value);
        volume.setCell(5, 7, 7, true, true);
        volume.rebuild(5.5f, 7.5f, 7.5f, 9.5f);
        for (float value : volume.data()) assertEquals(0, value);
    }
}
