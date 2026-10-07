package com.stonebreak.rendering.UI.menus;

import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.registry.ItemRegistry;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The #330 rule: an SBO item sprite in a slot is drawn with its alpha, from the one shared
 * {@link MTextureRegistry} image, by every path (legacy screens through {@link ItemIconRenderer},
 * the hotbar, the {@code stonebreak:item-icon} document provider). The legacy renderer once built
 * its own {@code OPAQUE} copy, which painted transparent pixels black.
 */
class ItemSlotSpriteAlphaTest {

    private static final int BACKDROP = 0xFFFF00FF;

    @Test
    void everySboItemSpriteLetsTheSlotShowThroughItsTransparentPixels() {
        int sprites = 0;
        int withTransparency = 0;
        List<String> failures = new ArrayList<>();
        ItemType.values(); // its static init re-scans the registry; finish that before iterating
        for (ItemRegistry.ItemEntry e : List.copyOf(ItemRegistry.getInstance().all())) {
            ItemType type = ItemType.getByObjectId(e.objectId());
            if (type == null || !SpriteVoxelizer.isSboBackedItem(type)) continue;

            Image img = ItemIconRenderer.sboItemImage(type, null);
            assertNotNull(img, e.objectId() + " has no slot sprite");
            MTexture shared = MTextureRegistry.getForSboItem(type, null);
            assertSame(shared.image(), img, e.objectId() + ": legacy screens must draw the hotbar's image");
            assertNotEquals(ColorAlphaType.OPAQUE, img.getImageInfo().getColorAlphaType(), e.objectId());
            sprites++;

            int w = img.getWidth();
            int h = img.getHeight();
            Bitmap alone = draw(img, w, h, 0x00000000);
            Bitmap onSlot = draw(img, w, h, BACKDROP);
            boolean any = false;
            scan:
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((alone.getColor(x, y) >>> 24) != 0) continue;
                    any = true;
                    if (onSlot.getColor(x, y) != BACKDROP) {
                        failures.add(e.objectId() + " (" + x + "," + y + ") = "
                            + Integer.toHexString(onSlot.getColor(x, y)));
                        break scan;
                    }
                }
            }
            if (any) withTransparency++;
            alone.close();
            onSlot.close();
        }
        assertTrue(sprites > 0, "no SBO item sprites registered");
        assertTrue(withTransparency > 0, "no shipped sprite has a transparent pixel, so the rule is untested");
        assertEquals(List.of(), failures, "transparent sprite pixels must show the slot behind them");
    }

    private static Bitmap draw(Image img, int w, int h, int background) {
        Bitmap bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(w, h));
        try (Canvas canvas = new Canvas(bitmap)) {
            canvas.clear(background);
            MPainter.drawImage(canvas, img, 0, 0, w, h);
        }
        return bitmap;
    }
}
