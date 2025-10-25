package com.stonebreak.rendering.UI.menus;

import com.stonebreak.core.Game;
import com.stonebreak.rendering.UI.core.BaseRenderer;
import com.stonebreak.ui.MainMenu;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_REPEATX;
import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_REPEATY;
import static org.lwjgl.system.MemoryStack.stackPush;

public class MenuRenderer extends BaseRenderer {
    private int dirtTextureImage = -1;
    
    public MenuRenderer(long vg) {
        super(vg);
        loadFonts();
        createDirtTexture();
    }
    
    private void createDirtTexture() {
        int textureSize = 64;
        
        try (MemoryStack stack = stackPush()) {
            java.nio.ByteBuffer dirtData = stack.malloc(textureSize * textureSize * 4);
            
            for (int y = 0; y < textureSize; y++) {
                for (int x = 0; x < textureSize; x++) {
                    float dirtX = (float) Math.sin(x * 0.7 + y * 0.3) * 0.5f + 0.5f;
                    float dirtY = (float) Math.cos(x * 0.4 + y * 0.8) * 0.5f + 0.5f;
                    float dirtNoise = (dirtX + dirtY) * 0.5f;
                    
                    float noise2 = (float) (Math.sin(x * 0.2 + y * 0.15) * 0.3 + 0.5);
                    float combinedNoise = (dirtNoise * 0.7f + noise2 * 0.3f);
                    
                    int r = (int) (120 + combinedNoise * 60);
                    int g = (int) (80 + combinedNoise * 50);
                    int b = (int) (50 + combinedNoise * 40);
                    
                    if ((x % 4 == 0 && y % 4 == 0) || ((x+1) % 6 == 0 && (y+2) % 5 == 0)) {
                        r = Math.max(0, r - 50);
                        g = Math.max(0, g - 40);
                        b = Math.max(0, b - 30);
                    }
                    
                    if ((x % 7 == 0 && y % 8 == 0) || ((x+3) % 9 == 0 && (y+1) % 7 == 0)) {
                        r = Math.min(255, r + 40);
                        g = Math.min(255, g + 30);
                        b = Math.min(255, b + 20);
                    }
                    
                    dirtData.put((byte) Math.min(255, Math.max(0, r)));
                    dirtData.put((byte) Math.min(255, Math.max(0, g)));
                    dirtData.put((byte) Math.min(255, Math.max(0, b)));
                    dirtData.put((byte) 255);
                }
            }
            
            dirtData.flip();
            
            dirtTextureImage = nvgCreateImageRGBA(vg, textureSize, textureSize, NVG_IMAGE_REPEATX | NVG_IMAGE_REPEATY, dirtData);
            if (dirtTextureImage == -1) {
                System.err.println("Warning: Could not create dirt texture");
            }
        }
    }
    
    public void renderMainMenu(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        drawDirtBackground(windowWidth, windowHeight, 60);

        drawMinecraftTitle(centerX, centerY - 120, "STONEBREAK");

        MainMenu mainMenu = Game.getInstance().getMainMenu();
        int selectedButton = mainMenu != null ? mainMenu.getSelectedButton() : -1;

        // Draw splash text
        if (mainMenu != null) {
            String splashText = mainMenu.getCurrentSplashText();
            drawSplashText(centerX, centerY - 70, splashText);
        }

        drawMinecraftButton("Singleplayer", centerX - UI_BUTTON_WIDTH/2, centerY - 20, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, selectedButton == 0);
        drawMinecraftButton("Settings", centerX - UI_BUTTON_WIDTH/2, centerY + 30, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, selectedButton == 1);
        drawMinecraftButton("Quit Game", centerX - UI_BUTTON_WIDTH/2, centerY + 80, UI_BUTTON_WIDTH, UI_BUTTON_HEIGHT, selectedButton == 2);
    }
    
    public void renderPauseMenu(int windowWidth, int windowHeight, boolean isQuitButtonHovered, boolean isSettingsButtonHovered) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, windowWidth, windowHeight);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 120, NVGColor.malloc(stack)));
            nvgFill(vg);
        }

        float panelWidth = 520;
        float panelHeight = 450;
        float panelX = centerX - panelWidth / 2;
        float panelY = centerY - panelHeight / 2;

        drawMinecraftPanel(panelX, panelY, panelWidth, panelHeight);
        drawPauseMenuTitle(centerX, panelY + 70, "GAME PAUSED");

        float buttonWidth = 360;
        float buttonHeight = 50;
        float resumeY = centerY - 60;
        drawMinecraftButton("Resume Game", centerX - buttonWidth/2, resumeY, buttonWidth, buttonHeight, false);

        float settingsY = centerY + 10;
        drawMinecraftButton("Settings", centerX - buttonWidth/2, settingsY, buttonWidth, buttonHeight, isSettingsButtonHovered);

        float quitY = centerY + 80;
        drawMinecraftButton("Quit to Main Menu", centerX - buttonWidth/2, quitY, buttonWidth, buttonHeight, isQuitButtonHovered);
    }

    public void renderDeathMenu(int windowWidth, int windowHeight, boolean isRespawnButtonHovered) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        // Dark overlay
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, windowWidth, windowHeight);
            nvgFillColor(vg, nvgRGBA(80, 0, 0, 180, NVGColor.malloc(stack)));
            nvgFill(vg);
        }

        // "You Died!" text in red
        try (MemoryStack stack = stackPush()) {
            nvgFontSize(vg, 96);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

            // Shadow
            nvgFillColor(vg, nvgRGBA(40, 0, 0, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX + 4, centerY - 100 + 4, "You Died!");

            // Main text
            nvgFillColor(vg, nvgRGBA(255, 50, 50, 255, NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY - 100, "You Died!");
        }

        // Respawn button
        float buttonWidth = 360;
        float buttonHeight = 50;
        float respawnY = centerY + 20;
        drawMinecraftButton("Respawn", centerX - buttonWidth/2, respawnY, buttonWidth, buttonHeight, isRespawnButtonHovered);
    }
    
    public void renderSettingsMenu(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        drawDirtBackground(windowWidth, windowHeight, 80);
        
        // Calculate responsive panel dimensions based on window size
        float panelWidth = Math.min(700, windowWidth * 0.85f);  // 85% of window width, max 700px
        float panelHeight = Math.min(550, windowHeight * 0.85f); // 85% of window height, max 550px
        float panelX = centerX - panelWidth / 2;
        float panelY = centerY - panelHeight / 2;
        
        drawMinecraftPanel(panelX, panelY, panelWidth, panelHeight);
        
        // Position title relative to panel, with proper spacing
        float titleY = panelY + Math.max(40, panelHeight * 0.08f);
        drawSettingsTitle(centerX, titleY, "SETTINGS");
    }
    
    private void drawDirtBackground(int windowWidth, int windowHeight, int overlayAlpha) {
        if (dirtTextureImage != -1) {
            try (MemoryStack stack = stackPush()) {
                NVGPaint dirtPattern = NVGPaint.malloc(stack);
                nvgImagePattern(vg, 0, 0, 96, 96, 0, dirtTextureImage, 1.0f, dirtPattern);
                
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, windowWidth, windowHeight);
                nvgFillPaint(vg, dirtPattern);
                nvgFill(vg);
                
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, windowWidth, windowHeight);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, overlayAlpha, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
        }
    }
    
    private void drawMinecraftTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            String fontName = getFontName();
            
            for (int i = 6; i >= 0; i--) {
                nvgFontSize(vg, UI_TITLE_SIZE + 8);
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                switch (i) {
                    case 0 -> nvgFillColor(vg, nvgRGBA(255, 255, 240, 255, NVGColor.malloc(stack)));
                    case 1 -> nvgFillColor(vg, nvgRGBA(200, 200, 190, 255, NVGColor.malloc(stack)));
                    default -> {
                        int darkness = Math.max(20, 80 - (i * 15));
                        nvgFillColor(vg, nvgRGBA(darkness, darkness, darkness, 220, NVGColor.malloc(stack)));
                    }
                }
                
                float offsetX = i * 2.5f;
                float offsetY = i * 2.5f;
                nvgText(vg, centerX + offsetX, centerY + offsetY, title);
            }
        }
    }
    
    private void drawPauseMenuTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            String fontName = getFontName();
            
            for (int i = 4; i >= 0; i--) {
                nvgFontSize(vg, 42);
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                switch (i) {
                    case 0 -> nvgFillColor(vg, nvgRGBA(255, 220, 100, 255, NVGColor.malloc(stack)));
                    case 1 -> nvgFillColor(vg, nvgRGBA(220, 180, 80, 255, NVGColor.malloc(stack)));
                    default -> {
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
    
    private void drawSettingsTitle(float centerX, float centerY, String title) {
        try (MemoryStack stack = stackPush()) {
            String fontName = getBoldFontName();
            
            for (int i = 4; i >= 0; i--) {
                nvgFontSize(vg, 36);
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                switch (i) {
                    case 0 -> nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                    case 1 -> nvgFillColor(vg, nvgRGBA(200, 200, 200, 255, NVGColor.malloc(stack)));
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

    private void drawSplashText(float centerX, float centerY, String splashText) {
        if (splashText == null || splashText.isEmpty()) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            String fontName = getFontName();

            // Calculate animation time for pumping effect (2 Hz = 500ms cycle)
            long currentTime = System.currentTimeMillis();
            float animationTime = (currentTime % 500) / 500.0f;
            float scale = 1.0f + (float)(Math.sin(animationTime * Math.PI * 2) * 0.05f);
            float baseFontSize = 18;
            float animatedFontSize = baseFontSize * scale;

            // Apply scaling transformation
            nvgSave(vg);
            nvgTranslate(vg, centerX, centerY);
            nvgScale(vg, scale, scale);
            nvgTranslate(vg, -centerX, -centerY);

            nvgFontSize(vg, animatedFontSize);
            nvgFontFace(vg, fontName);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

            // Draw multiple shadow layers for depth effect (similar to title)
            for (int i = 3; i >= 0; i--) {
                switch (i) {
                    case 0 -> nvgFillColor(vg, nvgRGBA(255, 255, 85, 255, NVGColor.malloc(stack)));
                    case 1 -> nvgFillColor(vg, nvgRGBA(220, 220, 70, 255, NVGColor.malloc(stack)));
                    default -> {
                        int darkness = Math.max(20, 60 - (i * 20));
                        nvgFillColor(vg, nvgRGBA(darkness, darkness, darkness, 180, NVGColor.malloc(stack)));
                    }
                }

                float offsetX = i * 1.5f;
                float offsetY = i * 1.5f;
                nvgText(vg, centerX + offsetX, centerY + offsetY, splashText);
            }

            nvgRestore(vg);
        }
    }

    private void drawMinecraftButton(String text, float x, float y, float w, float h, boolean highlighted) {
        try (MemoryStack stack = stackPush()) {
            float bevelSize = 3.0f;
            
            if (highlighted) {
                nvgBeginPath(vg);
                nvgRect(vg, x + bevelSize, y + bevelSize, w - 2 * bevelSize, h - 2 * bevelSize);
                nvgFillColor(vg, nvgRGBA(170, 170, 190, 255, NVGColor.malloc(stack)));
                nvgFill(vg);
            } else {
                nvgBeginPath(vg);
                nvgRect(vg, x + bevelSize, y + bevelSize, w - 2 * bevelSize, h - 2 * bevelSize);
                nvgFillColor(vg, nvgRGBA(130, 130, 130, 255, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
            
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
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + w, y);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(160, 160, 160, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + h);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + w, y);
            nvgLineTo(vg, x + w, y + h);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgStrokeWidth(vg, 2.5f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            String fontName = getFontName();
            nvgFontSize(vg, UI_FONT_SIZE);
            nvgFontFace(vg, fontName);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
            nvgText(vg, x + w * 0.5f + 1, y + h * 0.5f + 1, text);
            
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(240, 240, 240, 255, NVGColor.malloc(stack)));
            }
            nvgText(vg, x + w * 0.5f, y + h * 0.5f, text);
        }
    }
    
    private void drawMinecraftPanel(float x, float y, float w, float h) {
        try (MemoryStack stack = stackPush()) {
            float bevelSize = 5.0f;
            
            nvgBeginPath(vg);
            nvgRect(vg, x + bevelSize, y + bevelSize, w - 2 * bevelSize, h - 2 * bevelSize);
            nvgFillColor(vg, nvgRGBA(95, 95, 95, 250, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            int textureRows = 6;
            int textureCols = 8;
            for (int i = 0; i < textureRows * textureCols; i++) {
                float px = x + bevelSize + (i % textureCols) * (w - 2 * bevelSize) / textureCols;
                float py = y + bevelSize + (i / textureCols) * (h - 2 * bevelSize) / textureRows;
                float size = 12;
                
                nvgBeginPath(vg);
                nvgRect(vg, px + (i % 3), py + (i % 2), size, size);
                
                int variation = (i * 17) % 40;
                nvgFillColor(vg, nvgRGBA(75 + variation/2, 75 + variation/2, 75 + variation/2, 150, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + w, y);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(160, 160, 160, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y);
            nvgLineTo(vg, x + bevelSize, y + bevelSize);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(140, 140, 140, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, y + h);
            nvgLineTo(vg, x + bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w, y + h);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, x + w, y);
            nvgLineTo(vg, x + w, y + h);
            nvgLineTo(vg, x + w - bevelSize, y + h - bevelSize);
            nvgLineTo(vg, x + w - bevelSize, y + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
        }
    }
    
    public void drawButton(String text, float x, float y, float w, float h, boolean highlighted) {
        drawMinecraftButton(text, x, y, w, h, highlighted);
    }
    
    public void drawDropdownButton(String text, float x, float y, float w, float h, boolean highlighted, boolean isOpen) {
        drawMinecraftButton(text, x, y, w, h, highlighted);
        
        try (MemoryStack stack = stackPush()) {
            float arrowX = x + w - 25;
            float arrowY = y + h / 2;
            float arrowSize = 6;
            
            nvgBeginPath(vg);
            if (isOpen) {
                nvgMoveTo(vg, arrowX - arrowSize, arrowY + arrowSize/2);
                nvgLineTo(vg, arrowX, arrowY - arrowSize/2);
                nvgLineTo(vg, arrowX + arrowSize, arrowY + arrowSize/2);
            } else {
                nvgMoveTo(vg, arrowX - arrowSize, arrowY - arrowSize/2);
                nvgLineTo(vg, arrowX, arrowY + arrowSize/2);
                nvgLineTo(vg, arrowX + arrowSize, arrowY - arrowSize/2);
            }
            nvgClosePath(vg);
            
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
            
            nvgBeginPath(vg);
            nvgRect(vg, x + 3, y + 3, w, totalHeight);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 100, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, totalHeight);
            nvgFillColor(vg, nvgRGBA(130, 130, 130, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, totalHeight);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            for (int i = 0; i < options.length; i++) {
                float itemY = y + i * itemHeight;
                boolean isSelected = (i == selectedIndex);
                
                if (isSelected) {
                    nvgBeginPath(vg);
                    nvgRect(vg, x + 2, itemY + 1, w - 4, itemHeight - 2);
                    nvgFillColor(vg, nvgRGBA(160, 160, 180, 255, NVGColor.malloc(stack)));
                    nvgFill(vg);
                }
                
                if (i > 0) {
                    nvgBeginPath(vg);
                    nvgMoveTo(vg, x + 5, itemY);
                    nvgLineTo(vg, x + w - 5, itemY);
                    nvgStrokeWidth(vg, 1.0f);
                    nvgStrokeColor(vg, nvgRGBA(80, 80, 80, 255, NVGColor.malloc(stack)));
                    nvgStroke(vg);
                }
                
                String fontName = getFontName();
                nvgFontSize(vg, 16);
                nvgFontFace(vg, fontName);
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
                nvgText(vg, x + w/2 + 1, itemY + itemHeight/2 + 1, options[i]);
                
                if (isSelected) {
                    nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                } else {
                    nvgFillColor(vg, nvgRGBA(220, 220, 220, 255, NVGColor.malloc(stack)));
                }
                nvgText(vg, x + w/2, itemY + itemHeight/2, options[i]);
            }
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

    public boolean isDeathRespawnClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        float buttonWidth = 360;
        float buttonHeight = 50;
        float respawnY = centerY + 20;

        return isButtonClicked(mouseX, mouseY, centerX - buttonWidth/2, respawnY, buttonWidth, buttonHeight);
    }
    
    /**
     * Draws a horizontal separator line for visual organization.
     * @param centerX Center X position
     * @param y Y position for the separator
     * @param width Width of the separator line
     */
    public void drawSeparatorLine(float centerX, float y, float width) {
        try (MemoryStack stack = stackPush()) {
            float x = centerX - width / 2.0f;
            float lineHeight = 1.5f;

            // Draw the separator line with a subtle color
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, lineHeight);
            nvgFillColor(vg, nvgRGBA(150, 150, 150, 100, NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }

    /**
     * Renders the world selection screen with scrollable world list and UI elements.
     */
    public void renderWorldSelectScreen(int width, int height, java.util.List<String> worldList, int selectedIndex,
                                       boolean showCreateDialog, int scrollOffset, int visibleItems, boolean createButtonSelected) {
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;

        // Draw three-section background
        drawTriSectionBackground(width, height);

        // Calculate panel dimensions
        float panelWidth = Math.min(600, width * 0.8f);
        float panelHeight = Math.min(500, height * 0.8f);
        float panelX = centerX - panelWidth / 2;
        float panelY = centerY - panelHeight / 2;

        // Draw main panel
        drawMinecraftPanel(panelX, panelY, panelWidth, panelHeight);

        // Draw title
        float titleY = panelY + 40;
        drawSettingsTitle(centerX, titleY, "SELECT WORLD");

        // Calculate world list area
        float listStartY = titleY + 60;
        float listHeight = panelHeight - 160; // Leave space for title and buttons
        float itemHeight = 40;

        // Draw world list
        if (!worldList.isEmpty()) {
            for (int i = 0; i < Math.min(visibleItems, worldList.size()); i++) {
                int worldIndex = i + scrollOffset;
                if (worldIndex >= worldList.size()) break;

                String worldName = worldList.get(worldIndex);
                float itemY = listStartY + i * itemHeight;
                boolean isSelected = worldIndex == selectedIndex;

                // Draw world item background
                try (MemoryStack stack = stackPush()) {
                    if (isSelected) {
                        nvgBeginPath(vg);
                        nvgRect(vg, panelX + 10, itemY, panelWidth - 20, itemHeight - 2);
                        nvgFillColor(vg, nvgRGBA(100, 100, 120, 200, NVGColor.malloc(stack)));
                        nvgFill(vg);
                    }

                    // Draw world name
                    nvgFontSize(vg, 18);
                    nvgFontFace(vg, getFontName());
                    nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
                    nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
                    nvgText(vg, panelX + 20, itemY + itemHeight / 2, worldName);
                }
            }
        } else {
            // No worlds message
            try (MemoryStack stack = stackPush()) {
                nvgFontSize(vg, 16);
                nvgFontFace(vg, getFontName());
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
                nvgText(vg, centerX, listStartY + listHeight / 2, "No worlds found. Create a new world to get started!");
            }
        }

        // Draw buttons at bottom
        float buttonY = panelY + panelHeight - 60;
        float buttonWidth = 120;
        float buttonHeight = 35;
        float buttonSpacing = 20;

        // Create New World button
        float createButtonX = centerX - buttonWidth - buttonSpacing / 2;
        drawMinecraftButton("Create New", createButtonX, buttonY, buttonWidth, buttonHeight, createButtonSelected);

        // Load Selected World button (only show if world is selected)
        if (selectedIndex >= 0 && selectedIndex < worldList.size()) {
            float loadButtonX = centerX + buttonSpacing / 2;
            drawMinecraftButton("Load World", loadButtonX, buttonY, buttonWidth, buttonHeight, false);
        }

        // Back button
        float backButtonX = panelX + 10;
        float backButtonY = panelY + 10;
        drawMinecraftButton("Back", backButtonX, backButtonY, 80, 30, false);
    }

    /**
     * Renders the create world dialog container background.
     */
    public void renderCreateDialogContainer(int width, int height) {
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;

        // Calculate dialog dimensions
        float dialogWidth = Math.min(400, width * 0.6f);
        float dialogHeight = Math.min(300, height * 0.5f);
        float dialogX = centerX - dialogWidth / 2;
        float dialogY = centerY - dialogHeight / 2;

        // Draw semi-transparent overlay
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, width, height);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 120, NVGColor.malloc(stack)));
            nvgFill(vg);
        }

        // Draw dialog panel
        drawMinecraftPanel(dialogX, dialogY, dialogWidth, dialogHeight);

        // Draw dialog title
        float titleY = dialogY + 40;
        drawSettingsTitle(centerX, titleY, "CREATE NEW WORLD");

        // Draw labels for input fields
        try (MemoryStack stack = stackPush()) {
            nvgFontSize(vg, 16);
            nvgFontFace(vg, getFontName());
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));

            // World name label
            nvgText(vg, dialogX + 20, dialogY + 100, "World Name:");

            // Seed label
            nvgText(vg, dialogX + 20, dialogY + 160, "Seed (optional):");
        }
    }

    // ===== Text Input Rendering Methods =====

    public void drawTextInputBackground(float x, float y, float width, float height, boolean focused, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, width, height);

        // Background color - darker when focused
        if (focused) {
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
        } else {
            nvgFillColor(vg, nvgRGBA(30, 30, 30, 255, NVGColor.malloc(stack)));
        }
        nvgFill(vg);
    }

    public void drawTextInputBorder(float x, float y, float width, float height, boolean focused, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, width, height);

        // Border color - blue when focused, gray when not
        if (focused) {
            nvgStrokeColor(vg, nvgRGBA(100, 150, 255, 255, NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 2.0f);
        } else {
            nvgStrokeColor(vg, nvgRGBA(100, 100, 100, 255, NVGColor.malloc(stack)));
            nvgStrokeWidth(vg, 1.0f);
        }
        nvgStroke(vg);
    }

    public void drawTextInputIcon(float x, float y, float size, String iconType, MemoryStack stack) {
        // Simple icon drawing - can be enhanced with actual icons later
        nvgBeginPath(vg);
        nvgCircle(vg, x + size/2, y + size/2, size/3);
        nvgFillColor(vg, nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack)));
        nvgFill(vg);
    }

    public void drawTextInputText(float x, float y, String text, boolean isPlaceholder, MemoryStack stack) {
        nvgFontSize(vg, 16);
        nvgFontFace(vg, getFontName());
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);

        if (isPlaceholder) {
            // Placeholder text - lighter gray
            nvgFillColor(vg, nvgRGBA(150, 150, 150, 255, NVGColor.malloc(stack)));
        } else {
            // Regular text - white
            nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
        }

        nvgText(vg, x, y, text);
    }

    public void drawTextInputCursor(float x, float y1, float y2, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgMoveTo(vg, x, y1);
        nvgLineTo(vg, x, y2);
        nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
        nvgStrokeWidth(vg, 1.0f);
        nvgStroke(vg);
    }

    public void drawValidationIndicator(float x, float y, float size, boolean isValid, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgCircle(vg, x + size/2, y + size/2, size/2);

        if (isValid) {
            // Green checkmark circle
            nvgFillColor(vg, nvgRGBA(50, 200, 50, 255, NVGColor.malloc(stack)));
        } else {
            // Red error circle
            nvgFillColor(vg, nvgRGBA(200, 50, 50, 255, NVGColor.malloc(stack)));
        }
        nvgFill(vg);

        // Draw simple indicator mark
        nvgBeginPath(vg);
        if (isValid) {
            // Simple checkmark
            float centerX = x + size/2;
            float centerY = y + size/2;
            nvgMoveTo(vg, centerX - size/4, centerY);
            nvgLineTo(vg, centerX - size/8, centerY + size/8);
            nvgLineTo(vg, centerX + size/4, centerY - size/4);
        } else {
            // Simple X
            float centerX = x + size/2;
            float centerY = y + size/2;
            nvgMoveTo(vg, centerX - size/4, centerY - size/4);
            nvgLineTo(vg, centerX + size/4, centerY + size/4);
            nvgMoveTo(vg, centerX + size/4, centerY - size/4);
            nvgLineTo(vg, centerX - size/4, centerY + size/4);
        }
        nvgStrokeColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
        nvgStrokeWidth(vg, 1.5f);
        nvgStroke(vg);
    }

    /**
     * Draws a three-section background for the world select screen.
     * Top section (15%): dirt texture
     * Middle section (70%): stone texture
     * Bottom section (15%): dirt texture
     */
    private void drawTriSectionBackground(int width, int height) {
        // Calculate section heights
        float topHeight = height * 0.15f;
        float middleHeight = height * 0.70f;
        float bottomHeight = height * 0.15f;

        float middleY = topHeight;
        float bottomY = topHeight + middleHeight;

        // Draw top dirt section
        if (dirtTextureImage != -1) {
            try (MemoryStack stack = stackPush()) {
                NVGPaint dirtPattern = NVGPaint.malloc(stack);
                nvgImagePattern(vg, 0, 0, 96, 96, 0, dirtTextureImage, 1.0f, dirtPattern);

                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, width, topHeight);
                nvgFillPaint(vg, dirtPattern);
                nvgFill(vg);

                // Add overlay for consistency
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, width, topHeight);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 40, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
        }

        // Draw middle stone section
        drawStoneBackground(0, middleY, width, middleHeight);

        // Add subtle gradient at top of stone section
        try (MemoryStack stack = stackPush()) {
            NVGPaint gradientPaint = NVGPaint.malloc(stack);
            nvgLinearGradient(vg, 0, middleY, 0, middleY + 20,
                nvgRGBA(0, 0, 0, 60, NVGColor.malloc(stack)),
                nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)), gradientPaint);

            nvgBeginPath(vg);
            nvgRect(vg, 0, middleY, width, 20);
            nvgFillPaint(vg, gradientPaint);
            nvgFill(vg);
        }

        // Draw bottom dirt section
        if (dirtTextureImage != -1) {
            try (MemoryStack stack = stackPush()) {
                NVGPaint dirtPattern = NVGPaint.malloc(stack);
                nvgImagePattern(vg, 0, bottomY, 96, 96, 0, dirtTextureImage, 1.0f, dirtPattern);

                nvgBeginPath(vg);
                nvgRect(vg, 0, bottomY, width, bottomHeight);
                nvgFillPaint(vg, dirtPattern);
                nvgFill(vg);

                // Add overlay for consistency
                nvgBeginPath(vg);
                nvgRect(vg, 0, bottomY, width, bottomHeight);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 40, NVGColor.malloc(stack)));
                nvgFill(vg);
            }
        }
    }

    /**
     * Draws a stone background texture extracted from the panel rendering logic.
     * Used for the middle section of the world select screen.
     */
    private void drawStoneBackground(float x, float y, float w, float h) {
        try (MemoryStack stack = stackPush()) {
            // Base stone color fill
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgFillColor(vg, nvgRGBA(95, 95, 95, 255, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Add stone texture pattern - similar to drawMinecraftPanel but adapted for background
            int textureRows = (int)(h / 12) + 1; // Adapt rows based on height
            int textureCols = (int)(w / 12) + 1; // Adapt columns based on width

            for (int i = 0; i < textureRows * textureCols; i++) {
                float px = x + (i % textureCols) * 12;
                float py = y + (i / textureCols) * 12;

                // Skip if outside bounds
                if (px >= x + w || py >= y + h) continue;

                float size = 8; // Slightly smaller for background

                nvgBeginPath(vg);
                nvgRect(vg, px + (i % 3), py + (i % 2), size, size);

                int variation = (i * 17) % 40;
                nvgFillColor(vg, nvgRGBA(75 + variation/2, 75 + variation/2, 75 + variation/2, 80, NVGColor.malloc(stack)));
                nvgFill(vg);
            }

            // Add subtle overall overlay to blend the texture
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 20, NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }

    // ===== ENHANCED THREE-SECTION BACKGROUND RENDERING =====
    // Added for SOLID-compliant WorldSelectScreen architecture

    /**
     * Draws an enhanced three-section background with configurable section heights.
     * Integrates with the new SOLID-based world select screen architecture.
     *
     * @param width screen width
     * @param height screen height
     * @param topHeightPercent height percentage for top section (0.0-1.0)
     * @param middleHeightPercent height percentage for middle section (0.0-1.0)
     * @param bottomHeightPercent height percentage for bottom section (0.0-1.0)
     */
    public void drawEnhancedTriSectionBackground(int width, int height,
                                               float topHeightPercent, float middleHeightPercent, float bottomHeightPercent) {
        // Validate percentages
        if (Math.abs((topHeightPercent + middleHeightPercent + bottomHeightPercent) - 1.0f) > 0.001f) {
            System.err.println("Warning: Section height percentages don't sum to 1.0, using defaults");
            drawTriSectionBackground(width, height);
            return;
        }

        // Calculate section heights
        float topHeight = height * topHeightPercent;
        float middleHeight = height * middleHeightPercent;
        float bottomHeight = height * bottomHeightPercent;

        float middleY = topHeight;
        float bottomY = topHeight + middleHeight;

        // Draw top section with enhanced styling
        drawEnhancedTopSection(0, 0, width, topHeight);

        // Draw middle section with enhanced styling
        drawEnhancedMiddleSection(0, middleY, width, middleHeight);

        // Draw bottom section with enhanced styling
        drawEnhancedBottomSection(0, bottomY, width, bottomHeight);

        // Add section separators for better visual distinction
        drawSectionSeparators(width, topHeight, middleY, bottomY);
    }

    /**
     * Draws the enhanced top section (navigation area).
     */
    private void drawEnhancedTopSection(float x, float y, float width, float height) {
        if (dirtTextureImage != -1) {
            try (MemoryStack stack = stackPush()) {
                // Draw dirt texture
                NVGPaint dirtPattern = NVGPaint.malloc(stack);
                nvgImagePattern(vg, x, y, 96, 96, 0, dirtTextureImage, 1.0f, dirtPattern);

                nvgBeginPath(vg);
                nvgRect(vg, x, y, width, height);
                nvgFillPaint(vg, dirtPattern);
                nvgFill(vg);

                // Add darker overlay for navigation area distinction
                nvgBeginPath(vg);
                nvgRect(vg, x, y, width, height);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 50, NVGColor.malloc(stack)));
                nvgFill(vg);

                // Add subtle bottom gradient to blend into middle section
                NVGPaint gradientPaint = NVGPaint.malloc(stack);
                nvgLinearGradient(vg, x, y + height - 15f, x, y + height,
                    nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)),
                    nvgRGBA(0, 0, 0, 30, NVGColor.malloc(stack)), gradientPaint);

                nvgBeginPath(vg);
                nvgRect(vg, x, y + height - 15f, width, 15f);
                nvgFillPaint(vg, gradientPaint);
                nvgFill(vg);
            }
        }
    }

    /**
     * Draws the enhanced middle section (world list area) with optimized styling for content display.
     */
    private void drawEnhancedMiddleSection(float x, float y, float width, float height) {
        try (MemoryStack stack = stackPush()) {
            // Draw stone background with lighter overlay for better content visibility
            drawStoneBackground(x, y, width, height);

            // Add content-optimized overlay - lighter than default for better readability
            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, height);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 15, NVGColor.malloc(stack))); // Lighter than default 20
            nvgFill(vg);

            // Add subtle top gradient from top section
            NVGPaint topGradient = NVGPaint.malloc(stack);
            nvgLinearGradient(vg, x, y, x, y + 20,
                nvgRGBA(0, 0, 0, 40, NVGColor.malloc(stack)),
                nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)), topGradient);

            nvgBeginPath(vg);
            nvgRect(vg, x, y, width, 20);
            nvgFillPaint(vg, topGradient);
            nvgFill(vg);

            // Add subtle bottom gradient to bottom section
            NVGPaint bottomGradient = NVGPaint.malloc(stack);
            nvgLinearGradient(vg, x, y + height - 20, x, y + height,
                nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)),
                nvgRGBA(0, 0, 0, 25, NVGColor.malloc(stack)), bottomGradient);

            nvgBeginPath(vg);
            nvgRect(vg, x, y + height - 20, width, 20);
            nvgFillPaint(vg, bottomGradient);
            nvgFill(vg);
        }
    }

    /**
     * Draws the enhanced bottom section (action area).
     */
    private void drawEnhancedBottomSection(float x, float y, float width, float height) {
        if (dirtTextureImage != -1) {
            try (MemoryStack stack = stackPush()) {
                // Draw dirt texture
                NVGPaint dirtPattern = NVGPaint.malloc(stack);
                nvgImagePattern(vg, x, y, 96, 96, 0, dirtTextureImage, 1.0f, dirtPattern);

                nvgBeginPath(vg);
                nvgRect(vg, x, y, width, height);
                nvgFillPaint(vg, dirtPattern);
                nvgFill(vg);

                // Add slightly darker overlay for action area distinction
                nvgBeginPath(vg);
                nvgRect(vg, x, y, width, height);
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 45, NVGColor.malloc(stack)));
                nvgFill(vg);

                // Add subtle top gradient to blend with middle section
                NVGPaint gradientPaint = NVGPaint.malloc(stack);
                nvgLinearGradient(vg, x, y, x, y + 15f,
                    nvgRGBA(0, 0, 0, 30, NVGColor.malloc(stack)),
                    nvgRGBA(0, 0, 0, 0, NVGColor.malloc(stack)), gradientPaint);

                nvgBeginPath(vg);
                nvgRect(vg, x, y, width, 15f);
                nvgFillPaint(vg, gradientPaint);
                nvgFill(vg);
            }
        }
    }

    /**
     * Draws subtle separators between sections for better visual distinction.
     */
    private void drawSectionSeparators(float width, float topHeight, float middleY, float bottomY) {
        try (MemoryStack stack = stackPush()) {
            // Top-Middle separator (subtle line)
            nvgBeginPath(vg);
            nvgRect(vg, 0, topHeight - 1, width, 2);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 60, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Middle-Bottom separator (subtle line)
            nvgBeginPath(vg);
            nvgRect(vg, 0, bottomY - 1, width, 2);
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 60, NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }

    /**
     * Draws individual section backgrounds for the new panel system.
     * Allows rendering specific sections independently.
     *
     * @param sectionType 0=top, 1=middle, 2=bottom
     * @param x section x coordinate
     * @param y section y coordinate
     * @param width section width
     * @param height section height
     */
    public void drawSectionBackground(int sectionType, float x, float y, float width, float height) {
        switch (sectionType) {
            case 0: // Top section
                drawEnhancedTopSection(x, y, width, height);
                break;
            case 1: // Middle section
                drawEnhancedMiddleSection(x, y, width, height);
                break;
            case 2: // Bottom section
                drawEnhancedBottomSection(x, y, width, height);
                break;
            default:
                System.err.println("Warning: Invalid section type " + sectionType + ", using middle section");
                drawEnhancedMiddleSection(x, y, width, height);
                break;
        }
    }

    /**
     * Creates a customized three-section background using section bounds from the layout system.
     * This method integrates directly with the new SOLID architecture.
     */
    public void drawLayoutBasedTriSectionBackground(
            com.stonebreak.ui.worldSelect.SectionBounds topSection,
            com.stonebreak.ui.worldSelect.SectionBounds middleSection,
            com.stonebreak.ui.worldSelect.SectionBounds bottomSection) {

        if (topSection == null || middleSection == null || bottomSection == null) {
            System.err.println("Warning: Section bounds are null, falling back to default background");
            drawTriSectionBackground(800, 600); // Fallback with default dimensions
            return;
        }

        // Draw each section using its specific bounds
        drawEnhancedTopSection(
            topSection.getX(),
            topSection.getY(),
            topSection.getWidth(),
            topSection.getHeight()
        );

        drawEnhancedMiddleSection(
            middleSection.getX(),
            middleSection.getY(),
            middleSection.getWidth(),
            middleSection.getHeight()
        );

        drawEnhancedBottomSection(
            bottomSection.getX(),
            bottomSection.getY(),
            bottomSection.getWidth(),
            bottomSection.getHeight()
        );

        // Draw separators at the boundaries
        float width = Math.max(topSection.getWidth(), Math.max(middleSection.getWidth(), bottomSection.getWidth()));
        drawSectionSeparators(width, topSection.getBottom(), middleSection.getY(), bottomSection.getY());
    }
}