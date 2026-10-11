package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MColor;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.focusBattle.BattleHelpText;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;
import io.github.humbleui.skija.Font;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The focus battle HUD ({@code stonebreak:battle} 1, #301). {@link FocusBattleScreen} stays the controller: the
 * keyboard in its strict precedence, the menu cursor, every window's own animation on C1 and every gameplay rule.
 * Root {@code battleHud}: the window size, and for each piece the document stacks the rect it is drawn at this frame
 * (FocusBattleLayout's, moved by the HudAnimator's slides and shakes as the legacy renderer moved it), the command
 * window's submenu rect and whether it shows (no outcome yet), and the help strip (its text, colour after the
 * cross-fade, the font size that fits, baseline and inset). The windows and the layer groups are drawn by the
 * {@code stonebreak:battle-hud} provider; the document's own {@code battle} (C1) and {@code battle.sim} (C2) clocks are
 * set by its presentation before each paint.
 *
 * <p>Action {@code .pointer {down, x, y, button}}: the document's press or move in window pixels, run through the
 * screen's legacy mouse rules (a failure there ends the battle, as before).
 */
public final class BattleHudContracts {

    public static final HostContract BATTLE = HostContract.of("stonebreak:battle", 1);

    /** The game behind the HUD. */
    public interface Services {
        /** The battle HUD while bound and showing, or null. */
        default FocusBattleScreen focusBattle() {
            return null;
        }

        /** The window in device px ({@code {w, h}}); null: unknown. */
        default int[] screenSize() {
            return null;
        }

        /** The game's UI typeface, for fitting the help line; null: none loaded. */
        default io.github.humbleui.skija.Typeface uiTypeface() {
            return null;
        }
    }

    private final Services services;
    private final DataCell state;
    /** Measures the help line with the game font (the screen's own Masonry may have no backend). */
    private final com.openmason.engine.ui.masonry.MasonryUI fonts;
    private UiValue last;

    public BattleHudContracts(UiHost ui, Services services) {
        this.services = services;
        this.fonts = new com.openmason.engine.ui.masonry.MasonryUI(
            new com.stonebreak.ui.focusBattle.BattleCanvasBackend(services::uiTypeface));
        state = ui.data().register("battleHud", new DataCell(DataType.ANY, model(null, 1920, 1080, null)), BATTLE);
        ui.actions().register(ActionSpec.of(BATTLE.id() + ".pointer", BATTLE, DataType.object("down", DataType.bool(),
            "x", DataType.number(), "y", DataType.number(), "button", DataType.number()), DataType.ANY), (args, ctx) -> {
            FocusBattleScreen screen = services.focusBattle();
            if (screen == null || !(args instanceof UiValue.Obj a)) {
                return CompletableFuture.failedFuture(new IllegalStateException("no battle is showing"));
            }
            int[] size = size();
            boolean down = a.get("down") instanceof UiValue.Bool b && b.value();
            float x = (float) num(a, "x");
            float y = (float) num(a, "y");
            int button = (int) num(a, "button");
            com.stonebreak.battle.stage.FocusBattle.guardHud(down ? "hud click" : "hud mouse move",
                () -> screen.documentPointer(down, x, y, size[0], size[1], button));
            poll();
            return CompletableFuture.completedFuture(UiValue.NULL);
        });
    }

    private int[] size() {
        int[] s = services.screenSize();
        return s == null ? new int[]{1920, 1080} : s;
    }

    /** UI thread, once per frame and right before the HUD document paints. */
    public void poll() {
        int[] s = size();
        UiValue v = model(services.focusBattle(), s[0], s[1], fonts);
        if (!v.equals(last)) {
            last = v;
            state.set(v);
        }
    }

    /** As SkijaFocusBattleRenderer.paintHud places its windows. */
    static UiValue model(FocusBattleScreen screen, int w, int h, com.openmason.engine.ui.masonry.MasonryUI fonts) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        BattleView view = screen == null ? null : screen.view();
        m.put("bound", UiValue.of(view != null));
        m.put("window", obj("w", w, "h", h));
        float uiScale = Settings.getInstance().getUiScale();
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        BattleHudAnimState a = screen == null ? BattleHudAnimState.NEUTRAL : screen.animState();
        float[] help = FocusBattleLayout.helpStripRect(w, h, uiScale);
        float out = MColor.clamp01(a.bottomHudSlideOut) * (h - help[1] + 8f * s);
        m.put("mode", piece("mode", FocusBattleLayout.modeTagRect(w, h, uiScale), w, h, null));
        m.put("enemy", piece("enemy", FocusBattleLayout.offset(FocusBattleLayout.enemyPlateRect(w, h, uiScale),
            a.enemyShakeX, a.enemyShakeY), w, h, null));
        m.put("party", piece("party", FocusBattleLayout.offset(FocusBattleLayout.partyWindowRect(w, h, uiScale),
            a.partyShakeX, a.partyShakeY + out), w, h, null));
        m.put("command", piece("command", FocusBattleLayout.offset(FocusBattleLayout.commandWindowRect(w, h, uiScale),
            0f, out), w, h, FocusBattleLayout.offset(FocusBattleLayout.submenuRect(w, h, uiScale), 0f, out)));
        m.put("commandShown", UiValue.of(view != null && view.outcome() == BattleOutcome.NONE));
        m.put("underlays", piece("underlays", new float[]{0, 0, w, h}, w, h, null));
        m.put("overlays", piece("overlays", new float[]{0, 0, w, h}, w, h, null));
        m.put("topmost", piece("topmost", new float[]{0, 0, w, h}, w, h, null));

        // E4: the help strip (HelpStrip.render): nothing for an empty line
        float[] r = FocusBattleLayout.offset(help, 0f, out);
        BattleHelpText.Line line = view == null ? BattleHelpText.Line.EMPTY
            : a.helpLine != null ? a.helpLine : BattleHelpText.lineFor(view, screen.menuState());
        boolean shown = !line.isEmpty() && r[2] > 0f && r[3] > 0f && s > 0f;
        float pad = 12f * s;
        Font font = shown && fonts != null
            ? fonts.fonts().fit(line.text(), MStyle.FONT_META * s, r[2] - 2f * pad, 0.6f) : null;
        shown = shown && font != null;
        int color = line.warning() ? MStyle.TEXT_WARN : MStyle.TEXT_PRIMARY;
        Map<String, UiValue> hs = new LinkedHashMap<>();
        hs.put("shown", UiValue.of(shown));
        hs.put("rect", rect(r));
        hs.put("text", UiValue.of(line.text()));
        hs.put("color", CrosshairContracts.hex(MColor.fade(color, a.helpFade)));
        hs.put("size", UiValue.of(font == null ? 14f : font.getSize()));
        // the baseline below the strip's top, and the text's inset (relative: the strip moves as one)
        hs.put("baseline", UiValue.of(font == null ? 0f : MPainter.baselineFor(r[1] + r[3] / 2f, font.getSize()) - r[1]));
        hs.put("inset", UiValue.of(pad));
        m.put("help", new UiValue.Obj(hs));
        return new UiValue.Obj(m);
    }

    private static UiValue piece(String name, float[] rect, int w, int h, float[] submenu) {
        Map<String, UiValue> p = new LinkedHashMap<>();
        p.put("rect", rect(rect));
        Map<String, UiValue> params = new LinkedHashMap<>();
        params.put("piece", UiValue.of(name));
        params.put("w", UiValue.of(w));
        params.put("h", UiValue.of(h));
        if (submenu != null) {
            params.put("submenu", rect(submenu));
        }
        p.put("params", new UiValue.Obj(params));
        return new UiValue.Obj(p);
    }

    private static UiValue rect(float[] r) {
        return obj("x", r[0], "y", r[1], "w", r[2], "h", r[3]);
    }

    private static UiValue obj(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], switch (v) {
                case Integer n -> UiValue.of(n);
                case Float f -> UiValue.of(f);
                default -> throw new IllegalArgumentException(String.valueOf(v));
            });
        }
        return new UiValue.Obj(m);
    }

    private static double num(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }
}
