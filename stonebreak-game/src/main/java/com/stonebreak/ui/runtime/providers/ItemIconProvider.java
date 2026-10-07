package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.rendering.GlStateSnapshot;
import com.openmason.engine.ui.rendering.GlTextureImages;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import static org.lwjgl.opengl.GL11.GL_COLOR_CLEAR_VALUE;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glGetFloatv;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY;
import static org.lwjgl.opengl.GL11.glBindTexture;

/**
 * {@code stonebreak:item-icon}: the icon and stack count of one item inside a document element,
 * the replacement for the legacy Skija → GL → Skija slot phases (ledger Hard visuals #2, #3).
 *
 * <ul>
 *   <li><b>Blocks</b> render in {@link #prepare} into the shared {@link ItemIconAtlas} (once per
 *       block and size, by the legacy cube renderer) and paint from it in {@link #draw}, so they
 *       follow the document's transforms, clips, opacity and paint order.</li>
 *   <li><b>SBO items</b> paint their composited sprite from {@link MTextureRegistry} (the hotbar's
 *       path); items without a sprite get the legacy purple swatch.</li>
 *   <li>The <b>count</b> (when above 1) is drawn bottom-right in the meta font with the legacy
 *       accent colour and shadow; an optional <b>durability</b> fraction below 1 draws a bar.</li>
 * </ul>
 *
 * In an {@code ItemSlot} the icon is inset by 3 logical pixels (the legacy slot inset) and the
 * count anchors to the slot; in a bare {@code DrawProvider} (a carried item, a recipe result) the
 * icon fills the element. What to show comes from {@link ItemRef#of}.
 *
 * <p>Optional {@code params}: {@code insetPx} and {@code countMarginPx} replace the inset and the
 * count margin with fixed device pixels. The container screens (furnace, inventory, workbench)
 * never scaled them: 3 px and 2 px at every UI scale (#298).
 */
public final class ItemIconProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:item-icon";
    public static final int VERSION = 1;

    /** Legacy slot inset and count margin, in logical pixels. */
    static final float SLOT_INSET = 3f;
    static final float COUNT_MARGIN = 2f;
    private static final int SWATCH = 0xFF8033CC; // legacy "unknown item" (0.5, 0.2, 0.8)
    private static final int DURABILITY_BACK = 0xFF000000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemIconProvider.class);

    private final ItemIconAtlas atlas;
    private final Supplier<Typeface> typeface;
    private final Paint paint = new Paint();
    private Font font;
    private float fontSize;
    private boolean prepareFailed;

    public ItemIconProvider(ItemIconAtlas atlas, Supplier<Typeface> typeface) {
        this.atlas = atlas;
        this.typeface = typeface;
    }

    /** Device-pixel square the icon occupies inside {@code rect}, snapped to whole pixels. */
    static Rect iconRect(String elementType, UiRect rect, float scale) {
        return iconRect(elementType, rect, scale, Float.NaN);
    }

    /** @param insetPx fixed device-pixel inset, or NaN for the element type's default */
    static Rect iconRect(String elementType, UiRect rect, float scale, float insetPx) {
        float inset = !Float.isNaN(insetPx) ? insetPx
            : "ItemSlot".equals(elementType) ? Math.round(SLOT_INSET * scale) : 0f;
        float x = Math.round(rect.x() + inset);
        float y = Math.round(rect.y() + inset);
        float side = Math.round(Math.min(rect.width(), rect.height()) - 2f * inset);
        if (side <= 0f) {
            return null;
        }
        // centre a square in a non-square element
        x += Math.round((Math.round(rect.width() - 2f * inset) - side) / 2f);
        y += Math.round((Math.round(rect.height() - 2f * inset) - side) / 2f);
        return Rect.makeXYWH(x, y, side, side);
    }

    @Override
    public void prepare(UiElement element, UiRect rect, float scale) {
        if (prepareFailed) {
            return;
        }
        ItemRef ref = ItemRef.of(element);
        if (ref == null || !(ref.item() instanceof BlockType block) || !block.hasIcon()) {
            return;
        }
        Rect icon = iconRect(element.type(), rect, scale, pixels(element, "insetPx"));
        if (icon == null) {
            return;
        }
        int size = (int) icon.getWidth();
        if (atlas.find(block, size) != null) {
            return; // cached: no GL work, no state capture
        }
        GlStateSnapshot saved = GlStateSnapshot.capture();
        float[] clear = new float[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE1);
        int array1 = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        try {
            atlas.ensure(block, size);
        } catch (RuntimeException e) {
            prepareFailed = true; // one log, then documents show swatches instead of spamming
            LOGGER.error("[ui] item icon rendering failed; block icons disabled for this session", e);
        } finally {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D_ARRAY, array1);
            glActiveTexture(active);
            glClearColor(clear[0], clear[1], clear[2], clear[3]);
            saved.restore();
        }
    }

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        ItemRef ref = ItemRef.of(element);
        if (ref == null || !ref.item().hasIcon()) {
            return;
        }
        Rect icon = iconRect(element.type(), rect, scale, pixels(element, "insetPx"));
        if (icon != null) {
            drawIcon(canvas, ref, icon);
        }
        boolean slot = "ItemSlot".equals(element.type());
        Rect anchor = slot ? Rect.makeXYWH(rect.x(), rect.y(), rect.width(), rect.height()) : icon;
        if (anchor != null && !Double.isNaN(ref.durability()) && ref.durability() < 1.0) {
            drawDurability(canvas, anchor, (float) Math.max(0.0, ref.durability()), scale);
        }
        if (anchor != null && ref.count() > 1) {
            float margin = pixels(element, "countMarginPx");
            drawCount(canvas, Integer.toString(ref.count()), anchor, scale,
                Float.isNaN(margin) ? COUNT_MARGIN * scale : margin);
        }
    }

    /** A device-pixel override from the element's {@code params}, or NaN. */
    private static float pixels(UiElement element, String name) {
        return element.prop("params") instanceof UiValue.Obj p && p.get(name) instanceof UiValue.Num n
            ? (float) n.value() : Float.NaN;
    }

    private void drawIcon(Canvas canvas, ItemRef ref, Rect icon) {
        Item item = ref.item();
        if (item instanceof BlockType block) {
            ItemIconAtlas.Icon cell = prepareFailed ? null : atlas.find(block, (int) icon.getWidth());
            Image page = cell == null ? null
                : GlTextureImages.borrow(canvas, cell.texture(), cell.pageSize(), cell.pageSize(), true);
            if (page == null) {
                return; // not prepared (no GL host, e.g. a raster preview): leave the slot empty
            }
            // the image is upright (bottom-left origin wrapped); the cell's GL y flips once
            float srcY = cell.pageSize() - cell.y() - cell.size();
            Rect src = Rect.makeXYWH(cell.x(), srcY, cell.size(), cell.size());
            SamplingMode sampling = cell.size() == (int) icon.getWidth() ? SamplingMode.DEFAULT : SamplingMode.LINEAR;
            paint.reset();
            canvas.drawImageRect(page, src, icon, sampling, paint, true);
            return;
        }
        if (item instanceof ItemType type && SpriteVoxelizer.isSboBackedItem(type)) {
            MTexture tex = MTextureRegistry.getForSboItem(type, ref.state());
            if (tex != null && tex.image() != null) {
                MPainter.drawImage(canvas, tex.image(), icon.getLeft(), icon.getTop(), icon.getWidth(), icon.getHeight());
            }
            return;
        }
        paint.reset();
        paint.setColor(SWATCH);
        canvas.drawRect(icon, paint);
    }

    private void drawCount(Canvas canvas, String text, Rect anchor, float scale, float margin) {
        Font f = font(MStyle.FONT_META * scale);
        if (f == null) {
            return;
        }
        float x = anchor.getRight() - MPainter.measureWidth(f, text) - margin;
        float y = anchor.getBottom() - margin;
        MPainter.drawStringWithShadow(canvas, text, x, y, f, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    /** Two-pixel bar along the bottom of the icon, green to red as durability drops. */
    private void drawDurability(Canvas canvas, Rect anchor, float fraction, float scale) {
        float h = Math.max(1f, Math.round(2f * scale));
        float inset = Math.round(SLOT_INSET * scale);
        float w = anchor.getWidth() - 2f * inset;
        float x = anchor.getLeft() + inset;
        float y = anchor.getBottom() - inset - h;
        paint.reset();
        paint.setColor(DURABILITY_BACK);
        canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint);
        int red = Math.round(255 * (1f - fraction));
        int green = Math.round(255 * fraction);
        paint.setColor(0xFF000000 | red << 16 | green << 8);
        canvas.drawRect(Rect.makeXYWH(x, y, Math.round(w * fraction), h), paint);
    }

    private Font font(float size) {
        if (font != null && fontSize == size) {
            return font;
        }
        Typeface face = typeface == null ? null : typeface.get();
        if (face == null) {
            return null;
        }
        if (font != null) {
            font.close();
        }
        font = new Font(face, size);
        fontSize = size;
        return font;
    }
}
