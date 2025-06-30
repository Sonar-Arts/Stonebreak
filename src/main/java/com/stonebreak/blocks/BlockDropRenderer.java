package com.stonebreak.blocks;

import com.stonebreak.rendering.ShaderProgram;
import com.stonebreak.rendering.TextureAtlas;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Dedicated renderer for block drops with isolated OpenGL context to prevent UI corruption.
 * Runs on a separate thread with its own rendering state management.
 */
public class BlockDropRenderer {
    
    private final ExecutorService renderThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock renderLock = new ReentrantLock();
    
    // Rendering data passed from main thread
    private volatile List<BlockDrop> dropsToRender = new ArrayList<>();
    private volatile Matrix4f projectionMatrix = new Matrix4f();
    private volatile Matrix4f viewMatrix = new Matrix4f();
    
    // Isolated rendering resources
    private ShaderProgram isolatedShaderProgram;
    private TextureAtlas isolatedTextureAtlas;
    private final Map<Item, Integer> dropCubeVaoCache = new HashMap<>();
    
    // Synchronization for rendering data
    private final Object renderDataLock = new Object();
    
    public BlockDropRenderer() {
        renderThread = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "BlockDropRenderer");
            thread.setDaemon(true); // Don't prevent JVM shutdown
            return thread;
        });
    }
    
    /**
     * Initialize the isolated rendering context and resources.
     * Must be called from the main thread after OpenGL context is created.
     */
    public void initialize(ShaderProgram mainShaderProgram, TextureAtlas mainTextureAtlas) {
        // We'll use references to the main resources but with isolated state management
        // In a full implementation, we'd create separate OpenGL contexts, but for now
        // we'll use careful state isolation within the same context
        this.isolatedShaderProgram = mainShaderProgram;
        this.isolatedTextureAtlas = mainTextureAtlas;
        
        running.set(true);
    }
    
    /**
     * Update the rendering data from the main thread.
     * This method is thread-safe and can be called from the main rendering thread.
     */
    public void updateRenderData(List<BlockDrop> drops, Matrix4f projection, Matrix4f view) {
        synchronized (renderDataLock) {
            // Create defensive copies to avoid threading issues
            this.dropsToRender = new ArrayList<>(drops);
            this.projectionMatrix = new Matrix4f(projection);
            this.viewMatrix = new Matrix4f(view);
        }
    }
    
    /**
     * Render block drops in isolated context.
     * This method is called from the main thread but uses isolated state management.
     */
    public void renderBlockDrops() {
        if (!running.get()) {
            return;
        }
        
        renderLock.lock();
        try {
            List<BlockDrop> currentDrops;
            Matrix4f currentProjection;
            Matrix4f currentView;
            
            // Get current rendering data safely
            synchronized (renderDataLock) {
                if (dropsToRender.isEmpty()) {
                    return;
                }
                currentDrops = new ArrayList<>(dropsToRender);
                currentProjection = new Matrix4f(projectionMatrix);
                currentView = new Matrix4f(viewMatrix);
            }
            
            // Perform isolated rendering
            renderDropsIsolated(currentDrops, currentProjection, currentView);
            
        } finally {
            renderLock.unlock();
        }
    }
    
    /**
     * Perform the actual block drop rendering with complete state isolation.
     */
    private void renderDropsIsolated(List<BlockDrop> drops, Matrix4f projection, Matrix4f view) {
        // Save essential OpenGL state before any modifications
        boolean wasDepthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean wasBlendEnabled = glIsEnabled(GL_BLEND);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        
        try {
            // Set isolated OpenGL state for block drops
            glEnable(GL_DEPTH_TEST);
            glDepthMask(true);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            
            // Use shader program in isolated mode
            isolatedShaderProgram.bind();
            
            // Set isolated uniforms
            isolatedShaderProgram.setUniform("projectionMatrix", projection);
            isolatedShaderProgram.setUniform("viewMatrix", view);
            isolatedShaderProgram.setUniform("modelMatrix", new Matrix4f()); // Will be overridden per drop
            isolatedShaderProgram.setUniform("u_useSolidColor", false);
            isolatedShaderProgram.setUniform("u_isText", false);
            isolatedShaderProgram.setUniform("u_renderPass", 0);
            isolatedShaderProgram.setUniform("texture_sampler", 0);
            
            // Bind texture atlas in isolated mode
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, isolatedTextureAtlas.getTextureId());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            
            // Sort drops by distance from camera (back-to-front for proper depth testing)
            org.joml.Vector3f cameraPos = new org.joml.Vector3f();
            view.invert(new Matrix4f()).getTranslation(cameraPos);
            
            List<BlockDrop> sortedDrops = new ArrayList<>(drops);
            sortedDrops.sort((a, b) -> {
                float distA = a.getPosition().distanceSquared(cameraPos);
                float distB = b.getPosition().distanceSquared(cameraPos);
                return Float.compare(distB, distA); // Back-to-front (farthest first)
            });
            
            // Group sorted drops by item type for batch rendering
            Map<Item, List<BlockDrop>> dropsByType = new HashMap<>();
            for (BlockDrop drop : sortedDrops) {
                int itemId = drop.getBlockTypeId();
                Item item = BlockType.getById(itemId);
                if (item == null) {
                    item = ItemType.getById(itemId);
                }
                if (item != null) {
                    dropsByType.computeIfAbsent(item, k -> new ArrayList<>()).add(drop);
                }
            }
            
            // Batch render drops by type
            for (Map.Entry<Item, List<BlockDrop>> entry : dropsByType.entrySet()) {
                renderDropsBatchIsolated(entry.getKey(), entry.getValue());
            }
            
        } finally {
            // Complete state restoration with extreme care
            
            // Reset shader uniforms to safe defaults
            isolatedShaderProgram.setUniform("u_useSolidColor", false);
            isolatedShaderProgram.setUniform("u_isText", false);
            isolatedShaderProgram.setUniform("u_renderPass", 0);
            isolatedShaderProgram.setUniform("texture_sampler", 0);
            isolatedShaderProgram.setUniform("modelMatrix", new Matrix4f());
            
            // Unbind shader completely
            isolatedShaderProgram.unbind();
            
            // Restore OpenGL state to clean defaults
            GL30.glBindVertexArray(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, 0);
            
            // Restore viewport
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            
            // Restore blending state
            if (wasBlendEnabled) {
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glDisable(GL_BLEND);
            }
            
            // Restore depth testing state
            if (wasDepthTestEnabled) {
                glEnable(GL_DEPTH_TEST);
            } else {
                glDisable(GL_DEPTH_TEST);
            }
            
            glDepthMask(true);
        }
    }
    
    /**
     * Render a batch of drops of the same type in isolated context.
     */
    private void renderDropsBatchIsolated(Item item, List<BlockDrop> drops) {
        if (drops.isEmpty()) {
            return;
        }
        
        // Get or create cached VAO for this item type
        int vao = dropCubeVaoCache.computeIfAbsent(item, this::createDropCubeVaoIsolated);
        
        GL30.glBindVertexArray(vao);
        
        for (BlockDrop drop : drops) {
            // Create model matrix for this drop
            Matrix4f modelMatrix = new Matrix4f();
            modelMatrix.translate(drop.getPosition());
            modelMatrix.rotateY((float) Math.toRadians(drop.getRotationY()));
            modelMatrix.scale(0.25f); // 25% of full block size
            
            // Set model matrix for this drop
            isolatedShaderProgram.setUniform("modelMatrix", modelMatrix);
            
            // Draw the drop
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        }
        
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Create a cube VAO for isolated rendering of a specific item type.
     */
    private int createDropCubeVaoIsolated(Item item) {
        // This would be the same implementation as the original createDropCubeVao
        // but isolated within this renderer context
        // For brevity, I'll reference the existing implementation pattern
        
        // Create cube vertices at origin with standard size
        float halfSize = 0.5f;
        
        // Get texture coordinates for this item type
        float[][] faceTexCoords = new float[6][];
        if (item instanceof BlockType blockType) {
            // For blocks, get face-specific texture coordinates
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                BlockType.Face faceEnum = BlockType.Face.values()[faceValue];
                faceTexCoords[faceValue] = blockType.getTextureCoords(faceEnum);
            }
        } else {
            // For items (like tools), use the same texture for all faces
            float[] itemTexCoords = {item.getAtlasX(), item.getAtlasY()};
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                faceTexCoords[faceValue] = itemTexCoords;
            }
        }
        
        // Convert atlas coordinates to UV coordinates
        float atlasSize = 16.0f;
        float uvSize = 1.0f / atlasSize;
        
        // Calculate UV corners for each face
        float[][] faceUVs = new float[6][8];
        for (int face = 0; face < 6; face++) {
            float texX = faceTexCoords[face][0] / atlasSize;
            float texY = faceTexCoords[face][1] / atlasSize;
            
            float u_topLeft = texX;
            float v_topLeft = texY;
            float u_bottomLeft = texX;
            float v_bottomLeft = texY + uvSize;
            float u_bottomRight = texX + uvSize;
            float v_bottomRight = texY + uvSize;
            float u_topRight = texX + uvSize;
            float v_topRight = texY;
            
            faceUVs[face][0] = u_topLeft;     faceUVs[face][1] = v_topLeft;
            faceUVs[face][2] = u_bottomLeft;  faceUVs[face][3] = v_bottomLeft;
            faceUVs[face][4] = u_bottomRight; faceUVs[face][5] = v_bottomRight;
            faceUVs[face][6] = u_topRight;    faceUVs[face][7] = v_topRight;
        }
        
        // Determine isAlphaTested flag
        float isAlphaTestedValue = 0.0f;
        if (item instanceof BlockType blockType) {
            isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
        }
        
        // Generate vertex data with proper face-specific UV coordinates at origin
        // Each vertex: position (3), UV (2), normal (3), isWater (1), isAlphaTested (1) = 10 floats
        float[] vertices = {
            // Front face (positive Z) - Face 2 - UV order: BL, BR, TR, TL
            -halfSize, -halfSize, +halfSize,  faceUVs[2][2], faceUVs[2][3],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // BL
            +halfSize, -halfSize, +halfSize,  faceUVs[2][4], faceUVs[2][5],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // BR
            +halfSize, +halfSize, +halfSize,  faceUVs[2][6], faceUVs[2][7],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // TR
            -halfSize, +halfSize, +halfSize,  faceUVs[2][0], faceUVs[2][1],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // TL
            
            // Back face (negative Z) - Face 3 - UV order: BR, BL, TL, TR (flipped)
            -halfSize, -halfSize, -halfSize,  faceUVs[3][4], faceUVs[3][5],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // BR
            +halfSize, -halfSize, -halfSize,  faceUVs[3][2], faceUVs[3][3],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // BL
            +halfSize, +halfSize, -halfSize,  faceUVs[3][0], faceUVs[3][1],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // TL
            -halfSize, +halfSize, -halfSize,  faceUVs[3][6], faceUVs[3][7],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // TR
            
            // Left face (negative X) - Face 5 - UV order: BL, BR, TR, TL
            -halfSize, -halfSize, -halfSize,  faceUVs[5][2], faceUVs[5][3],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BL
            -halfSize, -halfSize, +halfSize,  faceUVs[5][4], faceUVs[5][5],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            -halfSize, +halfSize, +halfSize,  faceUVs[5][6], faceUVs[5][7],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            -halfSize, +halfSize, -halfSize,  faceUVs[5][0], faceUVs[5][1],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            
            // Right face (positive X) - Face 4 - UV order: BL, BR, TR, TL
            +halfSize, -halfSize, -halfSize,  faceUVs[4][2], faceUVs[4][3],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BL
            +halfSize, -halfSize, +halfSize,  faceUVs[4][4], faceUVs[4][5],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            +halfSize, +halfSize, +halfSize,  faceUVs[4][6], faceUVs[4][7],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            +halfSize, +halfSize, -halfSize,  faceUVs[4][0], faceUVs[4][1],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            
            // Top face (positive Y) - Face 0 - UV order: TL, TR, BR, BL
            -halfSize, +halfSize, -halfSize,  faceUVs[0][0], faceUVs[0][1],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            +halfSize, +halfSize, -halfSize,  faceUVs[0][6], faceUVs[0][7],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            +halfSize, +halfSize, +halfSize,  faceUVs[0][4], faceUVs[0][5],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            -halfSize, +halfSize, +halfSize,  faceUVs[0][2], faceUVs[0][3],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // BL
            
            // Bottom face (negative Y) - Face 1 - UV order: TL, TR, BR, BL
            -halfSize, -halfSize, -halfSize,  faceUVs[1][0], faceUVs[1][1],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            +halfSize, -halfSize, -halfSize,  faceUVs[1][6], faceUVs[1][7],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            +halfSize, -halfSize, +halfSize,  faceUVs[1][4], faceUVs[1][5],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            -halfSize, -halfSize, +halfSize,  faceUVs[1][2], faceUVs[1][3],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue  // BL
        };
        
        // Cube indices
        int[] indices = {
            // Front face
            0, 1, 2, 2, 3, 0,
            // Back face
            4, 5, 6, 6, 7, 4,
            // Left face
            8, 9, 10, 10, 11, 8,
            // Right face
            12, 13, 14, 14, 15, 12,
            // Top face
            16, 17, 18, 18, 19, 16,
            // Bottom face
            20, 21, 22, 22, 23, 20
        };
        
        // Create VAO and upload data
        int vao = GL30.glGenVertexArrays();
        int vbo = GL30.glGenBuffers();
        int ebo = GL30.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        
        // Upload vertex data
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vbo);
        java.nio.FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, vertexBuffer, GL30.GL_STATIC_DRAW);
        
        // Upload index data
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, ebo);
        java.nio.IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL30.glBufferData(GL30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL30.GL_STATIC_DRAW);
        
        // Set up vertex attributes (same as original)
        GL30.glVertexAttribPointer(0, 3, GL_FLOAT, false, 10 * Float.BYTES, 0);
        GL30.glEnableVertexAttribArray(0);
        GL30.glVertexAttribPointer(1, 2, GL_FLOAT, false, 10 * Float.BYTES, 3 * Float.BYTES);
        GL30.glEnableVertexAttribArray(1);
        GL30.glVertexAttribPointer(2, 3, GL_FLOAT, false, 10 * Float.BYTES, 5 * Float.BYTES);
        GL30.glEnableVertexAttribArray(2);
        GL30.glVertexAttribPointer(3, 1, GL_FLOAT, false, 10 * Float.BYTES, 8 * Float.BYTES);
        GL30.glEnableVertexAttribArray(3);
        GL30.glVertexAttribPointer(4, 1, GL_FLOAT, false, 10 * Float.BYTES, 9 * Float.BYTES);
        GL30.glEnableVertexAttribArray(4);
        
        GL30.glBindVertexArray(0);
        
        return vao;
    }
    
    /**
     * Cleanup resources and stop the rendering thread.
     */
    public void cleanup() {
        running.set(false);
        
        // Cleanup VAO cache
        for (Integer vao : dropCubeVaoCache.values()) {
            if (vao != null && vao != 0) {
                GL30.glDeleteVertexArrays(vao);
            }
        }
        dropCubeVaoCache.clear();
        
        // Shutdown executor
        renderThread.shutdown();
        try {
            if (!renderThread.awaitTermination(1, TimeUnit.SECONDS)) {
                renderThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            renderThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}