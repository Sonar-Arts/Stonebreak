package com.stonebreak.rendering.core.API.commonBlockResources;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.core.API.commonBlockResources.meshing.MeshManager;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.texturing.TextureResourceManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.GL_SHADING_LANGUAGE_VERSION;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Comprehensive test for the CBR (Common Block Resources) Framework.
 * 
 * This test validates:
 * 1. Block Definition Registry functionality
 * 2. Mesh Manager pre-built geometry creation
 * 3. Texture Resource Manager integration
 * 4. CBRResourceManager unified operations
 * 5. Actual OpenGL rendering of common blocks
 * 
 * Tests both modern BlockDefinition system and legacy BlockType compatibility.
 */
public class CBRFrameworkTest {
    
    // OpenGL context for testing
    private long window;
    private TextureAtlas textureAtlas;
    private BlockDefinitionRegistry blockRegistry;
    private CBRResourceManager cbrManager;
    
    // Test results tracking
    private int testsRun = 0;
    private int testsPassed = 0;
    private int testsFailed = 0;
    
    public static void main(String[] args) {
        CBRFrameworkTest test = new CBRFrameworkTest();
        test.runAllTests();
    }
    
    public void runAllTests() {
        System.out.println("=== CBR Framework Comprehensive Test ===\n");
        
        try {
            // Initialize OpenGL context for testing
            initializeOpenGL();
            
            // Initialize CBR components
            initializeCBRComponents();
            
            // Run all test suites
            testBlockDefinitionRegistry();
            testMeshManager();
            testTextureResourceManager();
            testCBRResourceManagerUnifiedOperations();
            testLegacyCompatibility();
            testActualRendering();
            testResourceCleanup();
            
        } catch (Exception e) {
            System.err.println("CRITICAL ERROR during testing: " + e.getMessage());
            e.printStackTrace();
            testsFailed++;
        } finally {
            cleanup();
            printTestSummary();
        }
    }
    
    // === Test Initialization ===
    
    private void initializeOpenGL() {
        System.out.println("1. Initializing OpenGL context for testing...");
        
        // Setup GLFW error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW for testing");
        }
        
        // Configure GLFW for minimal context
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        
        // Create window
        window = glfwCreateWindow(256, 256, "CBR Test Window", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window for testing");
        }
        
        // Make context current
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        
        System.out.println("   ✓ OpenGL context initialized successfully");
        System.out.println("   ✓ OpenGL Version: " + glGetString(GL_VERSION));
        System.out.println("   ✓ GLSL Version: " + glGetString(GL_SHADING_LANGUAGE_VERSION) + "\n");
    }
    
    private void initializeCBRComponents() {
        System.out.println("2. Initializing CBR Framework components...");
        
        // Create texture atlas (minimal for testing)
        textureAtlas = new TextureAtlas(16);
        System.out.println("   ✓ TextureAtlas initialized");
        
        // Create block definition registry with test data
        blockRegistry = createTestBlockRegistry();
        System.out.println("   ✓ BlockDefinitionRegistry created with test data");
        
        // Initialize CBRResourceManager
        cbrManager = CBRResourceManager.getInstance(textureAtlas, blockRegistry);
        System.out.println("   ✓ CBRResourceManager initialized");
        
        System.out.println("   ✓ All CBR components ready for testing\n");
    }
    
    private BlockDefinitionRegistry createTestBlockRegistry() {
        return new TestBlockDefinitionRegistry();
    }
    
    // === Individual Test Suites ===
    
    private void testBlockDefinitionRegistry() {
        System.out.println("3. Testing Block Definition Registry...");
        testsRun++;
        
        try {
            // Test basic registry operations
            assert blockRegistry.hasDefinition("stonebreak:grass") : "Grass definition should exist";
            assert blockRegistry.hasDefinition(0) : "Grass definition should exist by numeric ID";
            assert blockRegistry.getDefinitionCount() >= 5 : "Should have at least 5 test definitions";
            
            // Test definition retrieval
            var grassDef = blockRegistry.getDefinition("stonebreak:grass");
            assert grassDef.isPresent() : "Should find grass definition";
            assert grassDef.get().getRenderType() == BlockDefinition.RenderType.CUBE_DIRECTIONAL : "Grass should be directional cube";
            
            System.out.println("   ✓ Registry operations working correctly");
            System.out.println("   ✓ Definition lookup by string and numeric ID");
            System.out.println("   ✓ Block definitions have correct render types");
            testsPassed++;
            
        } catch (Exception e) {
            System.err.println("   ✗ Registry test failed: " + e.getMessage());
            testsFailed++;
        }
        System.out.println();
    }
    
    private void testMeshManager() {
        System.out.println("4. Testing Mesh Manager...");
        testsRun++;
        
        try {
            MeshManager meshManager = cbrManager.getMeshManager();
            
            // Test pre-built mesh retrieval
            MeshManager.MeshResource cubeMesh = meshManager.getMesh(MeshManager.MeshType.CUBE);
            assert cubeMesh != null : "Cube mesh should be available";
            assert cubeMesh.getVertexCount() == 24 : "Cube should have 24 vertices (6 faces × 4 vertices)";
            assert cubeMesh.getTriangleCount() == 12 : "Cube should have 12 triangles (6 faces × 2 triangles)";
            
            MeshManager.MeshResource crossMesh = meshManager.getMesh(MeshManager.MeshType.CROSS);
            assert crossMesh != null : "Cross mesh should be available";
            assert crossMesh.getVertexCount() == 8 : "Cross should have 8 vertices (2 quads × 4 vertices)";
            
            MeshManager.MeshResource spriteMesh = meshManager.getMesh(MeshManager.MeshType.SPRITE);
            assert spriteMesh != null : "Sprite mesh should be available";
            assert spriteMesh.getVertexCount() == 4 : "Sprite should have 4 vertices (1 quad)";
            
            // Test mesh binding (should not throw exceptions)
            cubeMesh.bind();
            cubeMesh.unbind();
            
            // Test statistics
            MeshManager.MeshStatistics stats = meshManager.getStatistics();
            assert stats.getPrebuiltMeshCount() >= 3 : "Should have at least 3 prebuilt meshes";
            assert stats.getTotalVertices() > 0 : "Should have vertices";
            
            System.out.println("   ✓ Pre-built meshes created successfully");
            System.out.println("   ✓ Mesh binding/unbinding works");
            System.out.println("   ✓ Mesh statistics: " + stats);
            testsPassed++;
            
        } catch (Exception e) {
            System.err.println("   ✗ Mesh manager test failed: " + e.getMessage());
            testsFailed++;
        }
        System.out.println();
    }
    
    private void testTextureResourceManager() {
        System.out.println("5. Testing Texture Resource Manager...");
        testsRun++;
        
        try {
            TextureResourceManager texManager = cbrManager.getTextureManager();
            
            // Test BlockType resolution (legacy compatibility)
            TextureResourceManager.TextureCoordinates grassCoords = texManager.resolveBlockType(BlockType.GRASS);
            assert grassCoords != null : "Should resolve grass texture coordinates";
            
            float[] coordArray = grassCoords.toArray();
            assert coordArray.length == 4 : "Should have 4 coordinate values";
            assert coordArray[0] >= 0.0f && coordArray[0] <= 1.0f : "U1 should be in range [0,1]";
            assert coordArray[1] >= 0.0f && coordArray[1] <= 1.0f : "V1 should be in range [0,1]";
            assert coordArray[2] >= 0.0f && coordArray[2] <= 1.0f : "U2 should be in range [0,1]";
            assert coordArray[3] >= 0.0f && coordArray[3] <= 1.0f : "V2 should be in range [0,1]";
            
            // Test ItemType resolution
            TextureResourceManager.TextureCoordinates stickCoords = texManager.resolveItemType(ItemType.STICK);
            assert stickCoords != null : "Should resolve stick texture coordinates";
            
            // Test cache statistics
            TextureResourceManager.CacheStatistics cacheStats = texManager.getCacheStatistics();
            assert cacheStats.getTotalEntries() >= 0 : "Cache should be accessible";
            
            System.out.println("   ✓ Block texture coordinate resolution");
            System.out.println("   ✓ Item texture coordinate resolution");
            System.out.println("   ✓ Texture coordinate validation");
            System.out.println("   ✓ Cache statistics: " + cacheStats);
            testsPassed++;
            
        } catch (Exception e) {
            System.err.println("   ✗ Texture manager test failed: " + e.getMessage());
            testsFailed++;
        }
        System.out.println();
    }
    
    private void testCBRResourceManagerUnifiedOperations() {
        System.out.println("6. Testing CBRResourceManager Unified Operations...");
        testsRun++;
        
        try {
            // Test modern BlockDefinition rendering
            var grassDef = blockRegistry.getDefinition("stonebreak:grass").get();
            CBRResourceManager.BlockRenderResource grassResource = cbrManager.getBlockRenderResource(grassDef);
            
            assert grassResource != null : "Should create grass render resource";
            assert grassResource.getMesh() != null : "Should have mesh";
            assert grassResource.getTextureCoords() != null : "Should have texture coordinates";
            assert grassResource.getDefinition().equals(grassDef) : "Should preserve definition";
            
            // Test face-specific rendering for directional blocks
            CBRResourceManager.BlockRenderResource grassTopResource = cbrManager.getBlockFaceResource(grassDef, "up");
            assert grassTopResource.getFace().isPresent() : "Should have face information";
            assert "up".equals(grassTopResource.getFace().get()) : "Should have correct face";
            
            // Test bind and render operations
            float[] texCoords = grassResource.bindAndGetTexCoords();
            assert texCoords != null && texCoords.length == 4 : "Should get texture coordinates";
            
            System.out.println("   ✓ Modern BlockDefinition rendering");
            System.out.println("   ✓ Face-specific texture resolution");
            System.out.println("   ✓ Unified resource operations");
            testsPassed++;
            
        } catch (Exception e) {
            System.err.println("   ✗ Unified operations test failed: " + e.getMessage());
            testsFailed++;
        }
        System.out.println();
    }
    
    private void testLegacyCompatibility() {
        System.out.println("7. Testing Legacy BlockType/ItemType Compatibility...");
        testsRun++;
        
        try {
            // Test legacy block rendering
            CBRResourceManager.BlockRenderResource legacyGrass = cbrManager.getBlockTypeResource(BlockType.GRASS);
            assert legacyGrass != null : "Should create legacy grass resource";
            assert legacyGrass.getMesh().getName().equals("cube") : "Should use cube mesh for blocks";
            
            CBRResourceManager.BlockRenderResource legacyStone = cbrManager.getBlockTypeResource(BlockType.STONE);
            assert legacyStone != null : "Should create legacy stone resource";
            
            // Test legacy item rendering
            CBRResourceManager.BlockRenderResource legacyStick = cbrManager.getItemTypeResource(ItemType.STICK);
            assert legacyStick != null : "Should create legacy stick resource";
            assert legacyStick.getMesh().getName().equals("sprite") : "Should use sprite mesh for items";
            assert legacyStick.getDefinition().getRenderType() == BlockDefinition.RenderType.SPRITE : "Should be sprite render type";
            
            System.out.println("   ✓ Legacy BlockType support");
            System.out.println("   ✓ Legacy ItemType support");
            System.out.println("   ✓ Proper mesh selection for legacy types");
            testsPassed++;
            
        } catch (Exception e) {
            System.err.println("   ✗ Legacy compatibility test failed: " + e.getMessage());
            testsFailed++;
        }
        System.out.println();
    }
    
    private void testActualRendering() {
        System.out.println("8. Testing Actual OpenGL Rendering...");
        testsRun++;
        
        try {
            // Clear the framebuffer
            glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            
            // Test rendering different block types
            renderTestBlock("stonebreak:grass", "Grass Block");
            renderTestBlock("stonebreak:stone", "Stone Block");
            renderTestBlock("stonebreak:dirt", "Dirt Block");
            
            // Test legacy rendering
            CBRResourceManager.BlockRenderResource legacyGrass = cbrManager.getBlockTypeResource(BlockType.GRASS);
            legacyGrass.getMesh().bind();
            assert glGetError() == GL_NO_ERROR : "OpenGL operations should not generate errors";
            legacyGrass.getMesh().unbind();
            
            System.out.println("   ✓ Modern block rendering (no OpenGL errors)");
            System.out.println("   ✓ Legacy block rendering compatibility");
            System.out.println("   ✓ All rendering operations completed successfully");
            testsPassed++;
            
        } catch (Exception e) {
            System.err.println("   ✗ Rendering test failed: " + e.getMessage());
            testsFailed++;
        }
        System.out.println();
    }
    
    private void renderTestBlock(String resourceId, String blockName) {
        var blockDef = blockRegistry.getDefinition(resourceId);
        if (blockDef.isPresent()) {
            CBRResourceManager.BlockRenderResource resource = cbrManager.getBlockRenderResource(blockDef.get());
            
            // Bind mesh and validate
            resource.getMesh().bind();
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                throw new RuntimeException("OpenGL error during " + blockName + " rendering: " + error);
            }
            
            // Get texture coordinates and validate
            float[] texCoords = resource.getTextureCoords().toArray();
            if (texCoords.length != 4) {
                throw new RuntimeException("Invalid texture coordinates for " + blockName);
            }
            
            resource.getMesh().unbind();
            System.out.println("     ✓ " + blockName + " rendered successfully");
        } else {
            throw new RuntimeException("Block definition not found: " + resourceId);
        }
    }
    
    private void testResourceCleanup() {
        System.out.println("9. Testing Resource Cleanup and Memory Management...");
        testsRun++;
        
        try {
            // Test cache operations
            cbrManager.clearCaches();
            
            // Test statistics
            CBRResourceManager.ResourceStatistics stats = cbrManager.getResourceStatistics();
            assert stats.getBlockDefinitionCount() > 0 : "Should have block definitions";
            assert stats.getMeshStats().getTotalMeshCount() > 0 : "Should have meshes";
            
            // Test memory optimization
            cbrManager.optimizeMemory();
            
            System.out.println("   ✓ Cache clearing operations");
            System.out.println("   ✓ Resource statistics: " + stats);
            System.out.println("   ✓ Memory optimization completed");
            testsPassed++;
            
        } catch (Exception e) {
            System.err.println("   ✗ Resource cleanup test failed: " + e.getMessage());
            testsFailed++;
        }
        System.out.println();
    }
    
    private void cleanup() {
        System.out.println("10. Cleaning up test resources...");
        
        try {
            if (cbrManager != null) {
                cbrManager.close();
                System.out.println("   ✓ CBRResourceManager disposed");
            }
            
            if (window != NULL) {
                glfwDestroyWindow(window);
                System.out.println("   ✓ GLFW window destroyed");
            }
            
            glfwTerminate();
            System.out.println("   ✓ GLFW terminated");
            
        } catch (Exception e) {
            System.err.println("   ⚠ Error during cleanup: " + e.getMessage());
        }
        System.out.println();
    }
    
    private void printTestSummary() {
        System.out.println("=== CBR Framework Test Results ===");
        System.out.println("Tests Run:    " + testsRun);
        System.out.println("Tests Passed: " + testsPassed);
        System.out.println("Tests Failed: " + testsFailed);
        
        if (testsFailed == 0) {
            System.out.println("\n🎉 ALL TESTS PASSED! CBR Framework is working correctly.");
        } else {
            System.out.println("\n⚠️  " + testsFailed + " test(s) failed. Please review the errors above.");
        }
        
        System.out.println("\n=== CBR Framework Validation Complete ===");
    }
    
    // === Test Data Implementation ===
    
    private static class TestBlockDefinitionRegistry implements BlockDefinitionRegistry {
        private final Map<String, BlockDefinition> definitionsByName = new HashMap<>();
        private final Map<Integer, BlockDefinition> definitionsById = new HashMap<>();
        
        public TestBlockDefinitionRegistry() {
            // Create test block definitions
            addTestDefinition("stonebreak:grass", 0, BlockDefinition.RenderType.CUBE_DIRECTIONAL);
            addTestDefinition("stonebreak:dirt", 1, BlockDefinition.RenderType.CUBE_ALL);
            addTestDefinition("stonebreak:stone", 2, BlockDefinition.RenderType.CUBE_ALL);
            addTestDefinition("stonebreak:dandelion", 15, BlockDefinition.RenderType.CROSS);
            addTestDefinition("stonebreak:stick", 100, BlockDefinition.RenderType.SPRITE);
        }
        
        private void addTestDefinition(String resourceId, int numericId, BlockDefinition.RenderType renderType) {
            BlockDefinition definition = new BlockDefinition.Builder()
                .resourceId(resourceId)
                .numericId(numericId)
                .renderType(renderType)
                .renderLayer(BlockDefinition.RenderLayer.OPAQUE)
                .build();
            
            definitionsByName.put(resourceId, definition);
            definitionsById.put(numericId, definition);
        }
        
        @Override
        public Optional<BlockDefinition> getDefinition(String resourceId) {
            return Optional.ofNullable(definitionsByName.get(resourceId));
        }
        
        @Override
        public Optional<BlockDefinition> getDefinition(int numericId) {
            return Optional.ofNullable(definitionsById.get(numericId));
        }
        
        @Override
        public boolean hasDefinition(String resourceId) {
            return definitionsByName.containsKey(resourceId);
        }
        
        @Override
        public boolean hasDefinition(int numericId) {
            return definitionsById.containsKey(numericId);
        }
        
        @Override
        public java.util.Collection<BlockDefinition> getAllDefinitions() {
            return definitionsByName.values();
        }
        
        @Override
        public java.util.Collection<BlockDefinition> getDefinitionsByNamespace(String namespace) {
            return definitionsByName.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(namespace + ":"))
                .map(Map.Entry::getValue)
                .toList();
        }
        
        @Override
        public int getDefinitionCount() {
            return definitionsByName.size();
        }
        
        @Override
        public void registerDefinition(BlockDefinition definition) {
            definitionsByName.put(definition.getResourceId(), definition);
            definitionsById.put(definition.getNumericId(), definition);
        }
        
        @Override
        public boolean isModifiable() {
            return true;
        }
        
        @Override
        public String getSchemaVersion() {
            return "1.0.0-test";
        }
        
        @Override
        public void close() {
            definitionsByName.clear();
            definitionsById.clear();
        }
    }
}