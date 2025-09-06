package com.stonebreak.rendering.models.blocks;

import com.stonebreak.blocks.BlockDrop;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import org.joml.*;
import org.lwjgl.opengl.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Dedicated renderer for block drops with simplified state management.
 */
public class BlockDropRenderer {
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Rendering resources
    private ShaderProgram shaderProgram;
    private TextureAtlas textureAtlas;
    private final Map<Item, Integer> vaoCache = new HashMap<>();
    
    public BlockDropRenderer() {
        // Constructor simplified - no thread management needed
    }
    
    /**
     * Initialize the renderer with shader and texture resources.
     */
    public void initialize(ShaderProgram shaderProgram, TextureAtlas textureAtlas) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        running.set(true);
    }
    
    /**
     * Render block drops using the provided data.
     */
    public void renderBlockDrops(List<BlockDrop> drops, Matrix4f projection, Matrix4f view) {
        if (!running.get() || drops.isEmpty()) {
            return;
        }
        
        renderDrops(drops, projection, view);
    }
    
    /**
     * Perform the actual block drop rendering with state management.
     */
    private void renderDrops(List<BlockDrop> drops, Matrix4f projection, Matrix4f view) {
        OpenGLStateManager.SavedState savedState = OpenGLStateManager.saveState();
        
        try {
            OpenGLStateManager.configureForBlockDrops();
            
            shaderProgram.bind();
            setupShaderUniforms(projection, view);
            setupTexture();
            
            List<BlockDrop> sortedDrops = sortDropsByDistance(drops, view);
            Map<Item, List<BlockDrop>> dropsByType = groupDropsByType(sortedDrops);
            
            for (Map.Entry<Item, List<BlockDrop>> entry : dropsByType.entrySet()) {
                renderDropsBatch(entry.getKey(), entry.getValue());
            }
            
        } finally {
            resetShaderUniforms();
            shaderProgram.unbind();
            OpenGLStateManager.restoreState(savedState);
        }
    }
    
    /**
     * Setup shader uniforms for rendering.
     */
    private void setupShaderUniforms(Matrix4f projection, Matrix4f view) {
        shaderProgram.setUniform("projectionMatrix", projection);
        shaderProgram.setUniform("viewMatrix", view);
        shaderProgram.setUniform("modelMatrix", new Matrix4f()); // Will be overridden per drop
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_renderPass", 0);
        shaderProgram.setUniform("texture_sampler", 0);
    }
    
    /**
     * Setup texture for rendering.
     */
    private void setupTexture() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    
    /**
     * Reset shader uniforms to safe defaults.
     */
    private void resetShaderUniforms() {
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_renderPass", 0);
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("modelMatrix", new Matrix4f());
    }
    
    /**
     * Sort drops by distance from camera for proper depth rendering.
     */
    private List<BlockDrop> sortDropsByDistance(List<BlockDrop> drops, Matrix4f view) {
        Vector3f cameraPos = new Vector3f();
        view.invert(new Matrix4f()).getTranslation(cameraPos);
        
        List<BlockDrop> sortedDrops = new ArrayList<>(drops);
        sortedDrops.sort((a, b) -> {
            float distA = a.getPosition().distanceSquared(cameraPos);
            float distB = b.getPosition().distanceSquared(cameraPos);
            return Float.compare(distB, distA); // Back-to-front (farthest first)
        });
        
        return sortedDrops;
    }
    
    /**
     * Group drops by item type for batch rendering.
     */
    private Map<Item, List<BlockDrop>> groupDropsByType(List<BlockDrop> drops) {
        Map<Item, List<BlockDrop>> dropsByType = new HashMap<>();
        
        for (BlockDrop drop : drops) {
            int itemId = drop.getBlockTypeId();
            Item item = resolveItem(itemId);
            
            if (item != null) {
                dropsByType.computeIfAbsent(item, k -> new ArrayList<>()).add(drop);
            }
        }
        
        return dropsByType;
    }
    
    /**
     * Resolve item from ID, trying ItemType first then BlockType.
     */
    private Item resolveItem(int itemId) {
        Item item = ItemType.getById(itemId);
        if (item == null) {
            BlockType blockType = BlockType.getById(itemId);
            if (blockType != null && blockType != BlockType.AIR) {
                item = blockType;
            }
        }
        return item;
    }
    
    /**
     * Render a batch of drops of the same type.
     */
    private void renderDropsBatch(Item item, List<BlockDrop> drops) {
        if (drops.isEmpty()) {
            return;
        }
        
        if (item instanceof ItemType itemType) {
            renderItem2DDropsBatch(itemType, drops);
        } else {
            renderBlock3DDropsBatch(item, drops);
        }
    }
    
    /**
     * Render items (tools, sticks) as flat 2D sprites when dropped.
     */
    private void renderItem2DDropsBatch(ItemType itemType, List<BlockDrop> drops) {
        int vao = vaoCache.computeIfAbsent(itemType, item -> VAOFactory.createSprite2DVAO((ItemType) item));
        
        OpenGLStateManager.SavedState spriteState = OpenGLStateManager.saveState();
        OpenGLStateManager.configureFor2DSprites();
        
        try {
            glBindVertexArray(vao);
            
            for (BlockDrop drop : drops) {
                Matrix4f modelMatrix = createItemModelMatrix(drop);
                shaderProgram.setUniform("modelMatrix", modelMatrix);
                glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            }
            
            glBindVertexArray(0);
        } finally {
            OpenGLStateManager.restoreState(spriteState);
        }
    }
    
    /**
     * Render blocks as traditional 3D cubes when dropped.
     */
    private void renderBlock3DDropsBatch(Item item, List<BlockDrop> drops) {
        int vao = vaoCache.computeIfAbsent(item, VAOFactory::createCube3DVAO);
        
        glBindVertexArray(vao);
        
        for (BlockDrop drop : drops) {
            Matrix4f modelMatrix = createBlockModelMatrix(drop);
            shaderProgram.setUniform("modelMatrix", modelMatrix);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        }
        
        glBindVertexArray(0);
    }
    
    /**
     * Create model matrix for item rendering with rotation and scaling.
     */
    private Matrix4f createItemModelMatrix(BlockDrop drop) {
        Matrix4f modelMatrix = new Matrix4f();
        modelMatrix.translate(drop.getPosition());
        modelMatrix.rotateY((float) java.lang.Math.toRadians(drop.getRotationY()));
        modelMatrix.rotateX((float) java.lang.Math.toRadians(-15.0f)); // Tilt back slightly
        modelMatrix.scale(0.6f); // Make items more visible
        return modelMatrix;
    }
    
    /**
     * Create model matrix for block rendering with rotation and scaling.
     */
    private Matrix4f createBlockModelMatrix(BlockDrop drop) {
        Matrix4f modelMatrix = new Matrix4f();
        modelMatrix.translate(drop.getPosition());
        modelMatrix.rotateY((float) java.lang.Math.toRadians(drop.getRotationY()));
        modelMatrix.scale(0.25f); // 25% of full block size
        return modelMatrix;
    }
    
    /**
     * Cleanup resources.
     */
    public void cleanup() {
        running.set(false);
        
        // Cleanup VAO cache
        for (Integer vao : vaoCache.values()) {
            if (vao != null && vao != 0) {
                glDeleteVertexArrays(vao);
            }
        }
        vaoCache.clear();
    }
}