package com.stonebreak.rendering.core.API.commonBlockResources.tests;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.meshing.MeshManager;
import com.stonebreak.rendering.core.API.commonBlockResources.texturing.TextureResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Simple visual test for the CBR (Common Block Resources) Framework.
 * 
 * This simplified version focuses on testing the core CBR functionality
 * without complex registry implementations. It demonstrates:
 * - MeshManager pre-built geometries
 * - TextureResourceManager coordinate resolution
 * - Legacy BlockType/ItemType compatibility
 * - Real-time 3D rendering
 */
public class CBRSimpleVisualTest {
    
    // Window and OpenGL context
    private long window;
    private int windowWidth = 1024;
    private int windowHeight = 768;
    
    // CBR Framework components
    private TextureAtlas textureAtlas;
    private CBRResourceManager cbrManager;
    private MeshManager meshManager;
    private TextureResourceManager textureManager;
    
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
    
    // Test objects to render
    private List<TestRenderObject> testObjects = new ArrayList<>();
    
    public static void main(String[] args) {
        CBRSimpleVisualTest visualTest = new CBRSimpleVisualTest();
        visualTest.run();
    }
    
    public void run() {
        System.out.println("=== CBR Framework Simple Visual Test ===");
        System.out.println("Controls:");
        System.out.println("  WASD - Move camera");
        System.out.println("  Mouse - Look around");
        System.out.println("  ESC - Exit");
        System.out.println("=========================================\n");
        
        try {
            initializeWindow();
            initializeOpenGL();
            initializeCBRFramework();
            initializeShaders();
            setupTestObjects();
            
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
        window = glfwCreateWindow(windowWidth, windowHeight, "CBR Framework Simple Visual Test", NULL, NULL);
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
        System.out.println("✓ TextureAtlas initialized");
        
        // Initialize individual managers for direct testing
        meshManager = new MeshManager();
        System.out.println("✓ MeshManager initialized");
        
        // Create a simple registry that implements the interface properly
        SimpleBlockRegistry simpleRegistry = new SimpleBlockRegistry();
        
        textureManager = new TextureResourceManager(textureAtlas, simpleRegistry);
        System.out.println("✓ TextureResourceManager initialized");
        
        // Initialize CBR resource manager
        cbrManager = CBRResourceManager.getInstance(textureAtlas, simpleRegistry);
        System.out.println("✓ CBRResourceManager initialized");
        
        // Print statistics
        MeshManager.MeshStatistics meshStats = meshManager.getStatistics();
        System.out.println("  Mesh Statistics: " + meshStats);
        
        TextureResourceManager.CacheStatistics texStats = textureManager.getCacheStatistics();
        System.out.println("  Texture Cache: " + texStats);
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
            uniform vec3 objectColor;
            
            void main() {
                vec4 texColor = texture(ourTexture, TexCoord);
                FragColor = texColor * vec4(objectColor, 1.0);
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
    
    private void setupTestObjects() {
        System.out.println("✓ Setting up test objects...");
        
        float spacing = 2.0f;
        
        // Test different mesh types directly
        addMeshTest(MeshManager.MeshType.CUBE, -spacing, 0, -spacing, new Vector3f(0.8f, 0.2f, 0.2f), "Cube Mesh");
        addMeshTest(MeshManager.MeshType.CROSS, 0, 0, -spacing, new Vector3f(0.2f, 0.8f, 0.2f), "Cross Mesh");
        addMeshTest(MeshManager.MeshType.SPRITE, spacing, 0, -spacing, new Vector3f(0.2f, 0.2f, 0.8f), "Sprite Mesh");
        
        // Test legacy block types using CBR
        addLegacyBlockTest(BlockType.GRASS, -spacing, 0, 0, new Vector3f(0.4f, 0.8f, 0.2f), "Legacy Grass");
        addLegacyBlockTest(BlockType.STONE, 0, 0, 0, new Vector3f(0.5f, 0.5f, 0.5f), "Legacy Stone");
        addLegacyBlockTest(BlockType.DIRT, spacing, 0, 0, new Vector3f(0.6f, 0.4f, 0.2f), "Legacy Dirt");
        
        // Test legacy item types using CBR
        addLegacyItemTest(ItemType.STICK, -spacing, 0, spacing, new Vector3f(0.6f, 0.3f, 0.1f), "Legacy Stick");
        addLegacyItemTest(ItemType.WOODEN_PICKAXE, 0, 0, spacing, new Vector3f(0.4f, 0.2f, 0.1f), "Legacy Pickaxe");
        
        System.out.println("✓ " + testObjects.size() + " test objects ready for rendering");
    }
    
    private void addMeshTest(MeshManager.MeshType meshType, float x, float y, float z, Vector3f color, String name) {
        MeshManager.MeshResource mesh = meshManager.getMesh(meshType);
        if (mesh != null) {
            testObjects.add(new TestRenderObject(mesh, null, new Vector3f(x, y, z), color, name));
        }
    }
    
    private void addLegacyBlockTest(BlockType blockType, float x, float y, float z, Vector3f color, String name) {
        try {
            CBRResourceManager.BlockRenderResource resource = cbrManager.getBlockTypeResource(blockType);
            testObjects.add(new TestRenderObject(resource.getMesh(), resource.getTextureCoords(), new Vector3f(x, y, z), color, name));
        } catch (Exception e) {
            System.err.println("Failed to create legacy block test for " + blockType + ": " + e.getMessage());
        }
    }
    
    private void addLegacyItemTest(ItemType itemType, float x, float y, float z, Vector3f color, String name) {
        try {
            CBRResourceManager.BlockRenderResource resource = cbrManager.getItemTypeResource(itemType);
            testObjects.add(new TestRenderObject(resource.getMesh(), resource.getTextureCoords(), new Vector3f(x, y, z), color, name));
        } catch (Exception e) {
            System.err.println("Failed to create legacy item test for " + itemType + ": " + e.getMessage());
        }
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
        
        // Render all test objects
        for (TestRenderObject obj : testObjects) {
            renderTestObject(obj);
        }
    }
    
    private void renderTestObject(TestRenderObject obj) {
        // Create model matrix
        Matrix4f model = new Matrix4f()
            .identity()
            .translate(obj.position)
            .rotate((float) glfwGetTime() * 0.5f, new Vector3f(0, 1, 0)); // Rotate around Y-axis
        
        // Set uniforms
        setMatrix4f("model", model);
        setVector3f("objectColor", obj.color);
        
        // Bind mesh and render
        obj.mesh.bind();
        obj.mesh.draw();
        obj.mesh.unbind();
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
            if (meshManager != null) {
                meshManager.close();
            }
            if (textureManager != null) {
                textureManager.close();
            }
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
    
    private static class TestRenderObject {
        final MeshManager.MeshResource mesh;
        final TextureResourceManager.TextureCoordinates textureCoords; // Can be null
        final Vector3f position;
        final Vector3f color;
        final String name;
        
        TestRenderObject(MeshManager.MeshResource mesh, 
                        TextureResourceManager.TextureCoordinates textureCoords,
                        Vector3f position, 
                        Vector3f color, 
                        String name) {
            this.mesh = mesh;
            this.textureCoords = textureCoords;
            this.position = position;
            this.color = color;
            this.name = name;
        }
    }
    
    // Simple registry implementation that works
    private static class SimpleBlockRegistry implements BlockDefinitionRegistry {
        
        @Override
        public java.util.Optional<BlockDefinition> getDefinition(String resourceId) {
            return java.util.Optional.empty(); // Not used in this simple test
        }
        
        @Override
        public java.util.Optional<BlockDefinition> getDefinition(int numericId) {
            return java.util.Optional.empty(); // Not used in this simple test
        }
        
        @Override
        public boolean hasDefinition(String resourceId) {
            return false;
        }
        
        @Override
        public boolean hasDefinition(int numericId) {
            return false;
        }
        
        @Override
        public java.util.Collection<BlockDefinition> getAllDefinitions() {
            return java.util.List.of();
        }
        
        @Override
        public java.util.Collection<BlockDefinition> getDefinitionsByNamespace(String namespace) {
            return java.util.List.of();
        }
        
        @Override
        public int getDefinitionCount() {
            return 0;
        }
        
        @Override
        public void registerDefinition(BlockDefinition definition) {
            // No-op for this simple test
        }
        
        @Override
        public boolean isModifiable() {
            return false;
        }
        
        @Override
        public String getSchemaVersion() {
            return "1.0.0-simple-test";
        }
        
        @Override
        public void close() {
            // No-op
        }
    }
}