package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.core.Game;
import com.stonebreak.ui.focusBattle.BattleCanvasBackend;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Typeface;

import java.util.function.Supplier;

/**
 * {@value #ID}: one piece of the focus battle HUD drawn by its legacy Masonry painters over the bound
 * {@link FocusBattleScreen} (#301): {@code underlays} (screen FX, letterbox), {@code overlays} (target cursor, action
 * banner, timing ring, parry, combo strip, floaters), {@code topmost} (result panel, encounter card) or a window at the
 * element's rect ({@code mode}, {@code enemy}, {@code party}, {@code command} with {@code submenu}). Params: {@code piece},
 * the window {@code w}/{@code h}. The windows and layers keep their own state and animate on C1 in the screen; the
 * document places, stacks and moves them. Pure Skia. Without a game battle (the editor preview) it draws the still
 * {@link com.stonebreak.ui.focusBattle.BattleHudPreview} battle.
 */
public final class BattleHudProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:battle-hud";
    public static final int VERSION = 1;

    private final Supplier<FocusBattleScreen> screen;
    private final BattleCanvasBackend backend;
    private final MasonryUI ui;

    public BattleHudProvider(Supplier<FocusBattleScreen> screen, Supplier<Typeface> typeface) {
        this.screen = screen;
        this.backend = new BattleCanvasBackend(typeface);
        this.ui = new MasonryUI(backend);
    }

    /** The game's bound battle HUD, or null. */
    public static FocusBattleScreen gameScreen() {
        Game game = Game.getInstance();
        FocusBattleScreen s = game == null ? null : game.getFocusBattleScreen();
        return s != null && s.isBound() ? s : null;
    }

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        FocusBattleScreen s = screen.get();
        if (s == null) {
            s = com.stonebreak.ui.focusBattle.BattleHudPreview.screen();
        }
        if (!(element.prop("params") instanceof UiValue.Obj p)
                || !(p.get("piece") instanceof UiValue.Str piece)) {
            return;
        }
        float[] submenu = p.get("submenu") instanceof UiValue.Obj sub
            ? new float[]{num(sub, "x"), num(sub, "y"), num(sub, "w"), num(sub, "h")} : null;
        backend.canvas(canvas);
        try {
            s.paintPiece(piece.value(), ui, canvas, (int) num(p, "w"), (int) num(p, "h"),
                new float[]{rect.x(), rect.y(), rect.width(), rect.height()}, submenu);
        } finally {
            backend.canvas(null);
        }
    }

    private static float num(UiValue.Obj p, String k) {
        return p.get(k) instanceof UiValue.Num n ? (float) n.value() : 0f;
    }
}
