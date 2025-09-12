package com.stonebreak.rendering.UI.menus;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.UI.core.BaseRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class ItemIconRenderer extends BaseRenderer {
    
    public ItemIconRenderer(long vg) {
        super(vg);
    }
    
    public void renderQuad(float x, float y, float w, float h, float r, float g, float b, float a) {
        if (vg == 0) return;
        try (MemoryStack stack = stackPush()) {
            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgFillColor(vg, nvgRGBA((int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255), NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }
    
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
    
    public void renderItemIcon(float x, float y, float w, float h, Item item, TextureAtlas textureAtlas) {
        if (vg == 0 || textureAtlas == null || item == null) return;

        if (item == BlockType.AIR) return;

        float[] texCoords;
        if (item instanceof BlockType blockType) {
            texCoords = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.TOP);
        } else if (item instanceof ItemType itemType) {
            texCoords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        } else {
            float atlasSize = 16.0f;
            float uvSize = 1.0f / atlasSize;
            float texX = item.getAtlasX() / atlasSize;
            float texY = item.getAtlasY() / atlasSize;
            
            texCoords = new float[]{
                texX,
                texY,
                texX + uvSize,
                texY + uvSize
            };
        }

        if (texCoords == null || texCoords.length < 4) {
            renderQuad(x, y, w, h, 0.5f, 0.2f, 0.8f, 1f);
            System.err.println("ItemIconRenderer: Invalid texCoords for item " + (item != null ? item.getClass().getSimpleName() : "null"));
            return;
        }
        
        if (texCoords[0] < 0 || texCoords[0] > 1 || texCoords[1] < 0 || texCoords[1] > 1 ||
            texCoords[2] < 0 || texCoords[2] > 1 || texCoords[3] < 0 || texCoords[3] > 1) {
            System.err.println("ItemIconRenderer: Warning - UV coordinates out of range for item " + (item != null ? item.getClass().getSimpleName() : "null") + 
                              ": [" + texCoords[0] + ", " + texCoords[1] + ", " + texCoords[2] + ", " + texCoords[3] + "]");
        }

        int atlasImageId;
        try {
            atlasImageId = textureAtlas.getNanoVGImageId(vg);
        } catch (Exception e) {
            System.err.println("ItemIconRenderer: Failed to get NanoVG image ID from texture atlas: " + e.getMessage());
            renderQuad(x, y, w, h, 0.8f, 0.2f, 0.2f, 1f);
            return;
        }
        if (atlasImageId == -1) {
            System.err.println("ItemIconRenderer: NanoVG image ID is -1, cannot render item icon");
            renderQuad(x, y, w, h, 0.2f, 0.8f, 0.2f, 1f);
            return;
        }

        try (MemoryStack stack = stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            
            float u1 = texCoords[0];
            float v1 = texCoords[1];
            float u2 = texCoords[2];
            float v2 = texCoords[3];
            
            float uv_w = u2 - u1;
            float uv_h = v2 - v1;
            
            float atlasWidth = textureAtlas.getTextureWidth();
            float atlasHeight = textureAtlas.getTextureHeight();
            
            if (atlasWidth <= 0 || atlasHeight <= 0) {
                System.err.println("ItemIconRenderer: Invalid atlas dimensions: " + atlasWidth + "x" + atlasHeight);
                renderQuad(x, y, w, h, 0.8f, 0.8f, 0.2f, 1f);
                return;
            }
            
            if (uv_w <= 0 || uv_h <= 0) {
                System.err.println("ItemIconRenderer: Invalid UV dimensions: " + uv_w + "x" + uv_h + " for item " + 
                                  (item != null ? item.getClass().getSimpleName() : "null"));
                renderQuad(x, y, w, h, 0.2f, 0.2f, 0.8f, 1f);
                return;
            }
            
            float fullAtlasDisplayWidth = w / uv_w;
            float fullAtlasDisplayHeight = h / uv_h;
            
            float patternX = x - (u1 * fullAtlasDisplayWidth);
            float patternY = y - (v1 * fullAtlasDisplayHeight);
            
            nvgImagePattern(vg,
                patternX,
                patternY,
                fullAtlasDisplayWidth,
                fullAtlasDisplayHeight,
                0,
                atlasImageId,
                1.0f,
                paint);

            nvgBeginPath(vg);
            nvgRect(vg, x, y, w, h);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }
    }
    
    public void renderItemIcon(float x, float y, float w, float h, int blockTypeId, TextureAtlas textureAtlas) {
        BlockType blockType = BlockType.getById(blockTypeId);
        if (blockType != null) {
            renderItemIcon(x, y, w, h, blockType, textureAtlas);
            return;
        }
        
        ItemType itemType = ItemType.getById(blockTypeId);
        if (itemType != null) {
            renderItemIcon(x, y, w, h, itemType, textureAtlas);
            return;
        }
        
        renderQuad(x, y, w, h, 0.5f, 0.2f, 0.8f, 1f);
    }
}