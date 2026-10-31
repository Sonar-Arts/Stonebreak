package com.stonebreak.openmason.item;

import com.openmason.api.viewport.ViewportContentFrame;
import com.openmason.api.viewport.ViewportContentProvider;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.main.systems.ViewportController;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Draws the viewport's selected Stonebreak item as its voxelized sprite (#382; was the tool's
 * built-in item content). The tool keeps the selection as a plain id
 * ({@link ViewportController#getSelectedItemId()}, an {@link ItemType} name); an id this game
 * does not know draws nothing. The renderer starts lazily on the GL thread, on the first draw.
 */
public final class StonebreakItemContent implements ViewportContentProvider, AutoCloseable {

    public static final String ID = "stonebreak.item";

    private static final Logger logger = LoggerFactory.getLogger(StonebreakItemContent.class);

    private final Supplier<ViewportController> viewport;
    private ItemRenderer renderer;
    private String unknownId;

    public StonebreakItemContent(Supplier<ViewportController> viewport) {
        this.viewport = viewport;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Stonebreak item";
    }

    /** The selected item, or null when none is selected or its id is not a Stonebreak item. */
    ItemType selected() {
        ViewportController v = viewport.get();
        String id = v == null ? null : v.getSelectedItemId();
        if (id == null) {
            return null;
        }
        try {
            return ItemType.valueOf(id);
        } catch (IllegalArgumentException e) {
            if (!id.equals(unknownId)) {
                unknownId = id;
                logger.warn("Not a Stonebreak item: {}", id);
            }
            return null;
        }
    }

    @Override
    public boolean ready() {
        return selected() != null;
    }

    @Override
    public void render(ViewportContentFrame frame) {
        ItemType item = selected();
        if (item == null) {
            return;
        }
        if (renderer == null) {
            renderer = new ItemRenderer("Viewport");
            renderer.initialize();
        }
        ShaderProgram shader = frame.matrixShader();
        renderer.renderItem(item,
                shader.getProgramId(),
                shader.getUniformLocation("uMVPMatrix"),
                shader.getUniformLocation("uModelMatrix"),
                frame.viewProjection(),
                frame.modelMatrix(),
                shader.getUniformLocation("uUseTexture"));
    }

    @Override
    public void close() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }
}
