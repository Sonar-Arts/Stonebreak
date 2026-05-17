package com.stonebreak.rendering.UI.menus;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Skija-based renderer for item icons in the UI layer.
 *
 * Draws atlas-sliced 2D item icons and SBO-backed item sprites using Skija
 * {@link Canvas} calls. Replaces the old NanoVG version that relied on
 * {@code nvgImagePattern} for UV mapping.
 *
 * Each public render method opens its own Skija begin/end frame so the
 * renderer can be called from any context (old NanoVG hotbar, new Skija
 * menus, overlay tooltips) without requiring a pre-existing Skija frame.
 */
public class ItemIconRenderer {

    /**
     * Cache key for an SBO item icon: an item type plus optional state name
     * so each state caches its own composited Skija image (e.g. empty vs
     * water bucket icons render independently).
     */
    private record SboIconKey(ItemType itemType, String state) {
        static SboIconKey of(ItemType type, String state) {
            return new SboIconKey(type, (state == null || state.isBlank()) ? null : state);
        }
    }

    private final SkijaUIBackend skijaBackend;
    private final Map<SboIconKey, Image> sboItemImageCache = new HashMap<>();
    private final Map<SboIconKey, Boolean> sboItemLoadFailed = new HashMap<>();

    public ItemIconRenderer(SkijaUIBackend skijaBackend) {
        this.skijaBackend = skijaBackend;
    }

    /**
     * Draw a solid colored rectangle (error fallback / debug).
     */
    public void renderQuad(float x, float y, float w, float h, float r, float g, float b, float a) {
        int windowWidth = com.stonebreak.core.Game.getWindowWidth();
        int windowHeight = com.stonebreak.core.Game.getWindowHeight();
        try (CanvasInFrame cif = acquireCanvas(windowWidth, windowHeight)) {
            int color = ((int)(a * 255) << 24) | (((int)(r * 255)) << 16) | (((int)(g * 255)) << 8) | ((int)(b * 255));
            MPainter.fillRect(cif.canvas, x, y, w, h, color);
        }
    }

    /**
     * Draw a rectangular outline (border).
     */
    public void renderOutline(float x, float y, float w, float h, float strokeWidth, float[] color) {
        if (color == null || color.length < 4) return;
        int windowWidth = com.stonebreak.core.Game.getWindowWidth();
        int windowHeight = com.stonebreak.core.Game.getWindowHeight();
        try (CanvasInFrame cif = acquireCanvas(windowWidth, windowHeight)) {
            int c = ((int)(color[3] * 255) << 24) | (((int)(color[0] * 255)) << 16) | (((int)(color[1] * 255)) << 8) | ((int)(color[2] * 255));
            MPainter.strokeRect(cif.canvas, x, y, w, h, c, strokeWidth);
        }
    }

    /**
     * Acquire a canvas for drawing, opening a new Skija frame if none is
     * active. Returns the canvas; the caller does NOT need to close the
     * frame — we use try-with-resource style in each public method.
     */
    private CanvasInFrame acquireCanvas(int width, int height) {
        skijaBackend.beginFrame(width, height, 1.0f);
        return new CanvasInFrame(skijaBackend.getCanvas());
    }

    /**
     * Lightweight wrapper so begin/end frame can be paired via try-with-resources.
     */
    private class CanvasInFrame implements AutoCloseable {
        final Canvas canvas;
        CanvasInFrame(Canvas c) { this.canvas = c; }
        @Override public void close() { skijaBackend.endFrame(); }
    }

    // ===== Main entry points =====

    public void renderItemIcon(float x, float y, float w, float h, Item item, TextureAtlas textureAtlas) {
        renderItemIcon(x, y, w, h, item, null, textureAtlas);
    }

    /**
     * State-aware variant — renders an SBO item with a specific state's
     * texture (1.3+). Pass {@code null} for the default state. Block items
     * ignore the state parameter.
     */
    public void renderItemIcon(float x, float y, float w, float h, Item item, String state, TextureAtlas textureAtlas) {
        if (textureAtlas == null || item == null) return;
        if (item == BlockType.AIR) return;

        float[] texCoords;
        if (item instanceof BlockType blockType) {
            texCoords = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.TOP);
        } else if (item instanceof ItemType itemType) {
            if (SpriteVoxelizer.isSboBackedItem(itemType)) {
                renderSboItemIcon(x, y, w, h, itemType, state);
                return;
            }
            texCoords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        } else {
            float atlasSize = 16.0f;
            float uvSize = 1.0f / atlasSize;
            float texX = item.getAtlasX() / atlasSize;
            float texY = item.getAtlasY() / atlasSize;
            texCoords = new float[]{texX, texY, texX + uvSize, texY + uvSize};
        }

        // Validate texture coordinates
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

        // Get Skija atlas image
        Image atlasImage = textureAtlas.getSkijaImage();
        if (atlasImage == null) {
            System.err.println("ItemIconRenderer: Skija atlas image is null, cannot render item icon");
            renderQuad(x, y, w, h, 0.2f, 0.8f, 0.2f, 1f);
            return;
        }

        int atlasPixelWidth = atlasImage.getWidth();
        int atlasPixelHeight = atlasImage.getHeight();

        if (atlasPixelWidth <= 0 || atlasPixelHeight <= 0) {
            System.err.println("ItemIconRenderer: Invalid atlas dimensions: " + atlasPixelWidth + "x" + atlasPixelHeight);
            renderQuad(x, y, w, h, 0.8f, 0.8f, 0.2f, 1f);
            return;
        }

        float u1 = texCoords[0];
        float v1 = texCoords[1];
        float u2 = texCoords[2];
        float v2 = texCoords[3];

        float uv_w = u2 - u1;
        float uv_h = v2 - v1;

        if (uv_w <= 0 || uv_h <= 0) {
            System.err.println("ItemIconRenderer: Invalid UV dimensions: " + uv_w + "x" + uv_h + " for item " +
                              (item != null ? item.getClass().getSimpleName() : "null"));
            renderQuad(x, y, w, h, 0.2f, 0.2f, 0.8f, 1f);
            return;
        }

        // Convert normalized UV to pixel-space source rectangle
        float srcX = u1 * atlasPixelWidth;
        float srcY = v1 * atlasPixelHeight;
        float srcW = uv_w * atlasPixelWidth;
        float srcH = uv_h * atlasPixelHeight;

        // Determine window dimensions from Game class
        int windowWidth = com.stonebreak.core.Game.getWindowWidth();
        int windowHeight = com.stonebreak.core.Game.getWindowHeight();

        try (CanvasInFrame cif = acquireCanvas(windowWidth, windowHeight)) {
            drawAtlasSubRect(cif.canvas, atlasImage, srcX, srcY, srcW, srcH, x, y, w, h);
        }
    }

    /**
     * Draw a sub-region of the atlas image into the destination rectangle.
     */
    private void drawAtlasSubRect(Canvas canvas, Image atlasImage,
                                  float srcX, float srcY, float srcW, float srcH,
                                  float dstX, float dstY, float dstW, float dstH) {
        try (Paint paint = new Paint()) {
            Rect src = Rect.makeXYWH(srcX, srcY, srcW, srcH);
            Rect dst = Rect.makeXYWH(dstX, dstY, dstW, dstH);
            canvas.drawImageRect(atlasImage, src, dst, SamplingMode.LINEAR, paint, true);
        }
    }

    // ===== SBO item rendering =====

    private void renderSboItemIcon(float x, float y, float w, float h, ItemType itemType, String state) {
        Image img = getSboItemImage(itemType, state);
        if (img == null) return;

        int windowWidth = com.stonebreak.core.Game.getWindowWidth();
        int windowHeight = com.stonebreak.core.Game.getWindowHeight();

        try (CanvasInFrame cif = acquireCanvas(windowWidth, windowHeight)) {
            MPainter.drawImage(cif.canvas, img, x, y, w, h);
        }
    }

    /**
     * Loads or retrieves a cached Skija {@link Image} for an SBO-backed item.
     * Converts the BufferedImage from SpriteVoxelizer into an RGBA raster.
     */
    private Image getSboItemImage(ItemType itemType, String state) {
        SboIconKey key = SboIconKey.of(itemType, state);
        if (sboItemLoadFailed.containsKey(key)) return null;

        Image cached = sboItemImageCache.get(key);
        if (cached != null) return cached;

        BufferedImage img = SpriteVoxelizer.loadSpriteFromSboItem(itemType, state);
        if (img == null) {
            sboItemLoadFailed.put(key, Boolean.TRUE);
            return null;
        }

        try {
            int imgW = img.getWidth();
            int imgH = img.getHeight();
            int[] argb = new int[imgW * imgH];
            img.getRGB(0, 0, imgW, imgH, argb, 0, imgW);

            byte[] bytes = new byte[imgW * imgH * 4];
            for (int i = 0, off = 0; i < argb.length; i++, off += 4) {
                int px = argb[i];
                bytes[off]     = (byte) (px         & 0xFF); // B (N32)
                bytes[off + 1] = (byte) ((px >> 8)  & 0xFF); // G
                bytes[off + 2] = (byte) ((px >> 16) & 0xFF); // R (N32)
                bytes[off + 3] = (byte) ((px >> 24) & 0xFF); // A
            }

            ImageInfo info = ImageInfo.makeN32(imgW, imgH, ColorAlphaType.OPAQUE);
            Image skijaImg = Image.makeRasterFromBytes(info, bytes, imgW * 4);
            sboItemImageCache.put(key, skijaImg);
            return skijaImg;
        } catch (Exception e) {
            System.err.println("ItemIconRenderer: Failed to create Skija image for SBO item " + itemType + ": " + e.getMessage());
            sboItemLoadFailed.put(key, Boolean.TRUE);
            return null;
        }
    }

    /**
     * Renders an item icon by ID (block type or item type lookup).
     */
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
