package com.stonebreak.rendering.core.API.commonBlockResources;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Visual test for the CBR (Common Block Resources) Framework.
 * 
 * Opens a window and renders various blocks using the CBR system in real-time.
 * Use WASD to move camera, mouse to look around, ESC to exit.
 * 
 * Features:
 * - Real-time 3D rendering of CBR blocks
 * - Camera controls for inspection
 * - Multiple block types displayed
 * - Legacy and modern block rendering
 * - Performance statistics display
 */
public class CBRVisualTest {
    
    // Window and OpenGL context
    private long window;
    private int windowWidth = 1024;
    private int windowHeight = 768;
    
    // CBR Framework components
    private TextureAtlas textureAtlas;
    private BlockDefinitionRegistry blockRegistry;
    private CBRResourceManager cbrManager;
    
    // Shader program for basic rendering
    private int shaderProgram;
    private int vertexShader;
    private int fragmentShader;
    
    // Camera system
    private Vector3f cameraPos = new Vector3f(0.0f, 2.0f, 6.0f);
    private Vector3f cameraFront = new Vector3f(0.0f, 0.0f, -1.0f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    private float yaw = -90.0f;
    private float pitch = 0.0f;
    private float lastX = windowWidth / 2.0f;
    private float lastY = windowHeight / 2.0f;
    private boolean firstMouse = true;
    
    // Movement and timing
    private boolean[] keys = new boolean[GLFW_KEY_LAST];
    private float deltaTime = 0.0f;
    private float lastFrame = 0.0f;
    
    // Test blocks to render
    private List<TestBlock> testBlocks = new ArrayList<>();
    
    public static void main(String[] args) {
        com.stonebreak.rendering.core.API.commonBlockResources.tests.CBRVisualTest visualTest = new com.stonebreak.rendering.core.API.commonBlockResources.tests.CBRVisualTest();
        visualTest.run();
    }
    
    public void run() {
        System.out.println("=== CBR Framework Visual Test ===");
        System.out.println("Controls:");
        System.out.println("  WASD - Move camera");
        System.out.println("  Mouse - Look around");
        System.out.println("  ESC - Exit");
        System.out.println("======================================\n");
        
        try {
            initializeWindow();
            initializeOpenGL();
            initializeCBRFramework();
            initializeShaders();
            setupTestBlocks();
            
            runRenderLoop();
            
        } catch (Exception e) {
            System.err.println("Error during visual test: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }
    
    private void initializeWindow() {
        // Setup GLFW error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new RuntimeException("Unable to initialize GLFW");
        }
        
        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        
        // Create the window
        window = glfwCreateWindow(windowWidth, windowHeight, "CBR Framework Visual Test", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }
        
        // Setup input callbacks
        setupInputCallbacks();
        
        // Center window
        var vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - windowWidth) / 2, (vidmode.height() - windowHeight) / 2);
        
        // Make context current
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // Enable v-sync
        
        System.out.println("✓ GLFW window initialized: " + windowWidth + "x" + windowHeight);
    }
    
    private void setupInputCallbacks() {
        // Key callback
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
            if (key >= 0 && key < keys.length) {
                if (action == GLFW_PRESS) {
                    keys[key] = true;
                } else if (action == GLFW_RELEASE) {
                    keys[key] = false;
                }
            }
        });
        
        // Mouse callback
        glfwSetCursorPosCallback(window, this::mouseCallback);
        
        // Capture mouse cursor
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        
        // Window resize callback
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
        float yoffset = lastY - (float) ypos; // Reversed since y-coordinates go from bottom to top
        lastX = (float) xpos;
        lastY = (float) ypos;
        
        float sensitivity = 0.1f;
        xoffset *= sensitivity;
        yoffset *= sensitivity;
        
        yaw += xoffset;
        pitch += yoffset;
        
        // Constrain pitch
        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;
        
        // Update camera direction
        Vector3f direction = new Vector3f();
        direction.x = (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        direction.y = (float) Math.sin(Math.toRadians(pitch));
        direction.z = (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        cameraFront = direction.normalize();
    }
    
    private void initializeOpenGL() {
        GL.createCapabilities();
        
        // Enable depth testing
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        
        // Enable face culling
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);
        
        // Set viewport
        glViewport(0, 0, windowWidth, windowHeight);
        
        System.out.println("✓ OpenGL initialized");
        System.out.println("  OpenGL Version: " + glGetString(GL_VERSION));
        System.out.println("  GLSL Version: " + glGetString(GL_SHADING_LANGUAGE_VERSION));
    }
    
    private void initializeCBRFramework() {
        // Initialize texture atlas
        textureAtlas = new TextureAtlas(16);
        
        // Create block registry with test data
        blockRegistry = new TestBlockDefinitionRegistry();
        
        // Initialize CBR resource manager
        cbrManager = CBRResourceManager.getInstance(textureAtlas, blockRegistry);
        
        System.out.println("✓ CBR Framework initialized");
        
        // Print statistics
        CBRResourceManager.ResourceStatistics stats = cbrManager.getResourceStatistics();
        System.out.println("  " + stats);
    }
    
    private void initializeShaders() {
        // Vertex shader source
        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            
            out vec2 TexCoord;
            
            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;
            
            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
                TexCoord = aTexCoord;
            }
            """;
        
        // Fragment shader source
        String fragmentShaderSource = """
            #version 330 core
            out vec4 FragColor;
            
            in vec2 TexCoord;
            
            uniform sampler2D ourTexture;
            uniform vec3 blockColor;
            
            void main() {
                vec4 texColor = texture(ourTexture, TexCoord);
                FragColor = texColor * vec4(blockColor, 1.0);
            }
            """;
        
        // Create and compile vertex shader
        vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexShaderSource);
        glCompileShader(vertexShader);
        
        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Vertex shader compilation failed: " + glGetShaderInfoLog(vertexShader));
        }
        
        // Create and compile fragment shader
        fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentShaderSource);
        glCompileShader(fragmentShader);
        
        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Fragment shader compilation failed: " + glGetShaderInfoLog(fragmentShader));
        }
        
        // Create shader program
        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vertexShader);
        glAttachShader(shaderProgram, fragmentShader);
        glLinkProgram(shaderProgram);
        
        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader program linking failed: " + glGetProgramInfoLog(shaderProgram));
        }
        
        System.out.println("✓ Shaders compiled and linked successfully");
    }
    
    private void setupTestBlocks() {
        // Add various test blocks in a grid pattern
        int gridSize = 3;
        float spacing = 2.0f;
        
        // Modern CBR blocks
        addTestBlock("stonebreak:grass", -spacing, 0, -spacing, new Vector3f(0.4f, 0.8f, 0.2f));
        addTestBlock("stonebreak:dirt", 0, 0, -spacing, new Vector3f(0.6f, 0.4f, 0.2f));
        addTestBlock("stonebreak:stone", spacing, 0, -spacing, new Vector3f(0.5f, 0.5f, 0.5f));
        
        // Legacy BlockType compatibility
        addLegacyBlock(BlockType.SAND, -spacing, 0, 0, new Vector3f(0.9f, 0.8f, 0.4f));
        addLegacyBlock(BlockType.COAL_ORE, 0, 0, 0, new Vector3f(0.2f, 0.2f, 0.2f));
        addLegacyBlock(BlockType.IRON_ORE, spacing, 0, 0, new Vector3f(0.7f, 0.6f, 0.5f));
        
        // Cross-shaped blocks (flowers)
        addTestBlock("stonebreak:dandelion", -spacing, 0, spacing, new Vector3f(1.0f, 1.0f, 0.0f));
        
        // Item rendering test
        addLegacyItem(ItemType.STICK, 0, 0, spacing, new Vector3f(0.6f, 0.3f, 0.1f));
        addLegacyItem(ItemType.WOODEN_PICKAXE, spacing, 0, spacing, new Vector3f(0.4f, 0.2f, 0.1f));
        
        System.out.println("✓ " + testBlocks.size() + " test blocks/items setup for rendering");
    }
    
    private void addTestBlock(String resourceId, float x, float y, float z, Vector3f color) {
        var blockDef = blockRegistry.getDefinition(resourceId);
        if (blockDef.isPresent()) {
            CBRResourceManager.BlockRenderResource resource = cbrManager.getBlockRenderResource(blockDef.get());
            testBlocks.add(new TestBlock(resource, new Vector3f(x, y, z), color, resourceId));
        }
    }
    
    private void addLegacyBlock(BlockType blockType, float x, float y, float z, Vector3f color) {
        CBRResourceManager.BlockRenderResource resource = cbrManager.getBlockTypeResource(blockType);
        testBlocks.add(new TestBlock(resource, new Vector3f(x, y, z), color, "Legacy:" + blockType.name()));
    }
    
    private void addLegacyItem(ItemType itemType, float x, float y, float z, Vector3f color) {
        CBRResourceManager.BlockRenderResource resource = cbrManager.getItemTypeResource(itemType);
        testBlocks.add(new TestBlock(resource, new Vector3f(x, y, z), color, "Item:" + itemType.name()));
    }
    
    private void runRenderLoop() {
        System.out.println("✓ Starting render loop...\n");
        
        while (!glfwWindowShouldClose(window)) {
            // Calculate delta time
            float currentFrame = (float) glfwGetTime();
            deltaTime = currentFrame - lastFrame;
            lastFrame = currentFrame;
            
            // Process input
            processInput();
            
            // Render
            render();
            
            // Swap buffers and poll events
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }
    
    private void processInput() {
        float cameraSpeed = 2.5f * deltaTime;
        
        if (keys[GLFW_KEY_W]) {
            cameraPos.add(new Vector3f(cameraFront).mul(cameraSpeed));
        }
        if (keys[GLFW_KEY_S]) {
            cameraPos.sub(new Vector3f(cameraFront).mul(cameraSpeed));
        }
        if (keys[GLFW_KEY_A]) {
            cameraPos.sub(new Vector3f(cameraFront).cross(cameraUp).normalize().mul(cameraSpeed));
        }
        if (keys[GLFW_KEY_D]) {
            cameraPos.add(new Vector3f(cameraFront).cross(cameraUp).normalize().mul(cameraSpeed));
        }
        if (keys[GLFW_KEY_SPACE]) {
            cameraPos.add(new Vector3f(cameraUp).mul(cameraSpeed));
        }
        if (keys[GLFW_KEY_LEFT_SHIFT]) {
            cameraPos.sub(new Vector3f(cameraUp).mul(cameraSpeed));
        }
    }
    
    private void render() {
        // Clear the framebuffer
        glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        
        // Use shader program
        glUseProgram(shaderProgram);
        
        // Bind texture atlas
        textureAtlas.bind();
        
        // Create matrices
        Matrix4f projection = new Matrix4f().perspective(
            (float) Math.toRadians(45.0f),
            (float) windowWidth / (float) windowHeight,
            0.1f, 100.0f
        );
        
        Matrix4f view = new Matrix4f().lookAt(
            cameraPos,
            new Vector3f(cameraPos).add(cameraFront),
            cameraUp
        );
        
        // Set matrix uniforms
        setMatrix4f("projection", projection);
        setMatrix4f("view", view);
        setUniform1i("ourTexture", 0);
        
        // Render all test blocks
        for (TestBlock testBlock : testBlocks) {
            renderTestBlock(testBlock);
        }
    }
    
    private void renderTestBlock(TestBlock testBlock) {
        // Create model matrix
        Matrix4f model = new Matrix4f()
            .identity()
            .translate(testBlock.position)
            .rotate((float) glfwGetTime() * 0.5f, new Vector3f(0, 1, 0)); // Rotate around Y-axis
        
        // Set uniforms
        setMatrix4f("model", model);
        setVector3f("blockColor", testBlock.color);
        
        // Bind mesh and render
        testBlock.resource.getMesh().bind();
        testBlock.resource.getMesh().draw();
        testBlock.resource.getMesh().unbind();
    }
    
    // Utility methods for setting uniforms
    private void setMatrix4f(String name, Matrix4f matrix) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        matrix.get(buffer);
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, name), false, buffer);
    }
    
    private void setVector3f(String name, Vector3f vector) {
        glUniform3f(glGetUniformLocation(shaderProgram, name), vector.x, vector.y, vector.z);
    }
    
    private void setUniform1i(String name, int value) {
        glUniform1i(glGetUniformLocation(shaderProgram, name), value);
    }
    
    private void cleanup() {
        System.out.println("\nCleaning up...");
        
        try {
            // Cleanup shaders
            if (shaderProgram != 0) {
                glDeleteProgram(shaderProgram);
            }
            if (vertexShader != 0) {
                glDeleteShader(vertexShader);
            }
            if (fragmentShader != 0) {
                glDeleteShader(fragmentShader);
            }
            
            // Cleanup CBR framework
            if (cbrManager != null) {
                cbrManager.close();
            }
            
            // Cleanup GLFW
            if (window != NULL) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            
            System.out.println("✓ Cleanup completed");
            
        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }
    
    // === Helper Classes ===
    
    private static class TestBlock {
        final CBRResourceManager.BlockRenderResource resource;
        final Vector3f position;
        final Vector3f color;
        final String name;
        
        TestBlock(CBRResourceManager.BlockRenderResource resource, Vector3f position, Vector3f color, String name) {
            this.resource = resource;
            this.position = position;
            this.color = color;
            this.name = name;
        }
    }
    
    // Test implementation of BlockDefinitionRegistry
    private static class TestBlockDefinitionRegistry implements BlockDefinitionRegistry {
        private final Map<String, BlockDefinition> definitionsByName = new HashMap<>();
        private final Map<Integer, BlockDefinition> definitionsById = new HashMap<>();
        
        public TestBlockDefinitionRegistry() {
            // Create comprehensive test block definitions
            addTestDefinition("stonebreak:grass", 0, BlockDefinition.RenderType.CUBE_DIRECTIONAL, BlockDefinition.RenderLayer.OPAQUE);
            addTestDefinition("stonebreak:dirt", 1, BlockDefinition.RenderType.CUBE_ALL, BlockDefinition.RenderLayer.OPAQUE);
            addTestDefinition("stonebreak:stone", 2, BlockDefinition.RenderType.CUBE_ALL, BlockDefinition.RenderLayer.OPAQUE);
            addTestDefinition("stonebreak:sand", 6, BlockDefinition.RenderType.CUBE_ALL, BlockDefinition.RenderLayer.OPAQUE);
            addTestDefinition("stonebreak:dandelion", 16, BlockDefinition.RenderType.CROSS, BlockDefinition.RenderLayer.CUTOUT);
            addTestDefinition("stonebreak:stick", 100, BlockDefinition.RenderType.SPRITE, BlockDefinition.RenderLayer.CUTOUT);
        }
        
        private void addTestDefinition(String resourceId, int numericId, BlockDefinition.RenderType renderType, BlockDefinition.RenderLayer renderLayer) {
            BlockDefinition definition = new BlockDefinition.Builder()
                .resourceId(resourceId)
                .numericId(numericId)
                .renderType(renderType)
                .renderLayer(renderLayer)
                .textureVariables(Map.of("all", resourceId.substring(resourceId.indexOf(':') + 1)))
                .build();
            
            definitionsByName.put(resourceId, definition);
            definitionsById.put(numericId, definition);
        }
        
        @Override
        public java.util.Optional<BlockDefinition> getDefinition(String resourceId) {
            return java.util.Optional.ofNullable(definitionsByName.get(resourceId));
        }
        
        @Override
        public java.util.Optional<BlockDefinition> getDefinition(int numericId) {
            return java.util.Optional.ofNullable(definitionsById.get(numericId));
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
            return "1.0.0-visual-test";
        }
        
        @Override
        public void close() {
            definitionsByName.clear();
            definitionsById.clear();
        }
    }
}