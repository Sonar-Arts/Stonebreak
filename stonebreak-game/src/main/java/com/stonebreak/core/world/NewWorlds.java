package com.stonebreak.core.world;

import com.stonebreak.config.TerrainScaleOption;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.io.AtomicFiles;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Writes a new world's {@code world.json} (+ {@code metadata.json}) before the integrated server loads it - the
 * one place the per-world creation choices (generator, terrain scale, spawn) are fixed: world creation in the
 * terrain mapper and the {@code stonebreak.autoworld} dev hook both go through here.
 */
public final class NewWorlds {

    private NewWorlds() {
    }

    /** Creation options of a new world. */
    public record Options(String name, long seed, TerrainGeneratorType generator, TerrainScalePreset scale,
                          Vector3d spawn, boolean explicitSpawn) {
        public Options {
            generator = generator == null ? TerrainGeneratorType.STANDARD : generator;
            scale = scale == null ? TerrainScaleOption.DEFAULT : scale;
            spawn = spawn == null ? new Vector3d(0, 100, 0) : spawn;
        }
    }

    /** True when the world folder already holds a {@code world.json}. */
    public static boolean exists(String name) {
        return Files.exists(WorldStorage.worldDir(name).resolve("world.json"));
    }

    /** The saved world's data, or null when it has none / it cannot be read. */
    public static WorldData read(String name) {
        try {
            Path file = WorldStorage.worldDir(name).resolve("world.json");
            return Files.exists(file) ? new JsonWorldSerializer().deserialize(Files.readAllBytes(file)) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Builds the world's data and writes it; returns the data. */
    public static WorldData write(Options o) throws IOException {
        WorldData worldData = WorldData.builder()
                .worldName(o.name())
                .seed(o.seed())
                .spawnPosition(new Vector3d(o.spawn()))
                .hasExplicitSpawn(o.explicitSpawn())
                .generatorType(o.generator())
                .terrainScale(o.scale())
                .createdTime(LocalDateTime.now())
                .lastPlayed(LocalDateTime.now())
                .totalPlayTimeMillis(0L)
                .build();
        Path worldDir = WorldStorage.worldDir(o.name());
        Files.createDirectories(worldDir);
        byte[] json = new JsonWorldSerializer().serialize(worldData);
        AtomicFiles.write(worldDir.resolve("world.json"), json);
        AtomicFiles.write(worldDir.resolve("metadata.json"), json);
        return worldData;
    }
}
