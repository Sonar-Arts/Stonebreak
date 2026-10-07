package com.stonebreak.rendering.UI.menus;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.masonry.MPainter;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

import java.util.HashMap;
import java.util.Map;

/**
 * Skija-based renderer for item icons in the UI layer.
 *
 * Draws block top-face icons and SBO-backed item sprites using Skija
 * {@link Canvas} calls. Replaces the old NanoVG version that relied on
 * {@code nvgImagePattern} for UV mapping.
 *
 * Each public render method opens its own Skija begin/end frame so the
 * renderer can be called from any context (old NanoVG hotbar, new Skija
 * menus, overlay tooltips) without requiring a pre-existing Skija frame.
 */
public class ItemIconRenderer {

    private final SkijaUIBackend skijaBackend;
    /** Cached Skija images for block-texture-array layers, keyed by layer index. */
    private final Map<Integer, Image> blockLayerImageCache = new HashMap<>();

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

    public void renderItemIcon(float x, float y, float w, float h, Item item, BlockTextureArray blockTextureArray) {
        renderItemIcon(x, y, w, h, item, null, blockTextureArray);
    }

    /**
     * State-aware variant — renders an SBO item with a specific state's
     * texture (1.3+). Pass {@code null} for the default state. Block items
     * ignore the state parameter.
     */
    public void renderItemIcon(float x, float y, float w, float h, Item item, String state, BlockTextureArray blockTextureArray) {
        if (item == null || item == BlockType.AIR) return;

        // SBO-backed items render from their composited sprite.
        if (item instanceof ItemType itemType && SpriteVoxelizer.isSboBackedItem(itemType)) {
            renderSboItemIcon(x, y, w, h, itemType, state);
            return;
        }

        // Block items draw the block's top-face texture from the block array.
        if (item instanceof BlockType blockType && blockTextureArray != null) {
            int layer = blockTextureArray.getBlockFaceLayer(blockType, BlockType.Face.TOP.getIndex());
            Image img = getBlockLayerImage(blockTextureArray, layer);
            if (img == null) {
                renderQuad(x, y, w, h, 0.2f, 0.8f, 0.2f, 1f);
                return;
            }
            int windowWidth = com.stonebreak.core.Game.getWindowWidth();
            int windowHeight = com.stonebreak.core.Game.getWindowHeight();
            try (CanvasInFrame cif = acquireCanvas(windowWidth, windowHeight)) {
                MPainter.drawImage(cif.canvas, img, x, y, w, h);
            }
            return;
        }

        // Unknown / textureless item — error swatch.
        renderQuad(x, y, w, h, 0.5f, 0.2f, 0.8f, 1f);
    }

    /**
     * Builds (and caches) a Skija image for one block-texture-array layer.
     */
    private Image getBlockLayerImage(BlockTextureArray array, int layer) {
        Image cached = blockLayerImageCache.get(layer);
        if (cached != null) return cached;

        int[] argb = array.getLayerPixels(layer);
        if (argb == null) return null;

        final int size = BlockTextureArray.TILE;
        byte[] bytes = new byte[size * size * 4];
        for (int i = 0, off = 0; i < argb.length; i++, off += 4) {
            int px = argb[i];
            bytes[off]     = (byte) (px         & 0xFF); // B
            bytes[off + 1] = (byte) ((px >> 8)  & 0xFF); // G
            bytes[off + 2] = (byte) ((px >> 16) & 0xFF); // R
            bytes[off + 3] = (byte) ((px >> 24) & 0xFF); // A
        }
        ImageInfo info = ImageInfo.makeN32(size, size, ColorAlphaType.UNPREMUL);
        Image img = Image.makeRasterFromBytes(info, bytes, size * 4);
        blockLayerImageCache.put(layer, img);
        return img;
    }

    // ===== SBO item rendering =====

    private void renderSboItemIcon(float x, float y, float w, float h, ItemType itemType, String state) {
        Image img = sboItemImage(itemType, state);
        if (img == null) return;

        int windowWidth = com.stonebreak.core.Game.getWindowWidth();
        int windowHeight = com.stonebreak.core.Game.getWindowHeight();

        try (CanvasInFrame cif = acquireCanvas(windowWidth, windowHeight)) {
            MPainter.drawImage(cif.canvas, img, x, y, w, h);
        }
    }

    /**
     * The sprite every item slot draws for an SBO item (#330): the shared
     * {@link MTextureRegistry} image, with straight alpha, as the hotbar and the
     * {@code stonebreak:item-icon} document provider draw it. This renderer used to
     * build its own copy flagged {@code OPAQUE}, which filled the transparent pixels
     * with black in the inventory, workbench, furnace and recipe book.
     */
    static Image sboItemImage(ItemType itemType, String state) {
        MTexture tex = MTextureRegistry.getForSboItem(itemType, state);
        return tex == null ? null : tex.image();
    }

    /**
     * Renders an item icon by ID (block type or item type lookup).
     */
    public void renderItemIcon(float x, float y, float w, float h, int blockTypeId, BlockTextureArray blockTextureArray) {
        BlockType blockType = BlockType.getById(blockTypeId);
        if (blockType != null) {
            renderItemIcon(x, y, w, h, blockType, blockTextureArray);
            return;
        }

        ItemType itemType = ItemType.getById(blockTypeId);
        if (itemType != null) {
            renderItemIcon(x, y, w, h, itemType, blockTextureArray);
            return;
        }

        renderQuad(x, y, w, h, 0.5f, 0.2f, 0.8f, 1f);
    }
}
