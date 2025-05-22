package com.stonebreak;

import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES;
import static org.lwjgl.nanovg.NanoVGGL3.nvgCreate;
import static org.lwjgl.nanovg.NanoVGGL3.nvgDelete;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.system.MemoryStack.stackPush;

public class UIRenderer {
    private long vg;
    private int fontRegular = -1;
    private int fontBold = -1;
    private int fontMinecraft = -1;
    private int dirtTextureImage = -1;
    
    private static final int UI_FONT_SIZE = 18;
    private static final int UI_TITLE_SIZE = 48;
    private static final int UI_BUTTON_HEIGHT = 40;
    private static final int UI_BUTTON_WIDTH = 400;
    
    public void init() {
        vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
        if (vg == 0) {
            throw new RuntimeException("Could not init NanoVG.");
        }
        
        loadFonts();
        createDirtTexture();
    }
    
    private void loadFonts() {
        fontRegular = nvgCreateFont(vg, "sans", "src/main/resources/fonts/Roboto-VariableFont_wdth,wght.ttf");
        fontBold = nvgCreateFont(vg, "sans-bold", "src/main/resources/fonts/Roboto-VariableFont_wdth,wght.ttf");
        fontMinecraft = nvgCreateFont(vg, "minecraft", "src/main/resources/fonts/Minecraft.ttf");
        
        if (fontRegular == -1 || fontBold == -1) {
            System.err.println("Warning: Could not load regular fonts, using default");
        }
        if (fontMinecraft == -1) {
            System.err.println("Warning: Could not load Minecraft font, using regular font for title");
        }
    }
    
    private void createDirtTexture() {
        // Create a small dirt texture pattern similar to the game's dirt texture
        int textureSize = 64; // 64x64 dirt texture
        
        try (MemoryStack stack = stackPush()) {
            // Use ByteBuffer instead of byte array
            java.nio.ByteBuffer dirtData = stack.malloc(textureSize * textureSize * 4); // RGBA
            
            for (int y = 0; y < textureSize; y++) {
                for (int x = 0; x < textureSize; x++) {
                    // Enhanced dirt generation with more contrast and variation
                    float dirtX = (float) Math.sin(x * 0.7 + y * 0.3) * 0.5f + 0.5f;
                    float dirtY = (float) Math.cos(x * 0.4 + y * 0.8) * 0.5f + 0.5f;
                    float dirtNoise = (dirtX + dirtY) * 0.5f;
                    
                    // Add secondary noise for more texture variety
                    float noise2 = (float) (Math.sin(x * 0.2 + y * 0.15) * 0.3 + 0.5);
                    float combinedNoise = (dirtNoise * 0.7f + noise2 * 0.3f);
                    
                    // Base dirt colors with higher contrast
                    int r = (int) (120 + combinedNoise * 60);
                    int g = (int) (80 + combinedNoise * 50);
                    int b = (int) (50 + combinedNoise * 40);
                    
                    // Add more prominent rocks and roots
                    if ((x % 4 == 0 && y % 4 == 0) || ((x+1) % 6 == 0 && (y+2) % 5 == 0)) {
                        r = Math.max(0, r - 50);
                        g = Math.max(0, g - 40);
                        b = Math.max(0, b - 30);
                    }
                    
                    // Add lighter specks for variety
                    if ((x % 7 == 0 && y % 8 == 0) || ((x+3) % 9 == 0 && (y+1) % 7 == 0)) {
                        r = Math.min(255, r + 40);
                        g = Math.min(255, g + 30);
                        b = Math.min(255, b + 20);
                    }
                    
                    dirtData.put((byte) Math.min(255, Math.max(0, r)));
                    dirtData.put((byte) Math.min(255, Math.max(0, g)));
                    dirtData.put((byte) Math.min(255, Math.max(0, b)));
                    dirtData.put((byte) 255); // Alpha
                }
            }
            
            dirtData.flip(); // Prepare buffer for reading
            
            dirtTextureImage = nvgCreateImageRGBA(vg, textureSize, textureSize, NVG_IMAGE_REPEATX | NVG_IMAGE_REPEATY, dirtData);
            if (dirtTextureImage == -1) {
                System.err.println("Warning: Could not create dirt texture");
            }
        }
    }
    
    public void beginFrame(int width, int height, float pixelRatio) {
        nvgBeginFrame(vg, width, height, pixelRatio);
    }
    
    public void endFrame() {
        nvgEndFrame(vg);
    }
    
    public void renderMainMenu(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Draw dirt background
        if (dirtTextureImage != -1) {
            try (MemoryStack stack = stackPush()) {
                NVGPaint dirtPattern = NVGPaint.malloc(stack);
                // Make texture more visible by using smaller tile size and full opacity
                nvgImagePattern(vg, 0, 0, 96, 96, 0, dirtTextureImage, 1.0f, dirtPattern);
                
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, windowWidth, windowHeight);
                nvgFillPaint(vg, dirtPattern);
                nvgFill(vg);
                
                // Lighter overlay for better contrast but still visible texture
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, windowWidth, windowHeight);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 60, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
        }
        
        // Draw 3D Minecraft-style title
        drawMinecraftTitle(centerX, centerY - 120, "STONEBREAK");
        
        // Get selected button from main menu
        MainMenu mainMenu = Game.getInstance().getMainMenu();
        int selectedButton = mainMenu != null ? mainMenu.getSelectedButton() : -1;
        
        // Draw Minecraft-style buttons
        drawMinecraftButton("Singleplayer", centerX - UI_BUTTON_WIDTH/2, centerY - 20, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, selectedButton == 0);
        drawMinecraftButton("Settings", centerX - UI_BUTTON_WIDTH/2, centerY + 30, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, selectedButton == 1);
        drawMinecraftButton("Quit Game", centerX - UI_BUTTON_WIDTH/2, centerY + 80, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, selectedButton == 2);
    }
    
    private void drawMinecraftTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            // Use Minecraft font if available, otherwise fall back to bold
            String fontName = (fontMinecraft != -1) ? "minecraft" : "sans-bold";
            
            // Draw enhanced 3D shadow effect for blocky appearance
            for (int i = 6; i >= 0; i--) {
                nvgFontSize(vg, UI_TITLE_SIZE + 8); // Slightly larger for more impact
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                if (i == 0) {
                    // Top layer - bright white with slight yellow tint (like Minecraft logo)
                    nvgFillColor(vg, nvgRGBA(255, 255, 240, 255, NVGColor.malloc(stack)));
                } else if (i == 1) {
                    // Second layer - light gray for depth
                    nvgFillColor(vg, nvgRGBA(200, 200, 190, 255, NVGColor.malloc(stack)));
                } else {
                    // Shadow layers - progressively darker for depth
                    int darkness = Math.max(20, 80 - (i * 15));
                    nvgFillColor(vg, nvgRGBA(darkness, darkness, darkness, 220, NVGColor.malloc(stack)));
                }
                
                // Increase offset for more pronounced 3D effect
                float offsetX = i * 2.5f;
                float offsetY = i * 2.5f;
                nvgText(vg, centerX + offsetX, centerY + offsetY, title);
            }
        }
    }
    
    private void drawMinecraftButton(String text, float x, float y, float w, float h, boolean highlighted) {
        try (MemoryStack stack = stackPush()) {
            // Stone-like button background with better hover effects
            NVGPaint bg = NVGPaint.malloc(stack);
            if (highlighted) {
                // Brighter and more noticeable when hovered
                nvgLinearGradient(vg, x, y, x, y + h,
                    nvgRGBA(160, 160, 180, 255, NVGColor.malloc(stack)),
                    nvgRGBA(120, 120, 140, 255, NVGColor.malloc(stack)), bg);
            } else {
                // Standard stone color
                nvgLinearGradient(vg, x, y, x, y + h,
                    nvgRGBA(130, 130, 130, 255, NVGColor.malloc(stack)),
                    nvgRGBA(90, 90, 90, 255, NVGColor.malloc(stack)), bg);
            }
            
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgFillPaint(vg, bg);
            nvgFill(vg);
            
            // Enhanced 3D border effects
            float borderWidth = highlighted ? 3.0f : 2.0f;
            
            // Top/left light edge - brighter when hovered
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + h);
            nvgLineTo(vg, x, y);
            nvgLineTo(vg, x + w, y);
            if (highlighted) {
                nvgStrokeColor(vg, nvgRGBA(240, 240, 240, 255, NVGColor.malloc(stack)));
            } else {
                nvgStrokeColor(vg, nvgRGBA(200, 200, 200, 255, NVGColor.malloc(stack)));
            }
            nvgStrokeWidth(vg, borderWidth);
            nvgStroke(vg);
            
            // Bottom/right dark edge - more pronounced when hovered
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + h);
            nvgLineTo(vg, x + w, y + h);
            nvgLineTo(vg, x + w, y);
            if (highlighted) {
                nvgStrokeColor(vg, nvgRGBA(30, 30, 30, 255, NVGColor.malloc(stack)));
            } else {
                nvgStrokeColor(vg, nvgRGBA(50, 50, 50, 255, NVGColor.malloc(stack)));
            }
            nvgStrokeWidth(vg, borderWidth);
            nvgStroke(vg);
            
            // Add subtle glow effect when highlighted
            if (highlighted) {
                nvgBeginPath(vg);
                nvgRect(vg, x - 2, y - 2, w + 4, h + 4);
                nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 50, NVGColor.malloc(stack)));
                nvgStrokeWidth(vg, 1.0f);
                nvgStroke(vg);
            }
            
            // Button text - slightly brighter when hovered
            nvgFontSize(vg, UI_FONT_SIZE);
            nvgFontFace(vg, "sans");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(235, 235, 235, 255, NVGColor.malloc(stack)));
            }
            nvgText(vg, x + w * 0.5f, y + h * 0.5f, text);
        }
    }
    
    public void drawButton(String text, float x, float y, float w, float h, boolean highlighted) {
        drawMinecraftButton(text, x, y, w, h, highlighted);
    }
    
    public boolean isButtonClicked(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return mouseX >= buttonX && mouseX <= buttonX + buttonW && 
               mouseY >= buttonY && mouseY <= buttonY + buttonH;
    }
    
    public void cleanup() {
        if (vg != 0) {
            nvgDelete(vg);
        }
    }
    
    // Helper method to create NVGColor
    private NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}