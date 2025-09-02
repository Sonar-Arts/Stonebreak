package com.stonebreak.ui;

import java.util.List;

import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_LEFT;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_RIGHT;
import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_REPEATX;
import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_REPEATY;
import static org.lwjgl.nanovg.NanoVG.nvgBeginFrame;
import static org.lwjgl.nanovg.NanoVG.nvgBeginPath;
import static org.lwjgl.nanovg.NanoVG.nvgClosePath;
import static org.lwjgl.nanovg.NanoVG.nvgCreateFont;
import static org.lwjgl.nanovg.NanoVG.nvgCreateFontMem;
import static org.lwjgl.nanovg.NanoVG.nvgCreateImageRGBA;
import static org.lwjgl.nanovg.NanoVG.nvgEndFrame;
import static org.lwjgl.nanovg.NanoVG.nvgFill;
import static org.lwjgl.nanovg.NanoVG.nvgFillColor;
import static org.lwjgl.nanovg.NanoVG.nvgFillPaint;
import static org.lwjgl.nanovg.NanoVG.nvgFontFace;
import static org.lwjgl.nanovg.NanoVG.nvgFontSize;
import static org.lwjgl.nanovg.NanoVG.nvgImagePattern;
import static org.lwjgl.nanovg.NanoVG.nvgLineTo;
import static org.lwjgl.nanovg.NanoVG.nvgMoveTo;
import static org.lwjgl.nanovg.NanoVG.nvgRect;
import static org.lwjgl.nanovg.NanoVG.nvgStroke;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeColor;
import static org.lwjgl.nanovg.NanoVG.nvgStrokeWidth;
import static org.lwjgl.nanovg.NanoVG.nvgText;
import static org.lwjgl.nanovg.NanoVG.nvgTextAlign;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES;
import static org.lwjgl.nanovg.NanoVGGL3.nvgCreate;
import static org.lwjgl.nanovg.NanoVGGL3.nvgDelete;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.chat.ChatMessage;
import com.stonebreak.chat.ChatSystem;
import com.stonebreak.core.Game;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.TextureAtlas;

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
        // Use classpath-relative paths for module compatibility with multiple fallback options
        String[] fontPaths = {
            "fonts/Roboto-VariableFont_wdth,wght.ttf"
        };
        
        String[] boldFontPaths = {
            "fonts/Roboto-Italic-VariableFont_wdth,wght.ttf",  // Use italic as bold variant
            "fonts/Roboto-VariableFont_wdth,wght.ttf"          // Fallback to regular
        };
        
        String[] minecraftPaths = {
            "fonts/Minecraft.ttf"
        };
        
        // Try loading regular font using memory buffer for accurate resource loading
        for (String path : fontPaths) {
            try {
                ByteBuffer fontBuffer = loadResourceToByteBuffer(path, 512 * 1024);
                fontRegular = nvgCreateFontMem(vg, "sans", fontBuffer, true);
                if (fontRegular != -1) {
                    System.out.println("Successfully loaded regular font from: " + path);
                    break;
                } else {
                    MemoryUtil.memFree(fontBuffer);
                }
            } catch (IOException e) {
                System.err.println("Failed to load regular font from: " + path + " - " + e.getMessage());
            }
        }
        
        // Try loading bold font using memory buffer with italic variant as primary choice
        for (String path : boldFontPaths) {
            try {
                ByteBuffer fontBuffer = loadResourceToByteBuffer(path, 512 * 1024);
                fontBold = nvgCreateFontMem(vg, "sans-bold", fontBuffer, true);
                if (fontBold != -1) {
                    System.out.println("Successfully loaded bold font from: " + path);
                    break;
                } else {
                    MemoryUtil.memFree(fontBuffer);
                }
            } catch (IOException e) {
                System.err.println("Failed to load bold font from: " + path + " - " + e.getMessage());
            }
        }
        
        // Try loading Minecraft font using memory buffer
        for (String path : minecraftPaths) {
            try {
                ByteBuffer fontBuffer = loadResourceToByteBuffer(path, 512 * 1024);
                fontMinecraft = nvgCreateFontMem(vg, "minecraft", fontBuffer, true);
                if (fontMinecraft != -1) {
                    System.out.println("Successfully loaded Minecraft font from: " + path);
                    break;
                } else {
                    MemoryUtil.memFree(fontBuffer);
                }
            } catch (IOException e) {
                System.err.println("Failed to load Minecraft font from: " + path + " - " + e.getMessage());
            }
        }
        
        if (fontRegular == -1 || fontBold == -1) {
            System.err.println("Warning: Could not load regular/bold fonts from classpath resources, using default");
            System.err.println("Attempted font paths: " + String.join(", ", fontPaths));
            System.err.println("Attempted bold font paths: " + String.join(", ", boldFontPaths));
        }
        if (fontMinecraft == -1) {
            System.err.println("Warning: Could not load Minecraft font from classpath resources, using regular font for title");
            System.err.println("Attempted Minecraft font paths: " + String.join(", ", minecraftPaths));
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
        if (vg != 0) {
            nvgBeginFrame(vg, width, height, pixelRatio);
        }
    }
    
    public void endFrame() {
        if (vg != 0) {
            nvgEndFrame(vg);
        }
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
            // Use Minecraft font if available, otherwise fall back to bold, then regular
            String fontName;
            if (fontMinecraft != -1) {
                fontName = "minecraft";
            } else if (fontBold != -1) {
                fontName = "sans-bold";
            } else if (fontRegular != -1) {
                fontName = "sans";
            } else {
                // Ultimate fallback to system default
                fontName = "default";
            }
            
            // Draw enhanced 3D shadow effect for blocky appearance
            for (int i = 6; i >= 0; i--) {
                nvgFontSize(vg, UI_TITLE_SIZE + 8); // Slightly larger for more impact
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                switch (i) {
                    case 0 -> // Top layer - bright white with slight yellow tint (like Minecraft logo)
                        nvgFillColor(vg, nvgRGBA(255, 255, 240, 255, NVGColor.malloc(stack)));
                    case 1 -> // Second layer - light gray for depth
                        nvgFillColor(vg, nvgRGBA(200, 200, 190, 255, NVGColor.malloc(stack)));
                    default -> { // Shadow layers - progressively darker for depth
                        int darkness = Math.max(20, 80 - (i * 15));
                        nvgFillColor(vg, nvgRGBA(darkness, darkness, darkness, 220, NVGColor.malloc(stack)));
                    }
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
            // Minecraft-style button with authentic colors and beveled edges
            float bevelSize = 3.0f;
            
            // Main button body - stone/cobblestone colors
            if (highlighted) {
                // Highlighted state - lighter stone with blue-ish tint (like Minecraft hover)
                nvgBeginPath(vg);
                nvgRect(vg, x + bevelSize, y + bevelSize, w - 2 * bevelSize, h - 2 * bevelSize);
                nvgFillColor(vg, nvgRGBA(170, 170, 190, 255, NVGColor.malloc(stack)));
                nvgFill(vg);
            } else {
                // Normal state - dark stone gray
                nvgBeginPath(vg);
                nvgRect(vg, x + bevelSize, y + bevelSize, w - 2 * bevelSize, h - 2 * bevelSize);
                nvgFillColor(vg, nvgRGBA(130, 130, 130, 255, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
            
            // Add texture pattern to simulate cobblestone
            for (int i = 0; i < 8; i++) {
                float px = x + bevelSize + (i % 4) * (w - 2 * bevelSize) / 4;
                float py = y + bevelSize + (i / 4) * (h - 2 * bevelSize) / 2;
                float size = 6;
                
                nvgBeginPath(vg);
                nvgRect(vg, px, py, size, size);
                if (highlighted) {
                    nvgFillColor(vg, nvgRGBA(150, 150, 170, 100, NVGColor.malloc(stack)));
                } else {
                    nvgFillColor(vg, nvgRGBA(110, 110, 110, 100, NVGColor.malloc(stack)));
                }
                nvgFill(vg);
            }
            
            // Top bevel - enhanced light highlight
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + w, y);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Left bevel - enhanced light highlight
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(160, 160, 160, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Bottom bevel - enhanced dark shadow
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + h);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Right bevel - enhanced dark shadow
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + w, y);
            nvgLineTo(vg, x + w, y + h);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Outer border for strong definition
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgStrokeWidth(vg, 2.5f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Button text with proper font fallback
            String fontName;
            if (fontMinecraft != -1) {
                fontName = "minecraft";
            } else if (fontRegular != -1) {
                fontName = "sans";
            } else if (fontBold != -1) {
                fontName = "sans-bold";
            } else {
                // Ultimate fallback to system default
                fontName = "default";
            }
            nvgFontSize(vg, UI_FONT_SIZE);
            nvgFontFace(vg, fontName);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            // Text shadow for depth
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
            nvgText(vg, x + w * 0.5f + 1, y + h * 0.5f + 1, text);
            
            // Main text
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(240, 240, 240, 255, NVGColor.malloc(stack)));
            }
            nvgText(vg, x + w * 0.5f, y + h * 0.5f, text);
        }
    }
    
    public void drawButton(String text, float x, float y, float w, float h, boolean highlighted) {
        drawMinecraftButton(text, x, y, w, h, highlighted);
    }
    
    public void drawDropdownButton(String text, float x, float y, float w, float h, boolean highlighted, boolean isOpen) {
        // Draw the main button
        drawMinecraftButton(text, x, y, w, h, highlighted);
        
        // Draw dropdown arrow
        try (MemoryStack stack = stackPush()) {
            float arrowX = x + w - 25;
            float arrowY = y + h / 2;
            float arrowSize = 6;
            
            nvgBeginPath(vg);
            if (isOpen) {
                // Up arrow when dropdown is open
                nvgMoveTo(vg, arrowX - arrowSize, arrowY + arrowSize/2);
                nvgLineTo(vg, arrowX, arrowY - arrowSize/2);
                nvgLineTo(vg, arrowX + arrowSize, arrowY + arrowSize/2);
            } else {
                // Down arrow when dropdown is closed
                nvgMoveTo(vg, arrowX - arrowSize, arrowY - arrowSize/2);
                nvgLineTo(vg, arrowX, arrowY + arrowSize/2);
                nvgLineTo(vg, arrowX + arrowSize, arrowY - arrowSize/2);
            }
            nvgClosePath(vg);
            
            // Arrow color
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(200, 200, 200, 255, NVGColor.malloc(stack)));
            }
            nvgFill(vg);
        }
    }
    
    public void drawDropdownMenu(String[] options, int selectedIndex, float x, float y, float w, float itemHeight) {
        try (MemoryStack stack = stackPush()) {
            float totalHeight = options.length * itemHeight;
            
            // Draw dropdown shadow for depth (slightly offset)
            nvgBeginPath(vg);
            nvgRect(vg, x + 3, y + 3, w, totalHeight);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 100, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Draw dropdown background
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, totalHeight);
            nvgFillColor(vg, nvgRGBA(130, 130, 130, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Draw dropdown border
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, totalHeight);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Draw dropdown items
            for (int i = 0; i < options.length; i++) {
                float itemY = y + i * itemHeight;
                boolean isSelected = (i == selectedIndex);
                
                // Highlight selected item
                if (isSelected) {
                    nvgBeginPath(vg);
                    nvgRect(vg, x + 2, itemY + 1, w - 4, itemHeight - 2);
                    nvgFillColor(vg, nvgRGBA(160, 160, 180, 255, NVGColor.malloc(stack)));
                    nvgFill(vg);
                }
                
                // Draw item separator line
                if (i > 0) {
                    nvgBeginPath(vg);
                    nvgMoveTo(vg, x + 5, itemY);
                    nvgLineTo(vg, x + w - 5, itemY);
                    nvgStrokeWidth(vg, 1.0f);
                    nvgStrokeColor(vg, nvgRGBA(80, 80, 80, 255, NVGColor.malloc(stack)));
                    nvgStroke(vg);
                }
                
                // Draw item text with proper font fallback
                String fontName;
                if (fontMinecraft != -1) {
                    fontName = "minecraft";
                } else if (fontRegular != -1) {
                    fontName = "sans";
                } else if (fontBold != -1) {
                    fontName = "sans-bold";
                } else {
                    fontName = "default";
                }
                nvgFontSize(vg, 16);
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                // Text shadow
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
                nvgText(vg, x + w/2 + 1, itemY + itemHeight/2 + 1, options[i]);
                
                // Main text
                if (isSelected) {
                    nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                } else {
                    nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
                }
                nvgText(vg, x + w/2, itemY + itemHeight/2, options[i]);
            }
        }
    }
    
    public void renderPauseMenu(int windowWidth, int windowHeight, boolean isQuitButtonHovered, boolean isSettingsButtonHovered) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Draw semi-transparent overlay
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, windowWidth, windowHeight);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 120, NVGColor.malloc(stack)));
            nvgFill(vg);
        }
        
        // Draw main pause panel with enhanced Minecraft styling (made taller for 3 buttons)
        float panelWidth = 520;
        float panelHeight = 450;
        float panelX = centerX - panelWidth / 2;
        float panelY = centerY - panelHeight / 2;
        
        drawMinecraftPanel(panelX, panelY, panelWidth, panelHeight);
        
        // Draw pause menu title with better positioning
        drawPauseMenuTitle(centerX, panelY + 70, "GAME PAUSED");
        
        // Draw resume button (moved up slightly)
        float buttonWidth = 360;
        float buttonHeight = 50;
        float resumeY = centerY - 60;
        drawMinecraftButton("Resume Game", centerX - buttonWidth/2, resumeY, buttonWidth, buttonHeight, false);
        
        // Draw settings button (new, in middle)
        float settingsY = centerY + 10;
        drawMinecraftButton("Settings", centerX - buttonWidth/2, settingsY, buttonWidth, buttonHeight, isSettingsButtonHovered);
        
        // Draw quit button (moved down slightly)
        float quitY = centerY + 80;
        drawMinecraftButton("Quit to Main Menu", centerX - buttonWidth/2, quitY, buttonWidth, buttonHeight, isQuitButtonHovered);
    }
    
    private void drawPauseMenuTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            // Use Minecraft font if available, otherwise fall back to bold, then regular
            String fontName;
            if (fontMinecraft != -1) {
                fontName = "minecraft";
            } else if (fontBold != -1) {
                fontName = "sans-bold";
            } else if (fontRegular != -1) {
                fontName = "sans";
            } else {
                fontName = "default";
            }
            
            // Draw enhanced 3D shadow effect optimized for pause menu
            for (int i = 4; i >= 0; i--) {
                nvgFontSize(vg, 42); // Slightly smaller than main menu
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                switch (i) {
                    case 0 -> // Top layer - bright red/orange for attention
                        nvgFillColor(vg, nvgRGBA(255, 220, 100, 255, NVGColor.malloc(stack)));
                    case 1 -> // Second layer - warm orange
                        nvgFillColor(vg, nvgRGBA(220, 180, 80, 255, NVGColor.malloc(stack)));
                    default -> { // Shadow layers - darker
                        int darkness = Math.max(30, 100 - (i * 20));
                        nvgFillColor(vg, nvgRGBA(darkness, darkness, darkness, 200, NVGColor.malloc(stack)));
                    }
                }
                
                float offsetX = i * 2.0f;
                float offsetY = i * 2.0f;
                nvgText(vg, centerX + offsetX, centerY + offsetY, title);
            }
        }
    }
    
    private void drawMinecraftPanel(float x, float y, float w, float h) {
        try (MemoryStack stack = stackPush()) {
            float bevelSize = 5.0f; // Slightly larger bevel for more depth
            
            // Main panel body - enhanced stone color with gradient-like effect
            nvgBeginPath(vg);
            nvgRect(vg, x + bevelSize, y + bevelSize, w - 2 * bevelSize, h - 2 * bevelSize);
            nvgFillColor(vg, nvgRGBA(95, 95, 95, 250, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Add enhanced stone texture pattern with variation
            int textureRows = 6;
            int textureCols = 8;
            for (int i = 0; i < textureRows * textureCols; i++) {
                float px = x + bevelSize + (i % textureCols) * (w - 2 * bevelSize) / textureCols;
                float py = y + bevelSize + (i / textureCols) * (h - 2 * bevelSize) / textureRows;
                float size = 12;
                
                nvgBeginPath(vg);
                nvgRect(vg, px + (i % 3), py + (i % 2), size, size);
                
                // Vary the texture darkness for more realistic stone look
                int variation = (i * 17) % 40; // Pseudo-random variation
                nvgFillColor(vg, nvgRGBA(75 + variation/2, 75 + variation/2, 75 + variation/2, 150, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
            
            // Top bevel - light highlight
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + w, y);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(160, 160, 160, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Left bevel - light highlight
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(140, 140, 140, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Bottom bevel - dark shadow
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + h);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Right bevel - dark shadow
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + w, y);
            nvgLineTo(vg, x + w, y + h);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Outer border
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }
    
    public boolean isButtonClicked(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return mouseX >= buttonX && mouseX <= buttonX + buttonW && 
               mouseY >= buttonY && mouseY <= buttonY + buttonH;
    }
    
    public boolean isPauseResumeClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        float buttonWidth = 360;
        float buttonHeight = 50;
        float resumeY = centerY - 60;
        
        return isButtonClicked(mouseX, mouseY, centerX - buttonWidth/2, resumeY, buttonWidth, buttonHeight);
    }
    
    public boolean isPauseSettingsClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        float buttonWidth = 360;
        float buttonHeight = 50;
        float settingsY = centerY + 10;
        
        return isButtonClicked(mouseX, mouseY, centerX - buttonWidth/2, settingsY, buttonWidth, buttonHeight);
    }
    
    public boolean isPauseQuitClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        float buttonWidth = 360;
        float buttonHeight = 50;
        float quitY = centerY + 80;
        
        return isButtonClicked(mouseX, mouseY, centerX - buttonWidth/2, quitY, buttonWidth, buttonHeight);
    }
    
    public long getVG() {
        return vg;
    }
    
    public void cleanup() {
        if (vg != 0) {
            try {
                nvgDelete(vg);
            } catch (Exception e) {
                System.err.println("Warning: Error during UIRenderer cleanup: " + e.getMessage());
            } finally {
                vg = 0; // Ensure vg is reset to prevent further use
            }
        }
    }
    
    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
        if (chatSystem == null) {
            return;
        }
        
        List<ChatMessage> visibleMessages = chatSystem.getVisibleMessages();
        if (visibleMessages.isEmpty() && !chatSystem.isOpen()) {
            return;
        }
        
        try (MemoryStack stack = stackPush()) {
            // Chat area settings
            float chatX = 20; // 20px from left edge
            float lineHeight = 20;
            float maxChatWidth = windowWidth * 0.4f; // Max 40% of screen width
            float inputBoxHeight = 25;
            float inputBoxMargin = 10;
            
            // Calculate starting position for chat messages
            // Messages should appear above the input box when chat is open
            float chatStartY;
            if (chatSystem.isOpen()) {
                // When chat is open, start messages above the input box
                chatStartY = windowHeight - inputBoxHeight - inputBoxMargin - lineHeight;
            } else {
                // When chat is closed, start messages from bottom of screen
                chatStartY = windowHeight - 20 - lineHeight;
            }
            
            // Render chat messages from bottom to top (newest at bottom, oldest at top)
            float currentY = chatStartY;
            for (int i = visibleMessages.size() - 1; i >= 0; i--) {
                ChatMessage message = visibleMessages.get(i);
                float alpha = message.getAlpha();
                
                if (alpha <= 0.0f) {
                    continue;
                }
                
                // Message background (semi-transparent black) - only when chat is open
                if (chatSystem.isOpen()) {
                    nvgBeginPath(vg);
                    nvgRect(vg, chatX - 5, currentY - lineHeight + 2, maxChatWidth + 10, lineHeight);
                    nvgFillColor(vg, nvgRGBA(0, 0, 0, (int)(80 * alpha), NVGColor.malloc(stack)));
                    nvgFill(vg);
                }
                
                // Message text
                nvgFontSize(vg, 14);
                nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
                nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
                
                float[] color = message.getColor();
                nvgFillColor(vg, nvgRGBA(
                    (int)(color[0] * 255), 
                    (int)(color[1] * 255), 
                    (int)(color[2] * 255), 
                    (int)(alpha * 255), 
                    NVGColor.malloc(stack)
                ));
                
                nvgText(vg, chatX, currentY - lineHeight/2, message.getText());
                currentY -= lineHeight; // Move up for next message
            }
            
            // Render chat input box when chat is open (always at the bottom)
            if (chatSystem.isOpen()) {
                float inputBoxY = windowHeight - inputBoxHeight - inputBoxMargin;
                renderChatInputBox(chatSystem, chatX, inputBoxY, maxChatWidth, stack);
            }
        }
    }
    
    private void renderChatInputBox(ChatSystem chatSystem, float x, float y, float width, MemoryStack stack) {
        float inputHeight = 25;
        
        // Input box background
        nvgBeginPath(vg);
        nvgRect(vg, x - 5, y, width + 10, inputHeight);
        nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
        nvgFill(vg);
        
        // Input box border
        nvgBeginPath(vg);
        nvgRect(vg, x - 5, y, width + 10, inputHeight);
        nvgStrokeWidth(vg, 1.0f);
        nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 200, NVGColor.malloc(stack)));
        nvgStroke(vg);
        
        // Input text
        nvgFontSize(vg, 14);
        nvgFontFace(vg, fontRegular != -1 ? "sans" : "default");
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
        
        String displayText = chatSystem.getDisplayInput();
        if (displayText.isEmpty()) {
            // Show prompt when empty
            nvgFillColor(vg, nvgRGBA(128, 128, 128, 255, NVGColor.malloc(stack)));
            nvgText(vg, x, y + inputHeight/2, "Type a message...");
        } else {
            nvgText(vg, x, y + inputHeight/2, displayText);
        }
    }
    
    public void renderSettingsMenu(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        // Draw dirt background
        if (dirtTextureImage != -1) {
            try (MemoryStack stack = stackPush()) {
                NVGPaint dirtPattern = NVGPaint.malloc(stack);
                nvgImagePattern(vg, 0, 0, 96, 96, 0, dirtTextureImage, 1.0f, dirtPattern);
                
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, windowWidth, windowHeight);
                nvgFillPaint(vg, dirtPattern);
                nvgFill(vg);
                
                // Darker overlay for settings
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, windowWidth, windowHeight);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 80, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
        }
        
        // Draw main settings panel
        float panelWidth = 600;
        float panelHeight = 400;
        float panelX = centerX - panelWidth / 2;
        float panelY = centerY - panelHeight / 2;
        
        drawMinecraftPanel(panelX, panelY, panelWidth, panelHeight);
        
        // Draw settings title
        drawSettingsTitle(centerX, panelY + 60, "SETTINGS");
    }
    
    private void drawSettingsTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            String fontName;
            if (fontMinecraft != -1) {
                fontName = "minecraft";
            } else if (fontBold != -1) {
                fontName = "sans-bold";
            } else if (fontRegular != -1) {
                fontName = "sans";
            } else {
                fontName = "default";
            }
            
            for (int i = 4; i >= 0; i--) {
                nvgFontSize(vg, 36);
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                switch (i) {
                    case 0 -> 
                        nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                    case 1 -> 
                        nvgFillColor(vg, nvgRGBA(200, 200, 200, 255, NVGColor.malloc(stack)));
                    default -> {
                        int darkness = Math.max(30, 80 - (i * 15));
                        nvgFillColor(vg, nvgRGBA(darkness, darkness, darkness, 200, NVGColor.malloc(stack)));
                    }
                }
                
                float offsetX = i * 2.0f;
                float offsetY = i * 2.0f;
                nvgText(vg, centerX + offsetX, centerY + offsetY, title);
            }
        }
    }
    
    public void drawVolumeSlider(String label, float centerX, float centerY, float sliderWidth, float sliderHeight, float value, boolean highlighted) {
        try (MemoryStack stack = stackPush()) {
            // Use the same button style as other menu items for consistency
            float buttonWidth = 400; // Same as BUTTON_WIDTH in SettingsMenu
            float buttonHeight = 60; // Taller to accommodate slider
            float buttonX = centerX - buttonWidth / 2;
            float buttonY = centerY - buttonHeight / 2;
            
            // Draw Minecraft-style button background (same as drawMinecraftButton)
            float bevelSize = 3.0f;
            
            // Main button body
            if (highlighted) {
                nvgBeginPath(vg);
                nvgRect(vg, buttonX + bevelSize, buttonY + bevelSize, buttonWidth - 2 * bevelSize, buttonHeight - 2 * bevelSize);
                nvgFillColor(vg, nvgRGBA(170, 170, 190, 255, NVGColor.malloc(stack)));
                nvgFill(vg);
            } else {
                nvgBeginPath(vg);
                nvgRect(vg, buttonX + bevelSize, buttonY + bevelSize, buttonWidth - 2 * bevelSize, buttonHeight - 2 * bevelSize);
                nvgFillColor(vg, nvgRGBA(130, 130, 130, 255, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
            
            // Add texture pattern to simulate cobblestone (same as buttons)
            for (int i = 0; i < 12; i++) {
                float px = buttonX + bevelSize + (i % 6) * (buttonWidth - 2 * bevelSize) / 6;
                float py = buttonY + bevelSize + (i / 6) * (buttonHeight - 2 * bevelSize) / 2;
                float size = 6;
                
                nvgBeginPath(vg);
                nvgRect(vg, px, py, size, size);
                if (highlighted) {
                    nvgFillColor(vg, nvgRGBA(150, 150, 170, 100, NVGColor.malloc(stack)));
                } else {
                    nvgFillColor(vg, nvgRGBA(110, 110, 110, 100, NVGColor.malloc(stack)));
                }
                nvgFill(vg);
            }
            
            // Button bevels (same as drawMinecraftButton)
            // Top bevel
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX, buttonY);
            nvgLineTo(vg, buttonX + buttonWidth, buttonY);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + bevelSize);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Left bevel
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX, buttonY);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + bevelSize);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX, buttonY + buttonHeight);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(160, 160, 160, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Bottom bevel
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX, buttonY + buttonHeight);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX + buttonWidth, buttonY + buttonHeight);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Right bevel
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX + buttonWidth, buttonY);
            nvgLineTo(vg, buttonX + buttonWidth, buttonY + buttonHeight);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Outer border
            nvgBeginPath(vg);
            nvgRect(vg, buttonX, buttonY, buttonWidth, buttonHeight);
            nvgStrokeWidth(vg, 2.5f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Draw label at top of button with proper font fallback
            String fontName;
            if (fontMinecraft != -1) {
                fontName = "minecraft";
            } else if (fontRegular != -1) {
                fontName = "sans";
            } else if (fontBold != -1) {
                fontName = "sans-bold";
            } else {
                fontName = "default";
            }
            nvgFontSize(vg, UI_FONT_SIZE);
            nvgFontFace(vg, fontName);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            // Text shadow
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
            nvgText(vg, centerX + 1, centerY - 15 + 1, label);
            
            // Main text
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(240, 240, 240, 255, NVGColor.malloc(stack)));
            }
            nvgText(vg, centerX, centerY - 15, label);
            
            // Slider track (positioned in the middle-bottom of button)
            float trackX = centerX - sliderWidth / 2;
            float trackY = centerY + 5; // Slightly below center
            
            nvgBeginPath(vg);
            nvgRect(vg, trackX, trackY, sliderWidth, sliderHeight);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            // Slider track border (inset style)
            nvgBeginPath(vg);
            nvgRect(vg, trackX, trackY, sliderWidth, sliderHeight);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Slider fill
            float fillWidth = sliderWidth * value;
            if (fillWidth > 0) {
                nvgBeginPath(vg);
                nvgRect(vg, trackX + 2, trackY + 2, fillWidth - 4, sliderHeight - 4);
                if (highlighted) {
                    nvgFillColor(vg, nvgRGBA(120, 220, 120, 255, NVGColor.malloc(stack)));
                } else {
                    nvgFillColor(vg, nvgRGBA(100, 200, 100, 255, NVGColor.malloc(stack)));
                }
                nvgFill(vg);
            }
            
            // Slider handle
            float handleX = trackX + fillWidth - 6;
            float handleY = trackY - 2;
            float handleW = 12;
            float handleH = sliderHeight + 4;
            
            nvgBeginPath(vg);
            nvgRect(vg, handleX, handleY, handleW, handleH);
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
            }
            nvgFill(vg);
            
            // Handle border
            nvgBeginPath(vg);
            nvgRect(vg, handleX, handleY, handleW, handleH);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            // Volume percentage text at bottom right of button
            String volumeText = Math.round(value * 100) + "%";
            nvgFontSize(vg, 12);
            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_MIDDLE);
            
            // Text shadow
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
            nvgText(vg, buttonX + buttonWidth - 8 + 1, trackY + sliderHeight/2 + 1, volumeText);
            
            // Main percentage text
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(240, 240, 240, 255, NVGColor.malloc(stack)));
            }
            nvgText(vg, buttonX + buttonWidth - 8, trackY + sliderHeight/2, volumeText);
        }
    }
    
    // Helper method to create NVGColor
    public NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }    /**
     * Calculates the width of a given text string using a specific font and size.
     * @param text The text to measure.
     * @param fontSize The size of the font.
     * @param fontFaceName The name of the font face (e.g., "sans", "minecraft").
     * @return The width of the text in pixels.
     */
    public float getTextWidth(String text, float fontSize, String fontFaceName) {
        if (vg == 0 || text == null || text.isEmpty()) {
            return 0;
        }
        nvgFontSize(vg, fontSize);
        nvgFontFace(vg, fontFaceName); // Use the specified font face
        // For nvgTextBounds, x and y are 0,0 as we only need the bounds relative to origin
        float[] bounds = new float[4]; // x, y, width, height of the text bounds
        org.lwjgl.nanovg.NanoVG.nvgTextBounds(vg, 0, 0, text, bounds);
        return bounds[2] - bounds[0]; // width is bounds[2] - bounds[0]
    }
 
    /**
     * Renders a simple colored quadrilateral.
     * @param x X-coordinate
     * @param y Y-coordinate
     * @param w Width
     * @param h Height
     * @param r Red component (0-1)
     * @param g Green component (0-1)
     * @param b Blue component (0-1)
     * @param a Alpha component (0-1)
     */
    public void renderQuad(float x, float y, float w, float h, float r, float g, float b, float a) {
        if (vg == 0) return;
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgFillColor(vg, nvgRGBA((int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255), NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }

    /**
     * Renders an outline for a rectangle.
     * @param x X-coordinate
     * @param y Y-coordinate
     * @param w Width
     * @param h Height
     * @param strokeWidth Width of the outline
     * @param color Float array for color {r, g, b, a}
     */
    public void renderOutline(float x, float y, float w, float h, float strokeWidth, float[] color) {
        if (vg == 0 || color == null || color.length < 4) return;
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgStrokeWidth(vg, strokeWidth);
            nvgStrokeColor(vg, nvgRGBA((int)(color[0]*255), (int)(color[1]*255), (int)(color[2]*255), (int)(color[3]*255), NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }

    /**
     * Renders an item icon using its item and texture atlas.
     * @param x X-coordinate
     * @param y Y-coordinate
     * @param w Width
     * @param h Height
     * @param item The item to render (BlockType or ItemType)
     * @param textureAtlas The texture atlas containing item/block textures.
     */
    public void renderItemIcon(float x, float y, float w, float h, Item item, TextureAtlas textureAtlas) {
        if (vg == 0 || textureAtlas == null || item == null) return;

        if (item == BlockType.AIR) return;

        // Get texture coordinates using the new atlas system
        // For blocks, use the top face; for items, use texture coordinate lookup
        float[] texCoords;
        if (item instanceof BlockType blockType) {
            texCoords = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.TOP);
        } else if (item instanceof ItemType itemType) {
            // Use new atlas coordinate system for items
            texCoords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        } else {
            // Legacy fallback for other item types
            float atlasSize = 16.0f; // Assuming 16x16 atlas
            float uvSize = 1.0f / atlasSize;
            float texX = item.getAtlasX() / atlasSize;
            float texY = item.getAtlasY() / atlasSize;
            
            // Create UV coordinates array in [u1, v1, u2, v2] format to match modern system
            texCoords = new float[]{
                texX,                          // u1 (left)
                texY,                          // v1 (top)
                texX + uvSize,                 // u2 (right)
                texY + uvSize                  // v2 (bottom)
            };
        }

        if (texCoords == null || texCoords.length < 4) {
            // Fallback or error, render a placeholder color
            renderQuad(x, y, w, h, 0.5f, 0.2f, 0.8f, 1f); // Purple placeholder
            System.err.println("UIRenderer: Invalid texCoords for item " + (item != null ? item.getClass().getSimpleName() : "null"));
            return;
        }
        
        // Validate UV coordinates are reasonable (0-1 range)
        if (texCoords[0] < 0 || texCoords[0] > 1 || texCoords[1] < 0 || texCoords[1] > 1 ||
            texCoords[2] < 0 || texCoords[2] > 1 || texCoords[3] < 0 || texCoords[3] > 1) {
            System.err.println("UIRenderer: Warning - UV coordinates out of range for item " + (item != null ? item.getClass().getSimpleName() : "null") + 
                              ": [" + texCoords[0] + ", " + texCoords[1] + ", " + texCoords[2] + ", " + texCoords[3] + "]");
        }

        // TextureAtlas typically binds its own texture. Here, we need to draw a sub-region of it
        // using NanoVG's image pattern. We need the atlas's texture ID.
        int atlasImageId;
        try {
            atlasImageId = textureAtlas.getNanoVGImageId(vg); // This method needs to exist in TextureAtlas
        } catch (Exception e) {
            System.err.println("UIRenderer: Failed to get NanoVG image ID from texture atlas: " + e.getMessage());
            renderQuad(x, y, w, h, 0.8f, 0.2f, 0.2f, 1f); // Red placeholder for NanoVG error
            return;
        }
        if (atlasImageId == -1) {
            System.err.println("UIRenderer: NanoVG image ID is -1, cannot render item icon");
            renderQuad(x, y, w, h, 0.2f, 0.8f, 0.2f, 1f); // Green placeholder for invalid image ID
            return;
        }

        try (MemoryStack stack = stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            
            // texCoords format from modern atlas: [u1, v1, u2, v2] 
            float u1 = texCoords[0];  // Left UV coordinate
            float v1 = texCoords[1];  // Top UV coordinate  
            float u2 = texCoords[2];  // Right UV coordinate
            float v2 = texCoords[3];  // Bottom UV coordinate
            
            float uv_w = u2 - u1;    // UV width
            float uv_h = v2 - v1;    // UV height
            
            // Get atlas dimensions in pixels
            float atlasWidth = textureAtlas.getTextureWidth();
            float atlasHeight = textureAtlas.getTextureHeight();
            
            // Validate atlas dimensions
            if (atlasWidth <= 0 || atlasHeight <= 0) {
                System.err.println("UIRenderer: Invalid atlas dimensions: " + atlasWidth + "x" + atlasHeight);
                renderQuad(x, y, w, h, 0.8f, 0.8f, 0.2f, 1f); // Yellow placeholder for invalid dimensions
                return;
            }
            
            // Validate UV dimensions
            if (uv_w <= 0 || uv_h <= 0) {
                System.err.println("UIRenderer: Invalid UV dimensions: " + uv_w + "x" + uv_h + " for item " + 
                                  (item != null ? item.getClass().getSimpleName() : "null"));
                renderQuad(x, y, w, h, 0.2f, 0.2f, 0.8f, 1f); // Blue placeholder for invalid UV dimensions
                return;
            }
            
            // Calculate the size of the full atlas when displayed at the icon size
            // If the UV region is uv_w wide, then the full atlas would be w/uv_w wide when displayed
            float fullAtlasDisplayWidth = w / uv_w;
            float fullAtlasDisplayHeight = h / uv_h;
            
            // Calculate pattern origin to position the UV region at (x,y)
            float patternX = x - (u1 * fullAtlasDisplayWidth);
            float patternY = y - (v1 * fullAtlasDisplayHeight);
            
            nvgImagePattern(vg,
                patternX,                    // Pattern origin X
                patternY,                    // Pattern origin Y  
                fullAtlasDisplayWidth,       // Pattern width (full atlas display width)
                fullAtlasDisplayHeight,      // Pattern height (full atlas display height)
                0,                          // Angle
                atlasImageId,               // Texture atlas NanoVG image ID
                1.0f,                       // Alpha
                paint);

            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }
    }

    /**
     * Renders an item icon using its block type ID and texture atlas (backwards compatibility).
     * @param x X-coordinate
     * @param y Y-coordinate
     * @param w Width
     * @param h Height
     * @param blockTypeId The ID of the block type for the icon.
     * @param textureAtlas The texture atlas containing item/block textures.
     */
    public void renderItemIcon(float x, float y, float w, float h, int blockTypeId, TextureAtlas textureAtlas) {
        // First try to find it as a BlockType
        BlockType blockType = BlockType.getById(blockTypeId);
        if (blockType != null) {
            renderItemIcon(x, y, w, h, blockType, textureAtlas);
            return;
        }
        
        // Try to find it as an ItemType
        ItemType itemType = ItemType.getById(blockTypeId);
        if (itemType != null) {
            renderItemIcon(x, y, w, h, itemType, textureAtlas);
            return;
        }
        
        // If not found, render placeholder
        renderQuad(x, y, w, h, 0.5f, 0.2f, 0.8f, 1f); // Purple placeholder
    }

    /**
     * Draws text using NanoVG.
     * @param text The string to draw.
     * @param x X-coordinate.
     * @param y Y-coordinate.
     * @param fontFaceName The NanoVG font face name (e.g., "sans", "minecraft").
     * @param fontSize The font size.
     * @param r Red component (0-1).
     * @param g Green component (0-1).
     * @param b Blue component (0-1).
     * @param a Alpha component (0-1).
     */
    public void drawText(String text, float x, float y, String fontFaceName, float fontSize, float r, float g, float b, float a) {
        if (vg == 0 || text == null || text.isEmpty()) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            nvgFontSize(vg, fontSize);
            nvgFontFace(vg, fontFaceName);
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE); // Default alignment, adjust if needed per call site
            nvgFillColor(vg, nvgRGBA((int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255), NVGColor.malloc(stack)));
            nvgText(vg, x, y, text);
        }
    }
    
    /**
     * Loads a resource to ByteBuffer for accurate font loading from classpath.
     * This mirrors the approach used in Font.java for consistent resource handling.
     */
    private ByteBuffer loadResourceToByteBuffer(String resource, int bufferSize) throws IOException {
        ByteBuffer buffer;
        
        // Try different approaches for module compatibility
        InputStream source = null;
        
        // First try: Module's class loader
        source = UIRenderer.class.getClassLoader().getResourceAsStream(resource);
        
        // Second try: Module class itself
        if (source == null) {
            source = UIRenderer.class.getResourceAsStream("/" + resource);
        }
        
        // Third try: Context class loader
        if (source == null) {
            source = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        }
        
        try (InputStream finalSource = source) {
            if (finalSource == null) {
                throw new IOException("Resource not found: " + resource);
            }
            
            try (ReadableByteChannel rbc = Channels.newChannel(finalSource)) {
                buffer = MemoryUtil.memAlloc(bufferSize);
                while (true) {
                    int bytes = rbc.read(buffer);
                    if (bytes == -1) {
                        break;
                    }
                    if (buffer.remaining() == 0) {
                        buffer = MemoryUtil.memRealloc(buffer, buffer.capacity() * 2);
                    }
                }
            }
        }
        
        buffer.flip();
        return buffer;
    }
}