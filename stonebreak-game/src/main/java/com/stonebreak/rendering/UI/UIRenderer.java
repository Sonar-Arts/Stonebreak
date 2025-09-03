package com.stonebreak.rendering.UI;

import java.nio.FloatBuffer;

import com.stonebreak.chat.ChatSystem;
import com.stonebreak.items.Item;
import com.stonebreak.rendering.TextureAtlas;
import com.stonebreak.rendering.UI.components.ChatRenderer;
import com.stonebreak.rendering.UI.components.OpenGLQuadRenderer;
import com.stonebreak.rendering.UI.menus.ItemIconRenderer;
import com.stonebreak.rendering.UI.menus.MenuRenderer;
import com.stonebreak.rendering.UI.menus.VolumeSliderRenderer;

import static org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES;
import static org.lwjgl.nanovg.NanoVGGL3.nvgCreate;
import static org.lwjgl.nanovg.NanoVGGL3.nvgDelete;
import static org.lwjgl.nanovg.NanoVG.nvgBeginFrame;
import static org.lwjgl.nanovg.NanoVG.nvgEndFrame;

/**
 * UIRenderer acts as a controller that delegates rendering tasks to specialized renderers.
 * This follows the controller pattern where the main renderer orchestrates calls to
 * smaller, focused renderers for different UI components.
 */
public class UIRenderer {
    private long vg;
    
    // Specialized renderers
    private MenuRenderer menuRenderer;
    private ChatRenderer chatRenderer;
    private VolumeSliderRenderer volumeSliderRenderer;
    private ItemIconRenderer itemIconRenderer;
    private OpenGLQuadRenderer openGLQuadRenderer;
    
    public UIRenderer() {
        openGLQuadRenderer = new OpenGLQuadRenderer();
    }
    
    public void init() {
        vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
        if (vg == 0) {
            throw new RuntimeException("Could not init NanoVG.");
        }
        
        // Initialize specialized renderers
        menuRenderer = new MenuRenderer(vg);
        chatRenderer = new ChatRenderer(vg);
        volumeSliderRenderer = new VolumeSliderRenderer(vg);
        itemIconRenderer = new ItemIconRenderer(vg);
        openGLQuadRenderer.initialize();
    }
    
    /**
     * Initializes the depth curtain renderer with the necessary parameters from the main renderer.
     * This should be called after the main renderer has been set up.
     * @param shaderProgram The main shader program
     * @param windowWidth Current window width
     * @param windowHeight Current window height
     * @param projectionMatrix The 3D projection matrix
     */
    public void initializeDepthCurtainRenderer(com.stonebreak.rendering.ShaderProgram shaderProgram, 
                                             int windowWidth, int windowHeight, 
                                             org.joml.Matrix4f projectionMatrix) {
        openGLQuadRenderer.initializeDepthCurtainRenderer(shaderProgram, windowWidth, windowHeight, projectionMatrix);
    }
    
    public void beginFrame(int width, int height, float pixelRatio) {
        // Store window dimensions for OpenGL quad rendering
        openGLQuadRenderer.setWindowDimensions(width, height);
        
        if (vg != 0) {
            nvgBeginFrame(vg, width, height, pixelRatio);
        }
    }
    
    public void endFrame() {
        if (vg != 0) {
            nvgEndFrame(vg);
        }
    }
    
    // ===== Menu Rendering Delegation =====
    
    public void renderMainMenu(int windowWidth, int windowHeight) {
        menuRenderer.renderMainMenu(windowWidth, windowHeight);
    }
    
    public void renderPauseMenu(int windowWidth, int windowHeight, boolean isQuitButtonHovered, boolean isSettingsButtonHovered) {
        menuRenderer.renderPauseMenu(windowWidth, windowHeight, isQuitButtonHovered, isSettingsButtonHovered);
    }
    
    public void renderSettingsMenu(int windowWidth, int windowHeight) {
        menuRenderer.renderSettingsMenu(windowWidth, windowHeight);
    }
    
    public void drawButton(String text, float x, float y, float w, float h, boolean highlighted) {
        menuRenderer.drawButton(text, x, y, w, h, highlighted);
    }
    
    public void drawDropdownButton(String text, float x, float y, float w, float h, boolean highlighted, boolean isOpen) {
        menuRenderer.drawDropdownButton(text, x, y, w, h, highlighted, isOpen);
    }
    
    public void drawDropdownMenu(String[] options, int selectedIndex, float x, float y, float w, float itemHeight) {
        menuRenderer.drawDropdownMenu(options, selectedIndex, x, y, w, itemHeight);
    }
    
    public boolean isButtonClicked(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return menuRenderer.isButtonClicked(mouseX, mouseY, buttonX, buttonY, buttonW, buttonH);
    }
    
    public boolean isPauseResumeClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return menuRenderer.isPauseResumeClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    public boolean isPauseSettingsClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return menuRenderer.isPauseSettingsClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    public boolean isPauseQuitClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return menuRenderer.isPauseQuitClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    // ===== Chat Rendering Delegation =====
    
    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
        chatRenderer.renderChat(chatSystem, windowWidth, windowHeight);
    }
    
    // ===== Volume Slider Rendering Delegation =====
    
    public void drawVolumeSlider(String label, float centerX, float centerY, float sliderWidth, float sliderHeight, float value, boolean highlighted) {
        volumeSliderRenderer.drawVolumeSlider(label, centerX, centerY, sliderWidth, sliderHeight, value, highlighted);
    }
    
    // ===== Item Icon Rendering Delegation =====
    
    public void renderItemIcon(float x, float y, float w, float h, Item item, TextureAtlas textureAtlas) {
        itemIconRenderer.renderItemIcon(x, y, w, h, item, textureAtlas);
    }
    
    public void renderItemIcon(float x, float y, float w, float h, int blockTypeId, TextureAtlas textureAtlas) {
        itemIconRenderer.renderItemIcon(x, y, w, h, blockTypeId, textureAtlas);
    }
    
    public void renderQuad(float x, float y, float w, float h, float r, float g, float b, float a) {
        itemIconRenderer.renderQuad(x, y, w, h, r, g, b, a);
    }
    
    public void renderOutline(float x, float y, float w, float h, float strokeWidth, float[] color) {
        itemIconRenderer.renderOutline(x, y, w, h, strokeWidth, color);
    }
    
    // ===== Text Rendering Delegation =====
    
    public float getTextWidth(String text, float fontSize, String fontFaceName) {
        return menuRenderer.getTextWidth(text, fontSize, fontFaceName);
    }
    
    public void drawText(String text, float x, float y, String fontFaceName, float fontSize, float r, float g, float b, float a) {
        menuRenderer.drawText(text, x, y, fontFaceName, fontSize, r, g, b, a);
    }
    
    // ===== OpenGL Quad Rendering Delegation =====
    
    public void drawQuad(com.stonebreak.rendering.ShaderProgram shaderProgram, int x, int y, int width, int height, int r, int g, int b, int a) {
        openGLQuadRenderer.drawQuad(shaderProgram, x, y, width, height, r, g, b, a);
    }
    
    public void drawTexturedQuadUI(com.stonebreak.rendering.ShaderProgram shaderProgram, int x, int y, int width, int height, int textureId, float u1, float v1, float u2, float v2) {
        openGLQuadRenderer.drawTexturedQuadUI(shaderProgram, x, y, width, height, textureId, u1, v1, u2, v2);
    }
    
    public void drawFlat2DItemInSlot(com.stonebreak.rendering.ShaderProgram shaderProgram, 
                                   com.stonebreak.blocks.BlockType type, 
                                   int screenSlotX, int screenSlotY, 
                                   int screenSlotWidth, int screenSlotHeight,
                                   TextureAtlas textureAtlas,
                                   FloatBuffer projectionMatrixBuffer,
                                   FloatBuffer viewMatrixBuffer) {
        openGLQuadRenderer.drawFlat2DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas, projectionMatrixBuffer, viewMatrixBuffer);
    }
    
    // ===== Depth Curtain Rendering Delegation =====
    
    public void renderInventoryDepthCurtain() {
        openGLQuadRenderer.renderInventoryDepthCurtain();
    }
    
    public void renderHotbarDepthCurtain() {
        openGLQuadRenderer.renderHotbarDepthCurtain();
    }
    
    public void renderPauseMenuDepthCurtain() {
        openGLQuadRenderer.renderPauseMenuDepthCurtain();
    }
    
    public void renderRecipeBookDepthCurtain() {
        openGLQuadRenderer.renderRecipeBookDepthCurtain();
    }
    
    public void renderWorkbenchDepthCurtain() {
        openGLQuadRenderer.renderWorkbenchDepthCurtain();
    }
    
    // ===== Utility Methods =====
    
    public long getVG() {
        return vg;
    }
    
    // Helper method for backward compatibility - delegates to MenuRenderer
    public org.lwjgl.nanovg.NVGColor nvgRGBA(int r, int g, int b, int a, org.lwjgl.nanovg.NVGColor color) {
        return menuRenderer.nvgRGBA(r, g, b, a, color);
    }
    
    public void cleanup() {
        // Cleanup OpenGL quad renderer
        if (openGLQuadRenderer != null) {
            openGLQuadRenderer.cleanup();
        }
        
        if (vg != 0) {
            try {
                nvgDelete(vg);
            } catch (Exception e) {
                System.err.println("Warning: Error during UIRenderer cleanup: " + e.getMessage());
            } finally {
                vg = 0;
            }
        }
    }
}