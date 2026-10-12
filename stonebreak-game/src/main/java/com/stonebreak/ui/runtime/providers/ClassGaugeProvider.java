package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.UI.components.hotbar.ClassGauge;
import com.stonebreak.rendering.UI.components.hotbar.DoubtGauge;
import com.stonebreak.rendering.UI.components.hotbar.GaugePanel;
import com.stonebreak.rendering.UI.components.hotbar.MomentumGauge;
import com.stonebreak.rendering.UI.components.hotbar.QuarryGauge;
import com.stonebreak.rendering.UI.components.hotbar.RageGauge;
import com.stonebreak.rendering.UI.components.hotbar.ResonanceGauge;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@value #ID}: the selected class's resource gauge beside the hotbar (#300, ledger row
 * {@code hud-class-gauge}), drawn by the same {@link ClassGauge} painters as the legacy HUD so the two
 * cannot drift: Rage (Berserker), Quarry (Ranger), Resonance (Arcanist), Doubt (Illusionist), Momentum
 * (Rogue). Each gauge is a column of header, bars, pips and status lines over a dozen ability
 * controllers' live state and ability icons; it stays a provider rather than authored rows until those
 * controllers publish a record of their own.
 *
 * <p>The gauge starts at the element's top-left and is {@value ClassGauge#PANEL_WIDTH} px wide (device
 * px, unscaled, as the legacy HUD drew it) in the house meta font at the UI scale. {@code params}:
 * {@code classId} (bind it to {@code hud.classId}): only that class's gauge draws. The player is the
 * game's; without one (the editor preview) nothing draws. Pure Skia: nothing to prepare.
 */
public final class ClassGaugeProvider implements UiPaintHost.UiDrawProvider, AutoCloseable {

    public static final String ID = "stonebreak:class-gauge";
    public static final int VERSION = 1;

    private static final List<ClassGauge> GAUGES = List.of(new RageGauge(), new QuarryGauge(), new ResonanceGauge(),
        new DoubtGauge(), new MomentumGauge());

    private final Supplier<Player> player;
    private final Supplier<Typeface> typeface;
    private Font font;
    private float fontSize;

    public ClassGaugeProvider(Supplier<Player> player, Supplier<Typeface> typeface) {
        this.player = player;
        this.typeface = typeface;
    }

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        Player p = player.get();
        String classId = element.prop("params") instanceof UiValue.Obj o && o.get("classId") instanceof UiValue.Str s
            ? s.value() : "";
        if (p == null || classId.isEmpty()) {
            return;
        }
        Font f = font(MStyle.FONT_META * scale);
        if (f == null) {
            return;
        }
        for (ClassGauge gauge : GAUGES) {
            if (gauge.classId().equals(classId)) {
                gauge.draw(new GaugePanel(canvas, f, rect.x(), rect.y(), ClassGauge.PANEL_WIDTH), p);
            }
        }
    }

    /** MFonts' half-pixel grid, so the gauge text matches the legacy HUD's {@code getScaled} font. */
    private Font font(float size) {
        size = Math.round(Math.max(6f, size) * 2f) / 2f;
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

    @Override
    public void close() {
        if (font != null) {
            font.close();
            font = null;
        }
    }
}
