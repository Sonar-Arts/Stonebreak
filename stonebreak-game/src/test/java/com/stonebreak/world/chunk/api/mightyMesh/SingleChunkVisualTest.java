package com.stonebreak.world.chunk.api.mightyMesh;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsRenderableHandle;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Visual test for rendering a single chunk using the MMS (Mighty Mesh System) pipeline.
 *
 * Creates a chunk with various block types and renders it with full texture mapping.
 * Use WASD to move camera, mouse to look around, ESC to exit.
 *
 * Features:
 * - Real-time 3D chunk rendering with MMS
 * - Full texture atlas integration
 * - Camera controls for inspection
 * - Performance statistics display
 */
public class SingleChunkVisualTest {

    // Window and OpenGL context
    private long window;
    private int windowWidth = 1280;
    private int windowHeight = 720;

    // MMS Framework components
    private TextureAtlas textureAtlas;
    private MmsAPI mmsAPI;

    // Test chunk
    private Chunk chunk;
    private MmsMeshData meshData;
    private MmsRenderableHandle renderableHandle;

    // Shader program
    private int shaderProgram;
    private int vertexShader;
    private int fragmentShader;

    // Camera system
    private Vector3f cameraPos = new Vector3f(8.0f, 10.0f, 20.0f);
    private Vector3f cameraFront = new Vector3f(0.0f, 0.0f, -1.0f);
    private Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    private float yaw = -90.0f;
    private float pitch = -15.0f;
    private float lastX = windowWidth / 2.0f;
    private float lastY = windowHeight / 2.0f;
    private boolean firstMouse = true;

    // Movement and timing
    private boolean[] keys = new boolean[GLFW_KEY_LAST];
    private float deltaTime = 0.0f;
    private float lastFrame = 0.0f;
    private float cameraSpeed = 5.0f;

    // Face label toggle
    private boolean showFaceLabels = true;

    public static void main(String[] args) {
        SingleChunkVisualTest visualTest = new SingleChunkVisualTest();
        visualTest.run();
    }

    public void run() {
        System.out.println("=== MMS Single Chunk Visual Test ===");
        System.out.println("Controls:");
        System.out.println("  WASD - Move camera");
        System.out.println("  SPACE/SHIFT - Move up/down");
        System.out.println("  Mouse - Look around");
        System.out.println("  L - Toggle face labels (color-coded)");
        System.out.println("  ESC - Exit");
        System.out.println();
        System.out.println("Face Colors:");
        System.out.println("  Top (Y+) = GREEN");
        System.out.println("  Bottom (Y-) = BROWN");
        System.out.println("  North (Z-) = BLUE");
        System.out.println("  South (Z+) = RED");
        System.out.println("  East (X+) = YELLOW");
        System.out.println("  West (X-) = MAGENTA");
        System.out.println("======================================\n");

        try {
            initializeWindow();
            initializeOpenGL();
            initializeMMS();
            initializeShaders();
            createTestChunk();

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
        window = glfwCreateWindow(windowWidth, windowHeight, "MMS Single Chunk Visual Test", NULL, NULL);
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
            if (key == GLFW_KEY_L && action == GLFW_PRESS) {
                showFaceLabels = !showFaceLabels;
                System.out.println("Face labels: " + (showFaceLabels ? "ON" : "OFF"));
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
        float yoffset = lastY - (float) ypos;
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

        // Enable face culling with CCW front faces (OpenGL default)
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);

        // Enable alpha blending for transparent textures
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Set viewport
        glViewport(0, 0, windowWidth, windowHeight);

        System.out.println("✓ OpenGL initialized");
        System.out.println("  OpenGL Version: " + glGetString(GL_VERSION));
        System.out.println("  GLSL Version: " + glGetString(GL_SHADING_LANGUAGE_VERSION));
        System.out.println("  Face culling: ENABLED (CCW front faces, culling back faces)");
    }

    private void initializeMMS() {
        // Initialize texture atlas
        textureAtlas = new TextureAtlas(16);

        // Initialize MMS API
        mmsAPI = MmsAPI.initialize(textureAtlas, null);

        System.out.println("✓ MMS Framework initialized");
    }

    private void initializeShaders() {
        // Vertex shader source - matches MMS vertex format (pos + texCoord)
        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;

            out vec2 TexCoord;
            out vec3 FragPos;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
                TexCoord = aTexCoord;
                FragPos = aPos;
            }
            """;

        // Fragment shader source - with face direction indicators
        String fragmentShaderSource = """
            #version 330 core
            out vec4 FragColor;

            in vec2 TexCoord;
            in vec3 FragPos;

            uniform sampler2D ourTexture;
            uniform bool showFaceLabels;

            void main() {
                vec4 texColor = texture(ourTexture, TexCoord);

                // Discard fully transparent pixels
                if (texColor.a < 0.1) {
                    discard;
                }

                // Calculate face normal from derivatives
                vec3 normal = normalize(cross(dFdx(FragPos), dFdy(FragPos)));

                // Color-code faces by direction for debugging
                vec3 faceColor = vec3(1.0);
                if (showFaceLabels) {
                    float threshold = 0.9;
                    if (normal.y > threshold) {
                        // Top face - GREEN with 'T'
                        faceColor = vec3(0.3, 1.0, 0.3);
                    } else if (normal.y < -threshold) {
                        // Bottom face - BROWN with 'B'
                        faceColor = vec3(0.6, 0.4, 0.2);
                    } else if (normal.z < -threshold) {
                        // North face (-Z) - BLUE with 'N'
                        faceColor = vec3(0.3, 0.3, 1.0);
                    } else if (normal.z > threshold) {
                        // South face (+Z) - RED with 'S'
                        faceColor = vec3(1.0, 0.3, 0.3);
                    } else if (normal.x > threshold) {
                        // East face (+X) - YELLOW with 'E'
                        faceColor = vec3(1.0, 1.0, 0.3);
                    } else if (normal.x < -threshold) {
                        // West face (-X) - MAGENTA with 'W'
                        faceColor = vec3(1.0, 0.3, 1.0);
                    }
                }

                // Simple lighting
                float light = abs(normal.y) * 0.3 + 0.7;

                // Apply lighting and face color
                vec3 finalColor = showFaceLabels ?
                    mix(texColor.rgb, faceColor, 0.5) * light :
                    texColor.rgb * light;

                FragColor = vec4(finalColor, texColor.a);
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

    private void createTestChunk() {
        System.out.println("Creating test chunk...");

        // Create chunk at origin
        chunk = new Chunk(0, 0);

        // Fill with interesting terrain
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Create varied height terrain
                int height = 4 + (x + z) / 4;

                // Bedrock bottom
                chunk.setBlock(x, 0, z, BlockType.BEDROCK);

                // Stone layers
                for (int y = 1; y < height - 3; y++) {
                    chunk.setBlock(x, y, z, BlockType.STONE);
                }

                // Add some ores randomly
                if ((x + z) % 7 == 0 && height > 5) {
                    chunk.setBlock(x, height - 5, z, BlockType.COAL_ORE);
                }
                if ((x * z) % 11 == 0 && height > 6) {
                    chunk.setBlock(x, height - 6, z, BlockType.IRON_ORE);
                }

                // Dirt layer
                for (int y = Math.max(1, height - 3); y < height; y++) {
                    chunk.setBlock(x, y, z, BlockType.DIRT);
                }

                // Grass top
                chunk.setBlock(x, height, z, BlockType.GRASS);

                // Add some flowers on top
                if ((x + z * 2) % 13 == 0) {
                    chunk.setBlock(x, height + 1, z, BlockType.DANDELION);
                }
                if ((x * 2 + z) % 17 == 0) {
                    chunk.setBlock(x, height + 1, z, BlockType.ROSE);
                }

                // Add some trees (simple trunk)
                if (x % 5 == 2 && z % 5 == 2) {
                    for (int y = height + 1; y < height + 5; y++) {
                        chunk.setBlock(x, y, z, BlockType.WOOD);
                    }
                }
            }
        }

        System.out.println("✓ Test chunk created with varied terrain");

        // Generate mesh using MMS
        long startTime = System.currentTimeMillis();
        meshData = mmsAPI.generateChunkMesh(chunk);
        long genTime = System.currentTimeMillis() - startTime;

        System.out.println("✓ Mesh generated in " + genTime + "ms");
        System.out.println("  Vertices: " + meshData.getVertexCount());
        System.out.println("  Triangles: " + meshData.getTriangleCount());
        System.out.println("  Memory: " + meshData.getMemoryUsageBytes() + " bytes");

        // Upload to GPU
        startTime = System.currentTimeMillis();
        renderableHandle = mmsAPI.uploadMeshToGPU(meshData);
        long uploadTime = System.currentTimeMillis() - startTime;

        System.out.println("✓ Mesh uploaded to GPU in " + uploadTime + "ms");

        // Print MMS statistics
        mmsAPI.printStatistics();
    }

    private void runRenderLoop() {
        System.out.println("✓ Starting render loop...\n");

        int frameCount = 0;
        double fpsTimer = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            // Calculate delta time
            float currentFrame = (float) glfwGetTime();
            deltaTime = currentFrame - lastFrame;
            lastFrame = currentFrame;

            // Process input
            processInput();

            // Render
            render();

            // FPS counter
            frameCount++;
            if (currentFrame - fpsTimer >= 1.0) {
                glfwSetWindowTitle(window, "MMS Single Chunk Visual Test - FPS: " + frameCount);
                frameCount = 0;
                fpsTimer = currentFrame;
            }

            // Swap buffers and poll events
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
        // Clear the framebuffer
        glClearColor(0.53f, 0.81f, 0.92f, 1.0f); // Sky blue
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Use shader program
        glUseProgram(shaderProgram);

        // Bind texture atlas
        textureAtlas.bind();

        // Create projection matrix
        Matrix4f projection = new Matrix4f().perspective(
            (float) Math.toRadians(70.0f),
            (float) windowWidth / (float) windowHeight,
            0.1f, 1000.0f
        );

        // Create view matrix
        Matrix4f view = new Matrix4f().lookAt(
            cameraPos,
            new Vector3f(cameraPos).add(cameraFront),
            cameraUp
        );

        // Create model matrix (identity - chunk at origin)
        Matrix4f model = new Matrix4f().identity();

        // Set matrix uniforms
        setMatrix4f("projection", projection);
        setMatrix4f("view", view);
        setMatrix4f("model", model);
        setUniform1i("ourTexture", 0);
        setUniform1i("showFaceLabels", showFaceLabels ? 1 : 0);

        // Render the chunk
        if (renderableHandle != null) {
            renderableHandle.render();
        }
    }

    // Utility methods for setting uniforms
    private void setMatrix4f(String name, Matrix4f matrix) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        matrix.get(buffer);
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, name), false, buffer);
    }

    private void setUniform1i(String name, int value) {
        glUniform1i(glGetUniformLocation(shaderProgram, name), value);
    }

    private void setUniform1b(String name, boolean value) {
        glUniform1i(glGetUniformLocation(shaderProgram, name), value ? 1 : 0);
    }

    private void cleanup() {
        System.out.println("\nCleaning up...");

        try {
            // Cleanup renderable handle
            if (renderableHandle != null) {
                renderableHandle.close();
            }

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

            // Cleanup MMS
            MmsAPI.shutdown();

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
}
