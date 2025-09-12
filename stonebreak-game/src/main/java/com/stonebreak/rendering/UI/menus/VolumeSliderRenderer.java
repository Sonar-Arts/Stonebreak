package com.stonebreak.rendering.UI.menus;

import com.stonebreak.rendering.UI.core.BaseRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class VolumeSliderRenderer extends BaseRenderer {
    
    public VolumeSliderRenderer(long vg) {
        super(vg);
        loadFonts();
    }
    
    public void drawVolumeSlider(String label, float centerX, float centerY, float sliderWidth, float sliderHeight, float value, boolean highlighted) {
        try (MemoryStack stack = stackPush()) {
            float buttonWidth = 400;
            float buttonHeight = 60;
            float buttonX = centerX - buttonWidth / 2;
            float buttonY = centerY - buttonHeight / 2;
            
            float bevelSize = 3.0f;
            
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
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX, buttonY);
            nvgLineTo(vg, buttonX + buttonWidth, buttonY);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + bevelSize);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(180, 180, 180, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX, buttonY);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + bevelSize);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX, buttonY + buttonHeight);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(160, 160, 160, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX, buttonY + buttonHeight);
            nvgLineTo(vg, buttonX + bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX + buttonWidth, buttonY + buttonHeight);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgMoveTo(vg, buttonX + buttonWidth, buttonY);
            nvgLineTo(vg, buttonX + buttonWidth, buttonY + buttonHeight);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + buttonHeight - bevelSize);
            nvgLineTo(vg, buttonX + buttonWidth - bevelSize, buttonY + bevelSize);
            nvgClosePath(vg);
            nvgFillColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgRect(vg, buttonX, buttonY, buttonWidth, buttonHeight);
            nvgStrokeWidth(vg, 2.5f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            String fontName = getFontName();
            nvgFontSize(vg, UI_FONT_SIZE);
            nvgFontFace(vg, fontName);
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
            nvgText(vg, centerX + 1, centerY - 15 + 1, label);
            
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(240, 240, 240, 255, NVGColor.malloc(stack)));
            }
            nvgText(vg, centerX, centerY - 15, label);
            
            float trackX = centerX - sliderWidth / 2;
            float trackY = centerY + 5;
            
            nvgBeginPath(vg);
            nvgRect(vg, trackX, trackY, sliderWidth, sliderHeight);
            nvgFillColor(vg, nvgRGBA(40, 40, 40, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
            
            nvgBeginPath(vg);
            nvgRect(vg, trackX, trackY, sliderWidth, sliderHeight);
            nvgStrokeWidth(vg, 2.0f);
            nvgStrokeColor(vg, nvgRGBA(20, 20, 20, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
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
            
            nvgBeginPath(vg);
            nvgRect(vg, handleX, handleY, handleW, handleH);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, nvgRGBA(60, 60, 60, 255, NVGColor.malloc(stack)));
            nvgStroke(vg);
            
            String volumeText = Math.round(value * 100) + "%";
            nvgFontSize(vg, 12);
            nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_MIDDLE);
            
            nvgFillColor(vg, nvgRGBA(0, 0, 0, 150, NVGColor.malloc(stack)));
            nvgText(vg, buttonX + buttonWidth - 8 + 1, trackY + sliderHeight/2 + 1, volumeText);
            
            if (highlighted) {
                nvgFillColor(vg, nvgRGBA(255, 255, 255, 255, NVGColor.malloc(stack)));
            } else {
                nvgFillColor(vg, nvgRGBA(240, 240, 240, 255, NVGColor.malloc(stack)));
            }
            nvgText(vg, buttonX + buttonWidth - 8, trackY + sliderHeight/2, volumeText);
        }
    }
}