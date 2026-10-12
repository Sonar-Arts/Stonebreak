package com.stonebreak.ui.runtime.providers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.Renderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * 3D block icons rendered once per (block, device-pixel size) into offscreen pages, so documents
 * can show them through {@link com.openmason.engine.ui.rendering.GlTextureImages} inside their
 * own paint order (Hard visual #2 of the migration ledger). The cube itself is drawn by the same
 * code as the legacy slots ({@link BlockIconPainter#LEGACY}): same shader, view, lighting and
 * leaf blending, only the target is a page cell instead of the shared default framebuffer — so
 * there is no depth clear in the world's depth buffer and nothing can overdraw a tooltip.
 *
 * <p>Rendering happens in {@link #ensure}, which belongs in a provider's {@code prepare} (outside
 * any Skia frame, wrapped in a {@code GlStateSnapshot}). GL thread only.
 */
public final class ItemIconAtlas implements AutoCloseable {

    /** Page edge in pixels; icons larger than this render at this size and are scaled by Skia. */
    public static final int PAGE_SIZE = 512;

    /** Draws one block icon into the bound framebuffer's square viewport {@code (x, y, size)}. */
    @FunctionalInterface
    public interface BlockIconPainter {
        void paint(BlockType type, int viewportX, int viewportY, int size);

        /**
         * The legacy slot renderer ({@code BlockIconRenderer}) aimed at a viewport rather than a
         * window rect: it converts its top-left window rect to GL with the window height, so the
         * rect is chosen to land exactly on {@code (x, y)} of the bound target.
         */
        BlockIconPainter LEGACY = (type, x, y, size) -> {
            Renderer renderer = Game.getRenderer();
            if (renderer == null || renderer.getUIRenderer() == null) {
                return;
            }
            int windowTop = Game.getWindowHeight() - y - size;
            renderer.getUIRenderer().draw3DItemInSlot(renderer.getShaderProgram(), type, x, windowTop, size, size,
                renderer.getBlockTextureArray());
        };
    }

    /** Where an icon is: the page texture and the cell's rect in it (GL origin bottom-left). */
    public record Icon(int texture, int pageSize, int x, int y, int size) {
    }

    private final BlockIconPainter painter;
    private final IconAtlasLayout<BlockType> layout = new IconAtlasLayout<>(PAGE_SIZE);
    private final Map<Integer, List<GlRenderTarget>> pages = new HashMap<>();

    public ItemIconAtlas(BlockIconPainter painter) {
        this.painter = painter;
    }

    /** The cached icon, or null when it was never rendered at {@code size}. */
    public Icon find(BlockType type, int size) {
        IconAtlasLayout.Cell cell = layout.find(type, clamp(size));
        return cell == null ? null : icon(cell);
    }

    /**
     * The icon of {@code type} at {@code size} device pixels, rendering it first when needed.
     * Changes GL state (framebuffer, viewport, scissor, clear colour, whatever the painter
     * touches): call inside a state snapshot.
     */
    public Icon ensure(BlockType type, int size) {
        int s = clamp(size);
        IconAtlasLayout.Cell existing = layout.find(type, s);
        if (existing != null) {
            return icon(existing);
        }
        IconAtlasLayout.Cell cell = layout.allocate(type, s);
        GlRenderTarget page = page(cell);
        page.bind();
        glViewport(cell.x(), cell.y(), s, s);
        glEnable(GL_SCISSOR_TEST);
        glScissor(cell.x(), cell.y(), s, s);
        glColorMask(true, true, true, true);
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        painter.paint(type, cell.x(), cell.y(), s);
        return icon(cell);
    }

    /** Forgets every icon (block textures changed); pages are reused. */
    public void invalidate() {
        layout.clear();
        for (List<GlRenderTarget> list : pages.values()) {
            list.forEach(GlRenderTarget::close);
        }
        pages.clear();
    }

    /** Icons cached (tests and diagnostics). */
    public int cachedIcons() {
        return layout.cellCount();
    }

    @Override
    public void close() {
        invalidate();
    }

    private GlRenderTarget page(IconAtlasLayout.Cell cell) {
        List<GlRenderTarget> list = pages.computeIfAbsent(cell.size(), s -> new ArrayList<>());
        while (list.size() <= cell.page()) {
            GlRenderTarget t = new GlRenderTarget();
            t.ensure(PAGE_SIZE, PAGE_SIZE);
            glViewport(0, 0, PAGE_SIZE, PAGE_SIZE);
            glScissor(0, 0, PAGE_SIZE, PAGE_SIZE);
            glColorMask(true, true, true, true);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            list.add(t);
        }
        return list.get(cell.page());
    }

    private Icon icon(IconAtlasLayout.Cell cell) {
        GlRenderTarget page = pages.get(cell.size()).get(cell.page());
        return new Icon(page.texture(), PAGE_SIZE, cell.x(), cell.y(), cell.size());
    }

    private static int clamp(int size) {
        return Math.clamp(size, 1, PAGE_SIZE);
    }
}
