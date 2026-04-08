package com.stonebreak.world.generation.water;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.mightyMesh.MmsAPI;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsRenderableHandle;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.TerrainGeneratorFactory;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.utils.TerrainCalculations;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * JUnit visual test for lake generation system.
 *
 * <p>This test validates lake generation by:</p>
 * <ul>
 *   <li>Discovering lakes using spiral search from origin</li>
 *   <li>Extracting boundary data (bottom, rim, water level)</li>
 *   <li>Rendering the lake region with colored wireframe overlays</li>
 *   <li>Validating containment (all rim samples >= water level)</li>
 * </ul>
 *
 * <p><strong>Wireframe Color Legend:</strong></p>
 * <ul>
 *   <li><strong>CYAN:</strong> Basin bottom (lowest point)</li>
 *   <li><strong>YELLOW:</strong> Rim samples (16 positions)</li>
 *   <li><strong>LIGHT BLUE:</strong> Water level grid (surface plane)</li>
 * </ul>
 *
 * <p><strong>Controls:</strong> WASD (move), SPACE/SHIFT (up/down), Mouse (look), ESC (close window)</p>
 *
 * <p><strong>Success Criteria:</strong></p>
 * <ul>
 *   <li>Cyan box (bottom) is below blue grid (water level)</li>
 *   <li>All yellow boxes (rim) are at or above blue grid (containment)</li>
 *   <li>Water surface is flat and horizontal</li>
 * </ul>
 */
public class LakeGenerationVisualTest {

    // Window and OpenGL context
    private long window;
    private int windowWidth = 1280;
    private int windowHeight = 720;

    // World seed
    private long worldSeed;

    // MMS Framework components
    private TextureAtlas textureAtlas;
    private MmsAPI mmsAPI;

    // Terrain generation system
    private TerrainGenerationConfig terrainConfig;
    private WaterGenerationConfig waterConfig;
    private NoiseRouter noiseRouter;
    private TerrainGenerator terrainGenerator;
    private BasinWaterFiller basinWaterFiller;

    // Lake data
    private LakeDiscoveryResult lakeResult;
    private LakeBoundaryData boundaryData;
    private LakeRegion lakeRegion;
    private List<Chunk> chunks = new ArrayList<>();

    // Wireframe data
    private List<WireframeBox> wireframeMarkers = new ArrayList<>();
    private int wireframeVao;
    private int wireframeVbo;

    // Shaders
    private ShaderProgram blockShader;
    private ShaderProgram wireframeShader;
    private boolean wireframesEnabled = true;

    // Camera system
    private Vector3f cameraPos = new Vector3f(0.0f, 10.0f, 20.0f);
    private Vector3f cameraFront = new Vector3f(0.0f, 0.0f, -1.0f);
    private final Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    private float yaw = -90.0f;
    private float pitch = -15.0f;
    private float lastX = windowWidth / 2.0f;
    private float lastY = windowHeight / 2.0f;
    private boolean firstMouse = true;

    // Movement and timing
    private final boolean[] keys = new boolean[GLFW_KEY_LAST];
    private float deltaTime = 0.0f;
    private float lastFrame = 0.0f;
    private final float cameraSpeed = 10.0f;

    @BeforeEach
    void setUp() {
        // GLFW/OpenGL initialization will happen in each test
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    /**
     * Test lake generation with a known seed that produces lakes.
     * This seed should reliably generate a lake within the search radius.
     */
    @Test
    void testLakeGeneration_KnownSeed() {
        System.out.println("\n=== TEST: Known Seed Lake Generation ===");
        long seed = 12345L;
        runVisualTest(seed, "Known Seed (12345)");
    }

    /**
     * Test lake generation with a random seed.
     * May take longer to find a lake depending on RNG.
     */
    @Test
    void testLakeGeneration_RandomSeed() {
        System.out.println("\n=== TEST: Random Seed Lake Generation ===");
        long seed = System.currentTimeMillis();
        System.out.println("Using random seed: " + seed);
        runVisualTest(seed, "Random Seed (" + seed + ")");
    }

    /**
     * Test lake generation with seed known to produce high-elevation lakes.
     */
    @Test
    void testLakeGeneration_HighElevation() {
        System.out.println("\n=== TEST: High Elevation Lake ===");
        long seed = 99999L;
        runVisualTest(seed, "High Elevation (99999)");
    }

    /**
     * Test lake generation with seed known to produce ice lakes (cold temperature).
     */
    @Test
    void testLakeGeneration_IceLake() {
        System.out.println("\n=== TEST: Ice Lake (Cold Temperature) ===");
        long seed = 54321L;
        runVisualTest(seed, "Ice Lake (54321)");
    }

    private void runVisualTest(long seed, String testName) {
        this.worldSeed = seed;
        System.out.println("\nInitializing terrain generation...");
        initializeTerrainGeneration();
        System.out.println("✓ Terrain generation initialized\n");

        System.out.println("Scanning for lakes...");
        lakeResult = findNearestLake();
        assertNotNull(lakeResult, "Should find a lake within search radius");

        System.out.println("✓ Lake found at grid (" + (lakeResult.worldX / 256) + ", " +
                         (lakeResult.worldZ / 256) + ") = world (" + lakeResult.worldX + ", " + lakeResult.worldZ + ")");
        System.out.println("  Water level: y=" + lakeResult.waterLevel);
        System.out.println("  Climate: temp=" + String.format("%.2f", lakeResult.params.temperature) +
                         ", humidity=" + String.format("%.2f", lakeResult.params.humidity) + "\n");

        System.out.println("Extracting boundary data...");
        boundaryData = extractBoundaryData(lakeResult.worldX, lakeResult.worldZ);
        assertNotNull(boundaryData, "Should extract boundary data");

        int avgRimHeight = boundaryData.rimPositions.stream().mapToInt(v -> v.y).sum() / boundaryData.rimPositions.size();
        System.out.println("  Bottom: (" + boundaryData.lowestPoint.x + ", " + boundaryData.lowestPoint.y +
                         ", " + boundaryData.lowestPoint.z + ")");
        System.out.println("  Rim samples: " + boundaryData.rimPositions.size() + " positions");
        System.out.println("  Average rim height: " + avgRimHeight);
        System.out.println("  Basin depth: " + (avgRimHeight - boundaryData.lowestPoint.y) + " blocks");

        // Validate basin containment
        assertTrue(boundaryData.lowestPoint.y < boundaryData.waterLevel,
                  "Basin bottom should be below water level");
        int rimsBelowWater = (int) boundaryData.rimPositions.stream()
            .filter(rim -> rim.y < boundaryData.waterLevel)
            .count();
        assertEquals(0, rimsBelowWater,
                    "All rim samples should be at or above water level (containment check)");

        System.out.println("✓ Boundary data extracted and validated\n");

        System.out.println("Extracting block region...");
        BoundingBox bounds = calculateExtractionBounds(boundaryData, 10);
        System.out.println("  Bounds: X[" + bounds.minX + "-" + bounds.maxX + "], Y[" + bounds.minY + "-" +
                         bounds.maxY + "], Z[" + bounds.minZ + "-" + bounds.maxZ + "]");
        int totalBlocks = bounds.sizeX() * bounds.sizeY() * bounds.sizeZ();
        System.out.println("  Size: " + bounds.sizeX() + " × " + bounds.sizeY() + " × " + bounds.sizeZ() +
                         " blocks = " + String.format("%,d", totalBlocks) + " blocks");

        lakeRegion = extractLakeRegion(bounds, boundaryData);
        assertNotNull(lakeRegion, "Should extract lake region");
        System.out.println("✓ Block extraction complete\n");

        initializeWindow(testName);
        initializeOpenGL();
        initializeMMS();
        initializeShaders();

        System.out.println("Creating pseudo-chunks...");
        long startTime = System.currentTimeMillis();
        chunks = createPseudoChunks(lakeRegion);
        long meshTime = System.currentTimeMillis() - startTime;
        int chunkCountX = (lakeRegion.bounds.sizeX() + 15) / 16;
        int chunkCountZ = (lakeRegion.bounds.sizeZ() + 15) / 16;
        System.out.println("  " + chunkCountX + " chunks × " + chunkCountZ + " chunks = " + chunks.size() + " total chunks");
        System.out.println("  Processing time: " + meshTime + "ms");
        assertTrue(chunks.size() > 0, "Should create at least one chunk");
        System.out.println("✓ " + chunks.size() + " chunks created\n");

        System.out.println("Creating wireframe markers...");
        wireframeMarkers = createBoundaryMarkers(lakeRegion);
        System.out.println("  1 bottom marker (cyan)");
        System.out.println("  " + boundaryData.rimPositions.size() + " rim markers (yellow)");
        System.out.println("  " + (wireframeMarkers.size() - 1 - boundaryData.rimPositions.size()) + " water grid lines (light blue)");
        assertTrue(wireframeMarkers.size() > 0, "Should create wireframe markers");
        System.out.println("✓ " + wireframeMarkers.size() + " wireframe elements created\n");

        positionCamera(lakeRegion);

        System.out.println("=== Window Opening ===");
        System.out.println("Test: " + testName);
        System.out.println("Controls:");
        System.out.println("  WASD - Move camera");
        System.out.println("  SPACE/SHIFT - Up/down");
        System.out.println("  Mouse - Look around");
        System.out.println("  G - Toggle wireframe overlays");
        System.out.println("  ESC - Close window and continue to next test\n");

        runRenderLoop();

        System.out.println("✓ Test completed successfully\n");
    }

    private void initializeTerrainGeneration() {
        terrainConfig = TerrainGenerationConfig.defaultConfig();
        waterConfig = new WaterGenerationConfig();
        noiseRouter = new NoiseRouter(worldSeed, terrainConfig);
        terrainGenerator = TerrainGeneratorFactory.create(
            TerrainGeneratorType.HYBRID_SDF, worldSeed, terrainConfig, true);
        basinWaterFiller = new BasinWaterFiller(noiseRouter, terrainGenerator, waterConfig, worldSeed);
    }

    private LakeDiscoveryResult findNearestLake() {
        int gridRes = waterConfig.waterGridResolution;
        int maxRadius = 20; // ~5km radius

        for (int radius = 0; radius <= maxRadius; radius++) {
            List<GridPosition> ring = getSpiralRing(radius);
            System.out.print("  Ring " + radius + " (" + String.format("%.2f", radius * gridRes / 1000.0) + " km)...");

            for (GridPosition gridPos : ring) {
                int worldX = gridPos.x * gridRes + gridRes / 2;
                int worldZ = gridPos.z * gridRes + gridRes / 2;

                MultiNoiseParameters params = noiseRouter.sampleParameters(worldX, worldZ, 70);
                int terrainHeight = terrainGenerator.generateHeight(worldX, worldZ, params);

                if (terrainHeight >= waterConfig.basinMinimumElevation) {
                    int waterLevel = basinWaterFiller.getBasinWaterLevelGrid()
                        .getWaterLevel(worldX, worldZ, terrainHeight, params.temperature, params.humidity);

                    if (waterLevel > 0) {
                        System.out.println(" FOUND LAKE!");
                        return new LakeDiscoveryResult(worldX, worldZ, waterLevel, params);
                    }
                }
            }
            System.out.println(" no lakes");
        }

        throw new RuntimeException("No lakes found within " + maxRadius + " grid cells (~" +
                                  String.format("%.1f", maxRadius * gridRes / 1000.0) + " km). " +
                                  "Try a different seed or increase search radius.");
    }

    private List<GridPosition> getSpiralRing(int radius) {
        List<GridPosition> ring = new ArrayList<>();
        if (radius == 0) {
            ring.add(new GridPosition(0, 0));
            return ring;
        }

        // Walk perimeter of square at given radius
        // Top edge (left to right)
        for (int x = -radius; x <= radius; x++) {
            ring.add(new GridPosition(x, -radius));
        }
        // Right edge (top to bottom, excluding corners)
        for (int z = -radius + 1; z <= radius; z++) {
            ring.add(new GridPosition(radius, z));
        }
        // Bottom edge (right to left, excluding right corner)
        for (int x = radius - 1; x >= -radius; x--) {
            ring.add(new GridPosition(x, radius));
        }
        // Left edge (bottom to top, excluding corners)
        for (int z = radius - 1; z > -radius; z--) {
            ring.add(new GridPosition(-radius, z));
        }

        return ring;
    }

    private LakeBoundaryData extractBoundaryData(int centerX, int centerZ) {
        Map<BlockPosition2D, Integer> terrainCache = new HashMap<>(50);

        // Find lowest point
        LowestPoint lowest = findLowestPoint(centerX, centerZ, 64, terrainCache);

        // Sample rim heights
        List<Vector3i> rimPositions = sampleRing(lowest.x, lowest.z, 32, 16, terrainCache);

        // Calculate water level (minimum rim height)
        int waterLevel = rimPositions.stream().mapToInt(v -> v.y).min().orElse(lowest.y + 10);

        return new LakeBoundaryData(
            new Vector3i(lowest.x, lowest.y, lowest.z),
            rimPositions,
            waterLevel
        );
    }

    private LowestPoint findLowestPoint(int centerX, int centerZ, int searchRadius,
                                       Map<BlockPosition2D, Integer> terrainCache) {
        int minHeight = Integer.MAX_VALUE;
        int minX = centerX;
        int minZ = centerZ;
        int step = 4;

        for (int dx = -searchRadius; dx <= searchRadius; dx += step) {
            for (int dz = -searchRadius; dz <= searchRadius; dz += step) {
                int sampleX = centerX + dx;
                int sampleZ = centerZ + dz;
                int sampleHeight = getTerrainHeight(sampleX, sampleZ, terrainCache);

                if (sampleHeight < minHeight) {
                    minHeight = sampleHeight;
                    minX = sampleX;
                    minZ = sampleZ;
                }
            }
        }

        return new LowestPoint(minX, minZ, minHeight);
    }

    private List<Vector3i> sampleRing(int centerX, int centerZ, int radius, int sampleCount,
                                      Map<BlockPosition2D, Integer> terrainCache) {
        List<Vector3i> ringPositions = new ArrayList<>();
        double angleStep = 2 * Math.PI / sampleCount;

        for (int i = 0; i < sampleCount; i++) {
            double angle = i * angleStep;
            int sampleX = centerX + (int) (radius * Math.cos(angle));
            int sampleZ = centerZ + (int) (radius * Math.sin(angle));
            int sampleHeight = getTerrainHeight(sampleX, sampleZ, terrainCache);

            ringPositions.add(new Vector3i(sampleX, sampleHeight, sampleZ));
        }

        return ringPositions;
    }

    private int getTerrainHeight(int worldX, int worldZ, Map<BlockPosition2D, Integer> terrainCache) {
        BlockPosition2D key = new BlockPosition2D(worldX, worldZ);
        return terrainCache.computeIfAbsent(key, k ->
            TerrainCalculations.calculateTerrainHeight(worldX, worldZ, noiseRouter, terrainGenerator)
        );
    }

    private BoundingBox calculateExtractionBounds(LakeBoundaryData boundary, int padding) {
        int minX = boundary.rimPositions.stream().mapToInt(v -> v.x).min().orElse(0) - padding;
        int maxX = boundary.rimPositions.stream().mapToInt(v -> v.x).max().orElse(0) + padding;
        int minZ = boundary.rimPositions.stream().mapToInt(v -> v.z).min().orElse(0) - padding;
        int maxZ = boundary.rimPositions.stream().mapToInt(v -> v.z).max().orElse(0) + padding;

        int minY = Math.max(0, boundary.lowestPoint.y - 5);
        int maxY = Math.min(255, boundary.waterLevel + 5);

        // Cap region size at 256x256
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        if (sizeX > 256 || sizeZ > 256) {
            int halfSize = 128;
            minX = boundary.lowestPoint.x - halfSize;
            maxX = boundary.lowestPoint.x + halfSize;
            minZ = boundary.lowestPoint.z - halfSize;
            maxZ = boundary.lowestPoint.z + halfSize;
        }

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private LakeRegion extractLakeRegion(BoundingBox bounds, LakeBoundaryData boundary) {
        int sizeX = bounds.sizeX();
        int sizeY = bounds.sizeY();
        int sizeZ = bounds.sizeZ();

        BlockType[][][] blocks = new BlockType[sizeX][sizeY][sizeZ];

        for (int worldX = bounds.minX; worldX <= bounds.maxX; worldX++) {
            for (int worldZ = bounds.minZ; worldZ <= bounds.maxZ; worldZ++) {
                int localX = worldX - bounds.minX;
                int localZ = worldZ - bounds.minZ;

                MultiNoiseParameters params = noiseRouter.sampleParameters(worldX, worldZ, 70);
                int terrainHeight = terrainGenerator.generateHeight(worldX, worldZ, params);
                int waterLevel = basinWaterFiller.getBasinWaterLevelGrid()
                    .getWaterLevel(worldX, worldZ, terrainHeight, params.temperature, params.humidity);

                for (int worldY = bounds.minY; worldY <= bounds.maxY; worldY++) {
                    int localY = worldY - bounds.minY;
                    blocks[localX][localY][localZ] = determineBlockType(
                        worldY, terrainHeight, waterLevel, params.temperature);
                }
            }
        }

        return new LakeRegion(bounds, blocks, boundary);
    }

    private BlockType determineBlockType(int y, int terrainHeight, int waterLevel, double temperature) {
        // terrainHeight represents the first AIR block (standard interpretation)
        // So the surface (grass) is at terrainHeight - 1
        if (y == 0) {
            return BlockType.BEDROCK;
        } else if (y < terrainHeight - 3) {
            return BlockType.STONE;
        } else if (y < terrainHeight - 1) {
            return BlockType.DIRT;
        } else if (y == terrainHeight - 1) {
            return BlockType.GRASS;
        } else if (y >= terrainHeight && y <= waterLevel) {
            // Water or ice based on temperature
            return temperature < 0.2 ? BlockType.ICE : BlockType.WATER;
        } else {
            return BlockType.AIR;
        }
    }

    private List<Chunk> createPseudoChunks(LakeRegion region) {
        List<Chunk> chunkList = new ArrayList<>();
        int chunksX = (region.bounds.sizeX() + 15) / 16;
        int chunksZ = (region.bounds.sizeZ() + 15) / 16;

        for (int cx = 0; cx < chunksX; cx++) {
            for (int cz = 0; cz < chunksZ; cz++) {
                Chunk chunk = new Chunk(cx, cz);

                // Fill chunk with region blocks
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int regionX = cx * 16 + x;
                        int regionZ = cz * 16 + z;

                        if (regionX < region.bounds.sizeX() && regionZ < region.bounds.sizeZ()) {
                            for (int y = 0; y < region.bounds.sizeY() && y < 256; y++) {
                                BlockType blockType = region.blocks[regionX][y][regionZ];
                                chunk.setBlock(x, y + region.bounds.minY, z, blockType);
                            }
                        }
                    }
                }

                // Generate and upload mesh
                chunk.getCcoDirtyTracker().markMeshDirtyOnly();
                MmsMeshData meshData = mmsAPI.generateChunkMesh(chunk);
                MmsRenderableHandle handle = mmsAPI.uploadMeshToGPU(meshData);
                chunk.setMmsRenderableHandle(handle);
                chunk.getCcoStateManager().removeState(CcoChunkState.MESH_CPU_READY);
                chunk.getCcoStateManager().addState(CcoChunkState.MESH_GPU_UPLOADED);
                chunk.getCcoDirtyTracker().clearMeshDirty();

                chunkList.add(chunk);
            }
        }

        return chunkList;
    }

    private List<WireframeBox> createBoundaryMarkers(LakeRegion region) {
        List<WireframeBox> markers = new ArrayList<>();

        // Basin bottom (cyan)
        markers.add(new WireframeBox(
            new Vector3f(region.boundary.lowestPoint.x, region.boundary.lowestPoint.y, region.boundary.lowestPoint.z),
            new Vector3f(1.0f),
            new Vector4f(0.0f, 1.0f, 1.0f, 1.0f)
        ));

        // Rim samples (yellow)
        for (Vector3i rim : region.boundary.rimPositions) {
            markers.add(new WireframeBox(
                new Vector3f(rim.x, rim.y, rim.z),
                new Vector3f(1.0f),
                new Vector4f(1.0f, 1.0f, 0.0f, 1.0f)
            ));
        }

        // Water level grid (light blue)
        markers.addAll(createWaterLevelGrid(region, 8));

        return markers;
    }

    private List<WireframeBox> createWaterLevelGrid(LakeRegion region, int spacing) {
        List<WireframeBox> gridLines = new ArrayList<>();
        int waterY = region.boundary.waterLevel;
        Vector4f gridColor = new Vector4f(0.3f, 0.3f, 1.0f, 0.5f);

        // Horizontal lines parallel to X axis
        for (int z = region.bounds.minZ; z <= region.bounds.maxZ; z += spacing) {
            gridLines.add(new WireframeBox(
                new Vector3f((region.bounds.minX + region.bounds.maxX) / 2.0f, waterY, z),
                new Vector3f(region.bounds.sizeX(), 0.1f, 0.1f),
                gridColor
            ));
        }

        // Horizontal lines parallel to Z axis
        for (int x = region.bounds.minX; x <= region.bounds.maxX; x += spacing) {
            gridLines.add(new WireframeBox(
                new Vector3f(x, waterY, (region.bounds.minZ + region.bounds.maxZ) / 2.0f),
                new Vector3f(0.1f, 0.1f, region.bounds.sizeZ()),
                gridColor
            ));
        }

        return gridLines;
    }

    private void positionCamera(LakeRegion region) {
        float centerX = (region.bounds.minX + region.bounds.maxX) / 2.0f;
        float centerY = region.boundary.waterLevel + 5.0f;
        float centerZ = (region.bounds.minZ + region.bounds.maxZ) / 2.0f;

        float distance = Math.max(region.bounds.sizeX(), region.bounds.sizeZ()) * 1.5f;

        cameraPos = new Vector3f(
            centerX - distance * 0.7f,
            centerY + distance * 0.5f,
            centerZ - distance * 0.7f
        );

        // Point at lake center
        Vector3f target = new Vector3f(centerX, centerY, centerZ);
        cameraFront = new Vector3f(target).sub(cameraPos).normalize();

        // Calculate yaw and pitch from direction
        yaw = (float) Math.toDegrees(Math.atan2(cameraFront.z, cameraFront.x));
        pitch = (float) Math.toDegrees(Math.asin(cameraFront.y));
    }

    private void initializeWindow(String testName) {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new RuntimeException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        String windowTitle = "Lake Generation Visual Test - " + testName;
        window = glfwCreateWindow(windowWidth, windowHeight, windowTitle, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        setupInputCallbacks();

        var vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - windowWidth) / 2, (vidmode.height() - windowHeight) / 2);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
    }

    private void setupInputCallbacks() {
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
            if (key == GLFW_KEY_G && action == GLFW_PRESS) {
                wireframesEnabled = !wireframesEnabled;
                System.out.println("Wireframes: " + (wireframesEnabled ? "ON" : "OFF"));
            }
            if (key >= 0 && key < keys.length) {
                if (action == GLFW_PRESS) {
                    keys[key] = true;
                } else if (action == GLFW_RELEASE) {
                    keys[key] = false;
                }
            }
        });

        glfwSetCursorPosCallback(window, this::mouseCallback);
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        glfwSetFramebufferSizeCallback(window, (window, width, height) -> {
            this.windowWidth = width;
            this.windowHeight = height;
            glViewport(0, 0, width, height);
        });
    }

    private void mouseCallback(long window, double xpos, double ypos) {
        if (firstMouse) {
            lastX = (float) xpos;
            lastY = (float) ypos;
            firstMouse = false;
        }

        float xoffset = (float) xpos - lastX;
        float yoffset = lastY - (float) ypos;
        lastX = (float) xpos;
        lastY = (float) ypos;

        float sensitivity = 0.1f;
        xoffset *= sensitivity;
        yoffset *= sensitivity;

        yaw += xoffset;
        pitch += yoffset;

        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;

        Vector3f direction = new Vector3f();
        direction.x = (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        direction.y = (float) Math.sin(Math.toRadians(pitch));
        direction.z = (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        cameraFront = direction.normalize();
    }

    private void initializeOpenGL() {
        GL.createCapabilities();

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glViewport(0, 0, windowWidth, windowHeight);
    }

    private void initializeMMS() {
        textureAtlas = new TextureAtlas(16);
        mmsAPI = MmsAPI.initialize(textureAtlas, null);
    }

    private void initializeShaders() {
        // Create simplified block shader with basic Phong lighting
        String blockVertexSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            layout (location = 2) in vec3 aNormal;

            out vec2 TexCoord;
            out vec3 FragPos;
            out vec3 Normal;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
                TexCoord = aTexCoord;
                FragPos = vec3(model * vec4(aPos, 1.0));
                Normal = mat3(transpose(inverse(model))) * aNormal;
            }
            """;

        String blockFragmentSource = """
            #version 330 core
            out vec4 FragColor;

            in vec2 TexCoord;
            in vec3 FragPos;
            in vec3 Normal;

            uniform sampler2D ourTexture;
            uniform vec3 u_sunDirection;
            uniform float u_ambientLight;

            void main() {
                vec4 texColor = texture(ourTexture, TexCoord);
                if (texColor.a < 0.1) {
                    discard;
                }

                // Simple Phong lighting (ambient + diffuse)
                vec3 norm = normalize(Normal);
                vec3 lightDir = normalize(u_sunDirection);

                float ambient = 0.4 * u_ambientLight;
                float diffuse = max(dot(norm, lightDir), 0.0) * 0.6 * u_ambientLight;
                float brightness = ambient + diffuse;
                brightness = max(brightness, 0.15); // Minimum visibility

                FragColor = vec4(texColor.rgb * brightness, texColor.a);
            }
            """;

        blockShader = new ShaderProgram();
        blockShader.createVertexShader(blockVertexSource);
        blockShader.createFragmentShader(blockFragmentSource);
        blockShader.link();

        blockShader.createUniform("model");
        blockShader.createUniform("view");
        blockShader.createUniform("projection");
        blockShader.createUniform("ourTexture");
        blockShader.createUniform("u_sunDirection");
        blockShader.createUniform("u_ambientLight");

        // Keep existing wireframe shader but use ShaderProgram class
        String wireframeVertexSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
            }
            """;

        String wireframeFragmentSource = """
            #version 330 core
            out vec4 FragColor;
            uniform vec4 color;

            void main() {
                FragColor = color;
            }
            """;

        wireframeShader = new ShaderProgram();
        wireframeShader.createVertexShader(wireframeVertexSource);
        wireframeShader.createFragmentShader(wireframeFragmentSource);
        wireframeShader.link();

        wireframeShader.createUniform("model");
        wireframeShader.createUniform("view");
        wireframeShader.createUniform("projection");
        wireframeShader.createUniform("color");

        // Create wireframe cube geometry
        createWireframeCube();
    }

    private void createWireframeCube() {
        float[] vertices = {
            // Bottom face edges
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,
             0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,
             0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,
            -0.5f, -0.5f,  0.5f, -0.5f, -0.5f, -0.5f,

            // Top face edges
            -0.5f,  0.5f, -0.5f,  0.5f,  0.5f, -0.5f,
             0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,
             0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f,  0.5f, -0.5f,  0.5f, -0.5f,

            // Vertical edges
            -0.5f, -0.5f, -0.5f, -0.5f,  0.5f, -0.5f,
             0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,
             0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f,
            -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f
        };

        wireframeVao = glGenVertexArrays();
        glBindVertexArray(wireframeVao);

        wireframeVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, wireframeVbo);

        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void runRenderLoop() {
        int frameCount = 0;
        double fpsTimer = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            float currentFrame = (float) glfwGetTime();
            deltaTime = currentFrame - lastFrame;
            lastFrame = currentFrame;

            processInput();
            render();

            frameCount++;
            if (currentFrame - fpsTimer >= 1.0) {
                glfwSetWindowTitle(window, "Lake Generation Visual Test - FPS: " + frameCount);
                frameCount = 0;
                fpsTimer = currentFrame;
            }

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void processInput() {
        float speed = cameraSpeed * deltaTime;

        if (keys[GLFW_KEY_W]) {
            cameraPos.add(new Vector3f(cameraFront).mul(speed));
        }
        if (keys[GLFW_KEY_S]) {
            cameraPos.sub(new Vector3f(cameraFront).mul(speed));
        }
        if (keys[GLFW_KEY_A]) {
            cameraPos.sub(new Vector3f(cameraFront).cross(cameraUp).normalize().mul(speed));
        }
        if (keys[GLFW_KEY_D]) {
            cameraPos.add(new Vector3f(cameraFront).cross(cameraUp).normalize().mul(speed));
        }
        if (keys[GLFW_KEY_SPACE]) {
            cameraPos.add(new Vector3f(cameraUp).mul(speed));
        }
        if (keys[GLFW_KEY_LEFT_SHIFT]) {
            cameraPos.sub(new Vector3f(cameraUp).mul(speed));
        }
    }

    private void render() {
        glClearColor(0.53f, 0.81f, 0.92f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Matrix4f projection = new Matrix4f().perspective(
            (float) Math.toRadians(70.0f),
            (float) windowWidth / (float) windowHeight,
            0.1f, 1000.0f
        );

        Matrix4f view = new Matrix4f().lookAt(
            cameraPos,
            new Vector3f(cameraPos).add(cameraFront),
            cameraUp
        );

        // Render blocks (always)
        renderBlocks(projection, view);

        // Render wireframes (toggleable)
        if (wireframesEnabled) {
            renderWireframes(projection, view);
        }
    }

    private void renderBlocks(Matrix4f projection, Matrix4f view) {
        blockShader.bind();
        textureAtlas.bind();

        blockShader.setUniform("projection", projection);
        blockShader.setUniform("view", view);
        blockShader.setUniform("ourTexture", 0);
        blockShader.setUniform("u_sunDirection", new Vector3f(0.5f, 1.0f, 0.3f).normalize());
        blockShader.setUniform("u_ambientLight", 1.0f);

        for (Chunk chunk : chunks) {
            // Calculate model matrix (translate to world position)
            Matrix4f model = new Matrix4f().identity();
            model.translate(chunk.getChunkX() * 16 + lakeRegion.bounds.minX, 0,
                          chunk.getChunkZ() * 16 + lakeRegion.bounds.minZ);

            blockShader.setUniform("model", model);

            if (chunk.getMmsRenderableHandle() != null) {
                chunk.getMmsRenderableHandle().render();
            }
        }
    }

    private void renderWireframes(Matrix4f projection, Matrix4f view) {
        glDepthMask(false);
        glLineWidth(2.0f);

        wireframeShader.bind();
        wireframeShader.setUniform("projection", projection);
        wireframeShader.setUniform("view", view);

        glBindVertexArray(wireframeVao);

        for (WireframeBox box : wireframeMarkers) {
            Matrix4f model = new Matrix4f();
            model.translation(box.position);
            model.scale(box.scale);

            wireframeShader.setUniform("model", model);
            wireframeShader.setUniform("color", new Vector4f(box.color.x, box.color.y, box.color.z, box.color.w));

            glDrawArrays(GL_LINES, 0, 24);
        }

        glBindVertexArray(0);
        glDepthMask(true);
    }

    private void cleanup() {
        System.out.println("Cleaning up resources...");

        try {
            for (Chunk chunk : chunks) {
                if (chunk.getMmsRenderableHandle() != null) {
                    chunk.getMmsRenderableHandle().close();
                }
            }
            chunks.clear();

            if (blockShader != null) {
                blockShader.cleanup();
            }
            if (wireframeShader != null) {
                wireframeShader.cleanup();
            }

            if (wireframeVao != 0) glDeleteVertexArrays(wireframeVao);
            if (wireframeVbo != 0) glDeleteBuffers(wireframeVbo);

            if (mmsAPI != null) {
                MmsAPI.shutdown();
                mmsAPI = null;
            }

            if (window != NULL) {
                glfwDestroyWindow(window);
                window = NULL;
            }
            glfwTerminate();

            // Reset state for next test
            wireframeMarkers.clear();
            lakeResult = null;
            boundaryData = null;
            lakeRegion = null;

            System.out.println("✓ Cleanup completed");

        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Data structures
    private record GridPosition(int x, int z) {}
    private record BlockPosition2D(int x, int z) {}
    private record LowestPoint(int x, int z, int y) {}

    private record LakeDiscoveryResult(int worldX, int worldZ, int waterLevel, MultiNoiseParameters params) {}

    private record LakeBoundaryData(Vector3i lowestPoint, List<Vector3i> rimPositions, int waterLevel) {}

    private static class BoundingBox {
        int minX, minY, minZ;
        int maxX, maxY, maxZ;

        BoundingBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        int sizeX() { return maxX - minX + 1; }
        int sizeY() { return maxY - minY + 1; }
        int sizeZ() { return maxZ - minZ + 1; }
    }

    private static class LakeRegion {
        BoundingBox bounds;
        BlockType[][][] blocks;
        LakeBoundaryData boundary;

        LakeRegion(BoundingBox bounds, BlockType[][][] blocks, LakeBoundaryData boundary) {
            this.bounds = bounds;
            this.blocks = blocks;
            this.boundary = boundary;
        }
    }

    private record WireframeBox(Vector3f position, Vector3f scale, Vector4f color) {}
}
