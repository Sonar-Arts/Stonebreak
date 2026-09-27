package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerRegistry;
import com.stonebreak.world.generation.TerrainGeneratorType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapper offers nothing until a generator is picked, then exactly that generator's modes.
 * Building the Diffusion previews does no I/O; autostart is off so nothing here could launch
 * the terrain services even by accident.
 */
class GeneratorSelectionTest {

    @BeforeAll
    static void withoutLaunchingTerrainServices() {
        System.setProperty("stonebreak.terrainService.autostart", "false");
    }

    @Test
    void registryIsEmptyUntilAGeneratorIsPicked() {
        VisualizerRegistry registry = new VisualizerRegistry(1234L);
        assertNull(registry.generatorType());
        assertTrue(registry.modes().isEmpty());
        assertNull(registry.get(VisualizerKind.HEIGHT));
        assertNull(registry.get(null));
    }

    @Test
    void eachGeneratorBindsItsOwnModes() {
        VisualizerRegistry registry = new VisualizerRegistry(1234L);

        registry.selectGenerator(TerrainGeneratorType.STANDARD);
        assertEquals(List.of(VisualizerKind.HEIGHT, VisualizerKind.CONTINENTALNESS, VisualizerKind.EROSION,
                VisualizerKind.PEAKS_VALLEYS, VisualizerKind.TEMPERATURE, VisualizerKind.MOISTURE,
                VisualizerKind.BIOME), registry.modes());
        registry.modes().forEach(kind -> assertNotNull(registry.get(kind), kind + " bound"));
        assertNull(registry.get(VisualizerKind.WATER), "Standard has no water mode");
        assertNull(registry.previewSource(), "Standard resamples instead of caching");

        registry.selectGenerator(TerrainGeneratorType.DIFFUSION);
        assertEquals(List.of(VisualizerKind.HEIGHT, VisualizerKind.TOPOGRAPHY, VisualizerKind.BIOME,
                VisualizerKind.WATER, VisualizerKind.RIVERS), registry.modes());
        registry.modes().forEach(kind -> assertNotNull(registry.get(kind), kind + " bound"));
        assertNull(registry.get(VisualizerKind.EROSION), "Diffusion has no erosion mode");
        assertNotNull(registry.previewSource());

        registry.rebuild(99L);
        assertEquals(TerrainGeneratorType.DIFFUSION, registry.generatorType(), "a seed change keeps the generator");
        assertEquals(99L, registry.previewSource().seed());

        registry.clearGenerator();
        assertTrue(registry.modes().isEmpty());
    }

    @Test
    void pickingAGeneratorFillsInTheModeButtonsAndEnablesCreate() {
        TerrainMapperStateManager state = new TerrainMapperStateManager();
        assertTrue(state.getModeButtons().isEmpty());
        assertNull(state.getActiveVisualizer());
        assertFalse(state.getCreateButton().enabled());
        assertEquals(TerrainGeneratorType.values().length, state.getGeneratorButtons().size());

        state.selectGenerator(TerrainGeneratorType.DIFFUSION);
        assertEquals(5, state.getModeButtons().size());
        assertEquals(VisualizerKind.HEIGHT, state.getActiveVisualizer());
        assertTrue(state.getCreateButton().enabled());

        state.getModeButtons().get(1).click();
        assertEquals(VisualizerKind.TOPOGRAPHY, state.getActiveVisualizer());

        state.selectGenerator(TerrainGeneratorType.STANDARD);
        assertEquals(7, state.getModeButtons().size());
        assertEquals(VisualizerKind.HEIGHT, state.getActiveVisualizer(), "a new generator starts on its first mode");

        state.reset();
        assertNull(state.getSelectedGenerator());
        assertTrue(state.getModeButtons().isEmpty());
        assertFalse(state.getCreateButton().enabled());
        state.dispose();
    }
}
