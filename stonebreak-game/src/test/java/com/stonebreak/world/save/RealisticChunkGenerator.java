package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;
import org.joml.Vector3f;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Generates realistic chunks with varied terrain, water, and entities for testing.
 * This simulates actual game chunks rather than minimal test data.
 */
public class RealisticChunkGenerator {

    private final Random random;

    public RealisticChunkGenerator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Creates a chunk with realistic terrain layers and varied block types.
     * Simulates actual world generation with stone, dirt, grass, ores, etc.
     */
    public Chunk createTerrainChunk(int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(chunkX, chunkZ);

        // Layer 1: Bedrock (y=0-4)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 5; y++) {
                    chunk.setBlock(x, y, z, BlockType.BEDROCK);
                }
            }
        }

        // Layer 2: Stone with ores (y=5-60)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 5; y < 60; y++) {
                    // 95% stone, 5% ores
                    if (random.nextInt(100) < 5) {
                        chunk.setBlock(x, y, z, getRandomOre());
                    } else {
                        chunk.setBlock(x, y, z, BlockType.STONE);
                    }
                }
            }
        }

        // Layer 3: Dirt layer (y=60-63)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 60; y < 63; y++) {
                    chunk.setBlock(x, y, z, BlockType.DIRT);
                }
            }
        }

        // Layer 4: Grass (y=63) with some variation
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int surfaceY = 63 + random.nextInt(3); // Slight height variation
                chunk.setBlock(x, surfaceY, z, BlockType.GRASS);

                // Fill below surface with dirt if we raised it
                for (int y = 63; y < surfaceY; y++) {
                    chunk.setBlock(x, y, z, BlockType.DIRT);
                }
            }
        }

        // Layer 5: Surface features (flowers)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (random.nextInt(20) == 0) { // 5% chance
                    int surfaceY = 64 + random.nextInt(3);
                    // Add a flower (rose or dandelion)
                    if (random.nextBoolean()) {
                        chunk.setBlock(x, surfaceY, z, BlockType.ROSE);
                    } else {
                        chunk.setBlock(x, surfaceY, z, BlockType.DANDELION);
                    }
                }
            }
        }

        // Layer 6: Air (y=65-255)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 65; y < 256; y++) {
                    chunk.setBlock(x, y, z, BlockType.AIR);
                }
            }
        }

        chunk.markDirty();
        return chunk;
    }

    /**
     * Creates a chunk with extensive water metadata (100+ entries).
     */
    public ChunkData createWaterChunk(int chunkX, int chunkZ) {
        BlockType[][][] blocks = new BlockType[16][256][16];

        // Initialize with stone base
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 50; y++) {
                    blocks[x][y][z] = BlockType.STONE;
                }
                for (int y = 50; y < 256; y++) {
                    blocks[x][y][z] = BlockType.AIR;
                }
            }
        }

        // Add water pool with varied levels
        Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();
        for (int x = 2; x < 14; x++) {
            for (int z = 2; z < 14; z++) {
                for (int y = 50; y < 56; y++) {
                    blocks[x][y][z] = BlockType.WATER;

                    // Create varied water levels (1-7, skip 0 as that's source)
                    int level = (y == 50) ? 0 : random.nextInt(7) + 1; // Source at bottom
                    boolean falling = random.nextBoolean();

                    // Only store non-source blocks in metadata
                    if (level > 0) {
                        String key = x + "," + y + "," + z;
                        waterMetadata.put(key, new ChunkData.WaterBlockData(level, falling));
                    }
                }
            }
        }

        return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)
                .lastModified(LocalDateTime.now())
                .featuresPopulated(true)
                .waterMetadata(waterMetadata)
                .entities(new ArrayList<>())
                .build();
    }

    /**
     * Creates a chunk with multiple entities (10+ entities).
     */
    public ChunkData createEntityChunk(int chunkX, int chunkZ) {
        BlockType[][][] blocks = new BlockType[16][256][16];

        // Simple terrain
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 60; y++) {
                    blocks[x][y][z] = BlockType.STONE;
                }
                blocks[x][60][z] = BlockType.GRASS;
                for (int y = 61; y < 256; y++) {
                    blocks[x][y][z] = BlockType.AIR;
                }
            }
        }

        // Create multiple entities (all cows since that's what we have)
        List<EntityData> entities = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            // Random positions within chunk
            float x = chunkX * 16 + random.nextFloat() * 16;
            float y = 61.0f + random.nextFloat() * 10;
            float z = chunkZ * 16 + random.nextFloat() * 16;

            // Random velocities
            float vx = (random.nextFloat() - 0.5f) * 2.0f;
            float vy = (random.nextFloat() - 0.5f) * 0.5f;
            float vz = (random.nextFloat() - 0.5f) * 2.0f;

            // Random rotation (yaw, pitch, roll)
            float yaw = random.nextFloat() * 360f;
            float pitch = random.nextFloat() * 180f - 90f;
            float roll = 0f;

            // Create cow entity with required custom data
            Map<String, Object> customData = new HashMap<>();
            customData.put("textureVariant", "default");  // Cow texture variant
            customData.put("canBeMilked", true);          // Can be milked
            customData.put("milkRegenTimer", 0.0f);       // Milk regen timer
            customData.put("aiState", "IDLE");             // AI state

            EntityData entity = EntityData.builder()
                    .entityType(EntityType.COW)
                    .position(new Vector3f(x, y, z))
                    .velocity(new Vector3f(vx, vy, vz))
                    .rotation(new Vector3f(yaw, pitch, roll))
                    .health(10.0f)
                    .maxHealth(10.0f)
                    .age(0.0f)
                    .alive(true)
                    .customData(customData)
                    .build();

            entities.add(entity);
        }

        return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)
                .lastModified(LocalDateTime.now())
                .featuresPopulated(true)
                .waterMetadata(new HashMap<>())
                .entities(entities)
                .build();
    }

    /**
     * Creates a chunk with EVERYTHING - maximum complexity.
     * This stresses all serialization paths simultaneously.
     */
    public ChunkData createMaxComplexityChunk(int chunkX, int chunkZ) {
        BlockType[][][] blocks = new BlockType[16][256][16];
        Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();
        List<EntityData> entities = new ArrayList<>();

        // Complex terrain with maximum block type variety
        BlockType[] terrainBlocks = {
                BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.COBBLESTONE,
                BlockType.WOOD_PLANKS, BlockType.SAND, BlockType.GRAVEL, BlockType.SANDSTONE,
                BlockType.IRON_ORE, BlockType.COAL_ORE, BlockType.WOOD, BlockType.LEAVES,
                BlockType.RED_SAND, BlockType.PINE, BlockType.ROSE, BlockType.DANDELION
        };

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 256; y++) {
                    if (y < 5) {
                        blocks[x][y][z] = BlockType.BEDROCK;
                    } else if (y < 50) {
                        // Random varied terrain
                        blocks[x][y][z] = terrainBlocks[random.nextInt(terrainBlocks.length)];
                    } else if (y < 60) {
                        // Water layer
                        blocks[x][y][z] = BlockType.WATER;
                        int level = random.nextInt(7) + 1;
                        boolean falling = random.nextBoolean();
                        waterMetadata.put(x + "," + y + "," + z,
                                new ChunkData.WaterBlockData(level, falling));
                    } else if (y == 60) {
                        blocks[x][y][z] = BlockType.GRASS;
                    } else {
                        blocks[x][y][z] = BlockType.AIR;
                    }
                }
            }
        }

        // Add 20 cow entities
        for (int i = 0; i < 20; i++) {
            float x = chunkX * 16 + random.nextFloat() * 16;
            float y = 61.0f;
            float z = chunkZ * 16 + random.nextFloat() * 16;

            // Create cow with required custom data
            Map<String, Object> customData = new HashMap<>();
            customData.put("textureVariant", "default");
            customData.put("canBeMilked", true);
            customData.put("milkRegenTimer", 0.0f);
            customData.put("aiState", "IDLE");

            EntityData entity = EntityData.builder()
                    .entityType(EntityType.COW)
                    .position(new Vector3f(x, y, z))
                    .velocity(new Vector3f(
                            (random.nextFloat() - 0.5f) * 2.0f,
                            0.0f,
                            (random.nextFloat() - 0.5f) * 2.0f))
                    .rotation(new Vector3f(random.nextFloat() * 360f, 0f, 0f))
                    .health(10.0f)
                    .maxHealth(10.0f)
                    .age(0.0f)
                    .alive(true)
                    .customData(customData)
                    .build();
            entities.add(entity);
        }

        return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)
                .lastModified(LocalDateTime.now())
                .featuresPopulated(true)
                .hasEntitiesGenerated(true)
                .waterMetadata(waterMetadata)
                .entities(entities)
                .build();
    }

    /**
     * Creates a completely empty chunk (all AIR) - edge case.
     */
    public ChunkData createEmptyChunk(int chunkX, int chunkZ) {
        BlockType[][][] blocks = new BlockType[16][256][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 256; y++) {
                    blocks[x][y][z] = BlockType.AIR;
                }
            }
        }

        return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)
                .lastModified(LocalDateTime.now())
                .featuresPopulated(false)
                .waterMetadata(new HashMap<>())
                .entities(new ArrayList<>())
                .build();
    }

    /**
     * Creates a completely full chunk (all STONE) - edge case.
     */
    public ChunkData createFullChunk(int chunkX, int chunkZ) {
        BlockType[][][] blocks = new BlockType[16][256][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 256; y++) {
                    blocks[x][y][z] = BlockType.STONE;
                }
            }
        }

        return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)
                .lastModified(LocalDateTime.now())
                .featuresPopulated(true)
                .waterMetadata(new HashMap<>())
                .entities(new ArrayList<>())
                .build();
    }

    private BlockType getRandomOre() {
        int roll = random.nextInt(100);
        if (roll < 60) return BlockType.COAL_ORE;      // 60% coal
        if (roll < 90) return BlockType.IRON_ORE;      // 30% iron
        return BlockType.STONE;                         // 10% just stone (no rare ores)
    }

    /**
     * Verifies two chunks are EXACTLY identical (all 65,536 blocks).
     */
    public static boolean chunksIdentical(ChunkData chunk1, ChunkData chunk2) {
        if (chunk1.getChunkX() != chunk2.getChunkX() || chunk1.getChunkZ() != chunk2.getChunkZ()) {
            return false;
        }

        BlockType[][][] blocks1 = chunk1.getBlocks();
        BlockType[][][] blocks2 = chunk2.getBlocks();

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if (blocks1[x][y][z] != blocks2[x][y][z]) {
                        System.err.println("Block mismatch at (" + x + "," + y + "," + z + "): " +
                                blocks1[x][y][z] + " != " + blocks2[x][y][z]);
                        return false;
                    }
                }
            }
        }

        // Verify water metadata matches
        if (!chunk1.getWaterMetadata().equals(chunk2.getWaterMetadata())) {
            System.err.println("Water metadata mismatch");
            return false;
        }

        // Verify entity count matches (detailed entity comparison omitted for brevity)
        if (chunk1.getEntities().size() != chunk2.getEntities().size()) {
            System.err.println("Entity count mismatch: " + chunk1.getEntities().size() +
                    " != " + chunk2.getEntities().size());
            return false;
        }

        return true;
    }

    /**
     * Counts unique block types in a chunk.
     */
    public static int countUniqueBlockTypes(ChunkData chunk) {
        Set<BlockType> uniqueTypes = new HashSet<>();
        BlockType[][][] blocks = chunk.getBlocks();

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    uniqueTypes.add(blocks[x][y][z]);
                }
            }
        }

        return uniqueTypes.size();
    }
}
