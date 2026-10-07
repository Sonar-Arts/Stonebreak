package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.ui.furnace.core.FurnaceLayout;
import com.stonebreak.ui.furnace.renderers.CruciblePainter;
import io.github.humbleui.skija.Canvas;

/**
 * The furnace crucible inside a document element (#298, ledger hard visual 9), drawn by the same
 * {@link CruciblePainter} as the legacy screen so the two cannot drift. Two providers, because
 * the legacy screen paints the crucible under the slot frames and the rings over them:
 *
 * <ul>
 *   <li>{@value #BOWL_ID}: the chutes to the three slots and the bowl (glow, lava, bubbles,
 *       flames), animated on {@link LegacyUiClock}.</li>
 *   <li>{@value #RINGS_ID}: the cook-progress and fuel rings.</li>
 * </ul>
 *
 * The element is the band the three slots sit in: the crucible is centred on it, and the slot
 * geometry the chutes run to is {@link FurnaceLayout#around} that centre. {@code params} is the
 * {@code stonebreak:furnace} record (bind {@code prop:params} to {@code furnace}): {@code fuel}
 * and {@code progress} (0..1) and {@code cooking}; the bowl glows while cooking or with fuel
 * left, as the legacy screen did. Optional {@code slotSize} and {@code slotGap} (logical px,
 * default 40 and 8) say how far the slots sit from the centre. Pure Skia: no GL, nothing to
 * prepare.
 */
public final class FurnaceCrucibleProvider implements UiPaintHost.UiDrawProvider {

    public static final String BOWL_ID = "stonebreak:furnace-crucible";
    public static final String RINGS_ID = "stonebreak:furnace-progress";
    public static final int VERSION = 1;

    static final float SLOT_SIZE = 40f;
    static final float SLOT_GAP = 8f;

    private final boolean rings;

    private FurnaceCrucibleProvider(boolean rings) {
        this.rings = rings;
    }

    public static FurnaceCrucibleProvider bowl() {
        return new FurnaceCrucibleProvider(false);
    }

    public static FurnaceCrucibleProvider rings() {
        return new FurnaceCrucibleProvider(true);
    }

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        UiValue.Obj p = element.prop("params") instanceof UiValue.Obj o ? o : UiValue.Obj.EMPTY;
        FurnaceLayout.Slots s = geometry(rect, scale, number(p, "slotSize", SLOT_SIZE), number(p, "slotGap", SLOT_GAP));
        float fuel = number(p, "fuel", 0f);
        if (rings) {
            CruciblePainter.paintRings(canvas, s, number(p, "progress", 0f), fuel);
        } else {
            boolean cooking = p.get("cooking") instanceof UiValue.Bool b && b.value();
            CruciblePainter.paintBowl(canvas, s, fuel, cooking || fuel > 0f, (float) LegacyUiClock.seconds());
        }
    }

    /** The radial slot geometry centred on {@code rect}, at the legacy rounding of the slot tokens. */
    static FurnaceLayout.Slots geometry(UiRect rect, float scale, float slotSize, float slotGap) {
        float cx = rect.x() + rect.width() / 2f;
        float cy = rect.y() + rect.height() / 2f;
        return FurnaceLayout.around(cx, cy, Math.round(slotSize * scale), Math.round(slotGap * scale), scale);
    }

    private static float number(UiValue.Obj p, String name, float fallback) {
        // values are published as the float's shortest decimal (SettingsContract.exact): back to the same float
        return p.get(name) instanceof UiValue.Num n ? (float) n.value() : fallback;
    }
}
