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
}