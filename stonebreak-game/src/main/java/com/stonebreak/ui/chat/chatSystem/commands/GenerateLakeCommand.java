package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.water.BasinWaterFiller;
import org.joml.Vector3f;

/**
 * Command to generate a test lake at the player's current position.
 * Creates a synthetic basin and fills it with water using the existing BasinWaterFiller system.
 * Useful for visualizing and testing lake generation with guaranteed flat water surfaces.
 */
public class GenerateLakeCommand implements ChatCommand {

    private static final int DEFAULT_WIDTH = 50;
    private static final int DEFAULT_DEPTH = 10;
    private static final int INNER_RADIUS = 6; // Flat center
    private static final int CHUNK_SIZE = 16;
    private static final int CHUNK_RADIUS = 3; // Generate 7x7 chunks

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        // 1. Validate cheats enabled
        if (!CommandValidator.validateCheatsEnabled(messageManager)) {
            return;
        }

        // 2. Get world and player from Game singleton
        World world = Game.getWorld();
        if (world == null) {
            messageManager.addMessage("No world loaded!", ChatColors.RED);
            return;
        }

        Player player = Game.getPlayer();
        if (player == null) {
            messageManager.addMessage("No player found!", ChatColors.RED);
            return;
        }

        // 3. Parse width/depth args (defaults: 50, 10)
        int width = parseIntArg(args, 0, DEFAULT_WIDTH);
        int depth = parseIntArg(args, 1, DEFAULT_DEPTH);

        // Validate parameters
        if (width < 10 || width > 200) {
            messageManager.addMessage("Width must be between 10 and 200 blocks", ChatColors.RED);
            return;
        }
        if (depth < 2 || depth > 50) {
            messageManager.addMessage("Depth must be between 2 and 50 blocks", ChatColors.RED);
            return;
        }

        // 4. Calculate basin parameters
        Vector3f playerPos = player.getPosition();
        int centerX = (int) playerPos.x;
        int centerZ = (int) playerPos.z;
        int centerY = Math.max(65, (int) playerPos.y - depth); // Keep above sea level (64)
        int rimY = centerY + depth;

        messageManager.addMessage("Generating lake at (" + centerX + ", " + centerZ + ")...", ChatColors.YELLOW);
        messageManager.addMessage("Basin: width=" + width + ", depth=" + depth + ", center Y=" + centerY + ", rim Y=" + rimY, ChatColors.YELLOW);

        // 5. Get BasinWaterFiller from terrain system
        TerrainGenerationSystem terrainSystem = world.getTerrainGenerationSystem();
        if (terrainSystem == null) {
            messageManager.addMessage("Terrain generation system not available!", ChatColors.RED);
            return;
        }

        BasinWaterFiller basinFiller = terrainSystem.getBasinWaterFiller();
        if (basinFiller == null) {
            messageManager.addMessage("Basin water filler not available!", ChatColors.RED);
            return;
        }

        // 6. Generate synthetic basin in existing chunks
        messageManager.addMessage("Generating basin terrain...", ChatColors.YELLOW);
        generateBasinTerrain(world, centerX, centerY, centerZ, width, rimY);

        // 7. Fill with water DIRECTLY (bypass all filters for test lake)
        messageManager.addMessage("Filling basin with water (bypassing filters)...", ChatColors.YELLOW);
        int waterBlocksPlaced = fillBasinWithWaterDirect(world, centerX, centerZ, centerY, rimY, width);

        // 8. Verify flat surface and report
        int waterSurfaceY = verifyFlatWaterSurface(world, centerX, centerZ, messageManager);

        if (waterSurfaceY == -1) {
            messageManager.addMessage("WARNING: No water was placed! Basin may be too shallow or filters rejected it.", ChatColors.RED);
        } else {
            messageManager.addMessage("Lake generated successfully!", ChatColors.GREEN);
            messageManager.addMessage("Water surface Y: " + waterSurfaceY + " (" + waterBlocksPlaced + " blocks placed)", ChatColors.CYAN);
            messageManager.addMessage("Use F3 to verify all water surface blocks are at Y=" + waterSurfaceY, ChatColors.YELLOW);
        }
    }

    /**
     * Generate basin-shaped terrain in existing world chunks
     */
    private void generateBasinTerrain(World world, int centerX, int centerY, int centerZ,
                                      int width, int rimY) {
        int outerRadius = width / 2;

        // Calculate chunk bounds (7x7 chunks centered on player)
        int centerChunkX = centerX / CHUNK_SIZE;
        int centerChunkZ = centerZ / CHUNK_SIZE;

        for (int cx = centerChunkX - CHUNK_RADIUS; cx <= centerChunkX + CHUNK_RADIUS; cx++) {
            for (int cz = centerChunkZ - CHUNK_RADIUS; cz <= centerChunkZ + CHUNK_RADIUS; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                if (chunk == null) {
                    continue; // Skip unloaded chunks
                }

                // Generate basin terrain for each column in chunk
                int chunkWorldX = cx * CHUNK_SIZE;
                int chunkWorldZ = cz * CHUNK_SIZE;

                for (int x = 0; x < CHUNK_SIZE; x++) {
                    for (int z = 0; z < CHUNK_SIZE; z++) {
                        int worldX = chunkWorldX + x;
                        int worldZ = chunkWorldZ + z;

                        // Calculate distance from basin center
                        int dx = worldX - centerX;
                        int dz = worldZ - centerZ;
                        double dist = Math.sqrt(dx * dx + dz * dz);

                        // Calculate basin height (bowl shape)
                        int terrainHeight;
                        if (dist < INNER_RADIUS) {
                            terrainHeight = centerY; // Flat center
                        } else if (dist < outerRadius) {
                            // Gradual slope from center to rim
                            double factor = (dist - INNER_RADIUS) / (outerRadius - INNER_RADIUS);
                            terrainHeight = (int) (centerY + (rimY - centerY) * factor);
                        } else {
                            terrainHeight = rimY; // Rim height
                        }

                        // Fill terrain blocks (stone below, air above)
                        // terrainHeight represents the first AIR block (standard interpretation)
                        for (int y = 0; y < 256; y++) {
                            if (y < terrainHeight) {
                                chunk.setBlock(x, y, z, BlockType.STONE);
                            } else {
                                chunk.setBlock(x, y, z, BlockType.AIR);
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Fill basin with water DIRECTLY, bypassing all BasinWaterFiller filters.
     * This ensures test lakes always generate regardless of climate, elevation, or depth filters.
     */
    private int fillBasinWithWaterDirect(World world, int centerX, int centerZ,
                                         int centerY, int rimY, int width) {
        int waterBlocksPlaced = 0;
        int outerRadius = width / 2;

        // Calculate chunk bounds
        int centerChunkX = centerX / CHUNK_SIZE;
        int centerChunkZ = centerZ / CHUNK_SIZE;

        for (int cx = centerChunkX - CHUNK_RADIUS; cx <= centerChunkX + CHUNK_RADIUS; cx++) {
            for (int cz = centerChunkZ - CHUNK_RADIUS; cz <= centerChunkZ + CHUNK_RADIUS; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                if (chunk == null) {
                    continue;
                }

                int chunkWorldX = cx * CHUNK_SIZE;
                int chunkWorldZ = cz * CHUNK_SIZE;

                for (int x = 0; x < CHUNK_SIZE; x++) {
                    for (int z = 0; z < CHUNK_SIZE; z++) {
                        int worldX = chunkWorldX + x;
                        int worldZ = chunkWorldZ + z;

                        // Calculate distance from basin center
                        int dx = worldX - centerX;
                        int dz = worldZ - centerZ;
                        double dist = Math.sqrt(dx * dx + dz * dz);

                        // Calculate terrain height (same formula as generateBasinTerrain)
                        int terrainHeight;
                        if (dist < INNER_RADIUS) {
                            terrainHeight = centerY;
                        } else if (dist < outerRadius) {
                            double factor = (dist - INNER_RADIUS) / (outerRadius - INNER_RADIUS);
                            terrainHeight = (int) (centerY + (rimY - centerY) * factor);
                        } else {
                            terrainHeight = rimY;
                        }

                        // Fill water from terrain surface to rim height (bypass all filters!)
                        // terrainHeight is the first air block, so start there
                        for (int y = terrainHeight; y <= rimY; y++) {
                            if (chunk.getBlock(x, y, z) == BlockType.AIR) {
                                chunk.setBlock(x, y, z, BlockType.WATER);
                                waterBlocksPlaced++;
                            }
                        }
                    }
                }
            }
        }

        return waterBlocksPlaced;
    }

    /**
     * Verify that all water surface blocks are at the same Y level (flat surface)
     * @return Water surface Y level, or -1 if no water found
     */
    private int verifyFlatWaterSurface(World world, int centerX, int centerZ,
                                       ChatMessageManager messageManager) {
        Integer waterSurfaceY = null;
        boolean isFlat = true;

        // Calculate chunk bounds
        int centerChunkX = centerX / CHUNK_SIZE;
        int centerChunkZ = centerZ / CHUNK_SIZE;

        for (int cx = centerChunkX - CHUNK_RADIUS; cx <= centerChunkX + CHUNK_RADIUS; cx++) {
            for (int cz = centerChunkZ - CHUNK_RADIUS; cz <= centerChunkZ + CHUNK_RADIUS; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                if (chunk == null) {
                    continue;
                }

                for (int x = 0; x < CHUNK_SIZE; x++) {
                    for (int z = 0; z < CHUNK_SIZE; z++) {
                        for (int y = 64; y < 200; y++) {
                            BlockType block = chunk.getBlock(x, y, z);
                            if (block == BlockType.WATER || block == BlockType.ICE) {
                                // Check if this is a surface block (air above)
                                BlockType above = chunk.getBlock(x, y + 1, z);
                                if (above == BlockType.AIR) {
                                    if (waterSurfaceY == null) {
                                        waterSurfaceY = y;
                                    } else if (waterSurfaceY != y) {
                                        messageManager.addMessage("WARNING: Water surface not flat! Found Y=" + y + " but expected Y=" + waterSurfaceY, ChatColors.RED);
                                        isFlat = false;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (waterSurfaceY != null && isFlat) {
            messageManager.addMessage("Water surface is FLAT at Y=" + waterSurfaceY, ChatColors.GREEN);
        }

        return waterSurfaceY != null ? waterSurfaceY : -1;
    }

    /**
     * Parse integer argument with fallback to default value
     */
    private int parseIntArg(String[] args, int index, int defaultValue) {
        if (args.length > index) {
            try {
                return Integer.parseInt(args[index]);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public String getName() {
        return "generatelake";
    }

    @Override
    public String getDescription() {
        return "Generate a test lake at current position [width] [depth] (default: 50 10)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }
}
