package com.stonebreak.world.save.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.save.model.WorldData;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class JsonWorldSerializerTest {

    private final JsonWorldSerializer serializer = new JsonWorldSerializer();

    /* ---------- roundTripPreservesAllFields ---------- */

    @Test
    void roundTripPreservesAllFields() {
        LocalDateTime createdTime = LocalDateTime.of(2024, 3, 15, 10, 30, 45);
        LocalDateTime lastPlayed = LocalDateTime.of(2025, 7, 1, 14, 22, 10);
        Vector3f spawn = new Vector3f(10f, 80f, -20f);

        WorldData original = WorldData.builder()
            .seed(9876543210L)
            .worldName("TestWorld")
            .spawnPosition(spawn)
            .hasExplicitSpawn(true)
            .createdTime(createdTime)
            .lastPlayed(lastPlayed)
            .totalPlayTimeMillis(3600000L)
            .worldTimeTicks(12000L)
            .cheatsEnabled(true)
            .weatherSeconds(987654L)
            .weatherMode(com.stonebreak.world.weather.WeatherMode.STORM)
            .weatherZones(java.util.List.of(new com.stonebreak.world.weather.WeatherZone(
                -512.5, 2048, 1500f, com.stonebreak.world.weather.WeatherMode.RAIN)))
            .formatVersion(3)
            .build();

        byte[] serialized = serializer.serialize(original);
        WorldData deserialized = serializer.deserialize(serialized);

        assertEquals(9876543210L, deserialized.getSeed());
        assertEquals("TestWorld", deserialized.getWorldName());
        assertEquals(new Vector3d(10, 80, -20), deserialized.getSpawnPosition());
        assertTrue(deserialized.hasExplicitSpawn());
        assertEquals(createdTime, deserialized.getCreatedTime());
        assertEquals(lastPlayed, deserialized.getLastPlayed());
        assertEquals(3600000L, deserialized.getTotalPlayTimeMillis());
        assertEquals(12000L, deserialized.getWorldTimeTicks());
        assertTrue(deserialized.isCheatsEnabled());
        assertEquals(987654L, deserialized.getWeatherSeconds());
        assertEquals(com.stonebreak.world.weather.WeatherMode.STORM, deserialized.getWeatherMode());
        assertEquals(original.getWeatherZones(), deserialized.getWeatherZones());
        assertEquals(3, deserialized.getFormatVersion());
    }

    /* ---------- emittedJsonUsesCreationTimeKey ---------- */

    @Test
    void emittedJsonUsesCreationTimeKey() {
        WorldData data = WorldData.builder()
            .seed(1L)
            .worldName("TestWorld")
            .build();
        byte[] serialized = serializer.serialize(data);
        String json = new String(serialized, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"creationTime\""), "JSON should contain creationTime key");
        assertFalse(json.contains("\"createdTime\""), "JSON should NOT contain createdTime key");
    }

    /* ---------- minimalLegacyJsonGetsDefaults ---------- */

    @Test
    void minimalLegacyJsonGetsDefaults() {
        String json = "{\n"
            + "  \"seed\": 42,\n"
            + "  \"worldName\": \"LegacyWorld\",\n"
            + "  \"creationTime\": \"2024-01-01T12:00:00\",\n"
            + "  \"lastPlayed\": \"2024-06-15T18:30:00\",\n"
            + "  \"totalPlayTimeMillis\": 100\n"
            + "}";
        WorldData data = serializer.deserialize(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(6000L, data.getWorldTimeTicks());
        assertFalse(data.isCheatsEnabled());
        assertEquals(0L, data.getWeatherSeconds());
        assertEquals(com.stonebreak.world.weather.WeatherMode.AUTO, data.getWeatherMode());
        assertTrue(data.getWeatherZones().isEmpty(), "saves from before weather zones have none");
        assertEquals(1, data.getFormatVersion());
        assertFalse(data.hasExplicitSpawn());
        assertEquals(new Vector3d(0, 100, 0), data.getSpawnPosition());
    }

    /* ---------- missingSeedOrWorldNameThrows ---------- */

    @Test
    void missingSeedThrows() {
        String json = "{\n"
            + "  \"worldName\": \"TestWorld\",\n"
            + "  \"creationTime\": \"2024-01-01T12:00:00\",\n"
            + "  \"lastPlayed\": \"2024-06-15T18:30:00\",\n"
            + "  \"totalPlayTimeMillis\": 100\n"
            + "}";
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().startsWith("Failed to deserialize WorldData: "),
            "Error message should start with expected prefix, was: " + ex.getMessage());
    }

    @Test
    void missingWorldNameThrows() {
        String json = "{\n"
            + "  \"seed\": 42,\n"
            + "  \"creationTime\": \"2024-01-01T12:00:00\",\n"
            + "  \"lastPlayed\": \"2024-06-15T18:30:00\",\n"
            + "  \"totalPlayTimeMillis\": 100\n"
            + "}";
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().startsWith("Failed to deserialize WorldData: "),
            "Error message should start with expected prefix, was: " + ex.getMessage());
    }

    @Test
    void missingCreationTimeThrows() {
        String json = "{\n"
            + "  \"seed\": 42,\n"
            + "  \"worldName\": \"TestWorld\",\n"
            + "  \"lastPlayed\": \"2024-06-15T18:30:00\",\n"
            + "  \"totalPlayTimeMillis\": 100\n"
            + "}";
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().startsWith("Failed to deserialize WorldData: "),
            "Error message should start with expected prefix, was: " + ex.getMessage());
    }

    @Test
    void missingLastPlayedThrows() {
        String json = "{\n"
            + "  \"seed\": 42,\n"
            + "  \"worldName\": \"TestWorld\",\n"
            + "  \"creationTime\": \"2024-01-01T12:00:00\",\n"
            + "  \"totalPlayTimeMillis\": 100\n"
            + "}";
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().startsWith("Failed to deserialize WorldData: "),
            "Error message should start with expected prefix, was: " + ex.getMessage());
    }

    @Test
    void missingTotalPlayTimeMillisThrows() {
        String json = "{\n"
            + "  \"seed\": 42,\n"
            + "  \"worldName\": \"TestWorld\",\n"
            + "  \"creationTime\": \"2024-01-01T12:00:00\",\n"
            + "  \"lastPlayed\": \"2024-06-15T18:30:00\"\n"
            + "}";
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().startsWith("Failed to deserialize WorldData: "),
            "Error message should start with expected prefix, was: " + ex.getMessage());
    }

    /* ---------- jacksonCanReadSerializerOutput ---------- */

    @Test
    void jacksonCanReadSerializerOutput() throws Exception {
        LocalDateTime createdTime = LocalDateTime.of(2024, 3, 15, 10, 30, 45);
        LocalDateTime lastPlayed = LocalDateTime.of(2025, 7, 1, 14, 22, 10);

        WorldData original = WorldData.builder()
            .seed(9876543210L)
            .worldName("JacksonTestWorld")
            .spawnPosition(new Vector3f(5f, 75f, 15f))
            .hasExplicitSpawn(true)
            .createdTime(createdTime)
            .lastPlayed(lastPlayed)
            .totalPlayTimeMillis(500000L)
            .worldTimeTicks(10000L)
            .cheatsEnabled(true)
            .weatherSeconds(77L)
            .weatherMode(com.stonebreak.world.weather.WeatherMode.RAIN)
            .formatVersion(2)
            .build();

        byte[] serialized = serializer.serialize(original);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        WorldData jacksonRead = mapper.readValue(serialized, WorldData.class);

        assertEquals(original.getSeed(), jacksonRead.getSeed());
        assertEquals(original.getWorldName(), jacksonRead.getWorldName());
        assertEquals(original.getWorldTimeTicks(), jacksonRead.getWorldTimeTicks());
        assertEquals(original.isCheatsEnabled(), jacksonRead.isCheatsEnabled());
        assertEquals(original.getWeatherSeconds(), jacksonRead.getWeatherSeconds());
        assertEquals(original.getWeatherMode(), jacksonRead.getWeatherMode());
        assertEquals(original.getCreatedTime(), jacksonRead.getCreatedTime());
        assertEquals(original.getLastPlayed(), jacksonRead.getLastPlayed());
        assertEquals(original.getSpawnPosition(), jacksonRead.getSpawnPosition());
        assertEquals(original.getMinY(), jacksonRead.getMinY());
        assertEquals(original.getMaxY(), jacksonRead.getMaxY());
        assertEquals(original.getCubeEdge(), jacksonRead.getCubeEdge());
    }

    /* ---------- cubic format (#447): bounds, version, double spawn ---------- */

    @Test
    void newWorldsAreWrittenInTheCurrentFormatWithBounds() {
        WorldData data = WorldData.builder().seed(1L).worldName("Cubic").build();
        assertEquals(com.stonebreak.world.save.WorldFormat.CURRENT_VERSION, data.getFormatVersion());
        WorldData back = serializer.deserialize(serializer.serialize(data));
        assertEquals(com.stonebreak.world.save.WorldFormat.CURRENT_VERSION, back.getFormatVersion());
        assertEquals(com.stonebreak.world.operations.WorldConfiguration.MIN_Y, back.getMinY());
        assertEquals(com.stonebreak.world.operations.WorldConfiguration.MAX_Y, back.getMaxY());
        assertEquals(com.stonebreak.world.operations.WorldConfiguration.CUBE_EDGE, back.getCubeEdge());
        assertTrue(com.stonebreak.world.save.WorldFormat.isCompatible(back));
    }

    @Test
    void farSpawnKeepsDoublePrecisionThroughJson() {
        // Beyond 2^24 a float drops the fraction; beyond 1e7 Java writes doubles in scientific notation.
        Vector3d far = new Vector3d(123_456_789_012.5, -300.25, -9_876_543_210.75);
        WorldData data = WorldData.builder().seed(1L).worldName("Far").spawnPosition(far).build();
        WorldData back = serializer.deserialize(serializer.serialize(data));
        assertEquals(far, back.getSpawnPosition());
    }

    @Test
    void recordsWithoutBoundsReadAsTheColumnWorldAndAreIncompatible() {
        WorldData data = serializer.deserialize(("{\"seed\": 4, \"worldName\": \"Old\","
            + " \"creationTime\": \"2024-01-01T12:00:00\", \"lastPlayed\": \"2024-01-01T12:00:00\","
            + " \"totalPlayTimeMillis\": 1, \"formatVersion\": 1}").getBytes(StandardCharsets.UTF_8));
        assertEquals(com.stonebreak.world.save.WorldFormat.LEGACY_MIN_Y, data.getMinY());
        assertEquals(com.stonebreak.world.save.WorldFormat.LEGACY_MAX_Y, data.getMaxY());
        assertEquals(com.stonebreak.world.save.WorldFormat.LEGACY_EDGE, data.getCubeEdge());
        var check = com.stonebreak.world.save.WorldFormat.check(data);
        assertFalse(check.compatible());
        assertTrue(check.reason().startsWith(com.stonebreak.world.save.WorldFormat.INCOMPATIBLE), check.reason());
    }

    /* ---------- withersReturnModifiedCopies ---------- */

    @Test
    void withersReturnModifiedCopies() {
        // withWorldTime
        WorldData original = WorldData.builder()
            .seed(1L)
            .worldName("TestWorld")
            .worldTimeTicks(6000L)
            .build();
        WorldData modifiedTime = original.withWorldTime(12000L);
        assertEquals(12000L, modifiedTime.getWorldTimeTicks());
        assertEquals(6000L, original.getWorldTimeTicks(), "original should be unchanged");

        // withCheatsEnabled
        WorldData modifiedCheats = original.withCheatsEnabled(true);
        assertTrue(modifiedCheats.isCheatsEnabled());
        assertFalse(original.isCheatsEnabled(), "original should be unchanged");

        // withAddedPlayTime: 1000 + 500 = 1500
        WorldData playtimeBase = WorldData.builder()
            .seed(1L)
            .worldName("TestWorld")
            .totalPlayTimeMillis(1000L)
            .build();
        WorldData modifiedPlaytime = playtimeBase.withAddedPlayTime(500L);
        assertEquals(1500L, modifiedPlaytime.getTotalPlayTimeMillis());
    }

    /* ---------- builderRejectsMissingWorldName ---------- */

    @Test
    void builderRejectsMissingWorldName() {
        assertThrows(IllegalStateException.class, () -> WorldData.builder()
            .seed(1L)
            .build());
    }

    /* ---------- generatorType ---------- */

    @Test
    void generatorTypeRoundTrips() {
        for (TerrainGeneratorType type : TerrainGeneratorType.values()) {
            WorldData data = WorldData.builder().seed(1L).worldName("TestWorld").generatorType(type).build();
            assertEquals(type, serializer.deserialize(serializer.serialize(data)).getGeneratorType());
        }
    }

    @Test
    void newWorldsDefaultToStandard() {
        assertEquals(TerrainGeneratorType.STANDARD,
            WorldData.builder().seed(1L).worldName("TestWorld").build().getGeneratorType());
    }

    @Test
    void savesWithoutAGeneratorLoadAsDiffusion() {
        // Every save written before the choice existed was made by the diffusion generator.
        String legacy = """
            {
              "seed": 42,
              "worldName": "Old",
              "spawnPosition": { "x": 0.0, "y": 100.0, "z": 0.0 },
              "creationTime": "2026-09-01T10:00:00",
              "lastPlayed": "2026-09-01T10:00:00",
              "totalPlayTimeMillis": 0,
              "formatVersion": 1
            }
            """;
        WorldData data = serializer.deserialize(legacy.getBytes(StandardCharsets.UTF_8));
        assertEquals(TerrainGeneratorType.DIFFUSION, data.getGeneratorType());
    }

    @Test
    void withersKeepTheGenerator() {
        WorldData data = WorldData.builder().seed(1L).worldName("TestWorld")
            .generatorType(TerrainGeneratorType.DIFFUSION).build();
        assertEquals(TerrainGeneratorType.DIFFUSION, data.withWorldTime(100L).getGeneratorType());
    }
}
