package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every player setting the {@code stonebreak:settings} contract (version 2) exposes, as one
 * table: the field's type, how it is read and written, the range the setter would clamp to
 * (refused up front so a document shows the problem instead of a silently clamped value), and
 * whether the legacy settings menu applies it immediately (before Apply).
 *
 * <p>Floats are published through their shortest decimal ({@code 1.1f} reads {@code 1.1}, not
 * {@code 1.100000023841858}), so a value that round-trips through a document compares equal to
 * the stored one and an untouched draft is not dirty.
 *
 * <p>Semantics, matching the legacy menu: {@link Live} fields take effect as soon as they are
 * changed ({@code stonebreak:settings.set-live}, nothing saved); everything is written, pushed to
 * the game systems and saved by {@code stonebreak:settings.apply}; a UI-scale change applied
 * there is held for confirmation ({@code keep-ui-scale} / {@code revert-ui-scale}) and not saved
 * until kept.
 */
public final class SettingsContract {

    /** What changing a field does before Apply. */
    public enum Live {
        /** Stored; takes effect on Apply (world distances, crosshair, master volume, resolution, UI). */
        ON_APPLY,
        /** Read by the game every frame: storing it is enough. */
        READ_LIVE,
        /** Stored and pushed to its system at once ({@link Effects}). */
        PUSHED
    }

    /** One setting. */
    public record Field(String name, DataType type, Function<Settings, UiValue> read,
                        BiConsumer<Settings, UiValue> write, Function<UiValue, String> problem, Live live) {
    }

    /** The side effects of {@link Live#PUSHED} fields (the game: {@code SettingsEffects}). */
    public interface Effects {
        void pushed(String field, Settings settings);
    }

    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    static {
        // display
        add("windowWidth", DataType.integer(), s -> UiValue.of(s.getWindowWidth()),
            (s, v) -> s.setResolution(i(v), s.getWindowHeight()), v -> i(v) < 320 ? "window width must be at least 320" : null,
            Live.ON_APPLY);
        add("windowHeight", DataType.integer(), s -> UiValue.of(s.getWindowHeight()),
            (s, v) -> s.setResolution(s.getWindowWidth(), i(v)), v -> i(v) < 240 ? "window height must be at least 240" : null,
            Live.ON_APPLY);
        add("vsyncEnabled", DataType.bool(), s -> UiValue.of(s.isVsyncEnabled()), (s, v) -> s.setVsyncEnabled(b(v)),
            null, Live.PUSHED);
        add("maxFps", DataType.integer(), s -> UiValue.of(s.getMaxFps()), (s, v) -> s.setMaxFps(i(v)),
            range(Settings.MIN_MAX_FPS, Settings.MAX_MAX_FPS, "max FPS"), Live.READ_LIVE);
        // audio
        add("masterVolume", DataType.number(), s -> exact(s.getMasterVolume()), (s, v) -> s.setMasterVolume(f(v)),
            range(0, 1, "master volume"), Live.ON_APPLY);
        add("musicVolume", DataType.number(), s -> exact(s.getMusicVolume()), (s, v) -> s.setMusicVolume(f(v)),
            range(0, 1, "music volume"), Live.PUSHED);
        add("musicEnabled", DataType.bool(), s -> UiValue.of(s.getMusicEnabled()), (s, v) -> s.setMusicEnabled(b(v)),
            null, Live.PUSHED);
        // player model
        add("armModelType", DataType.string(), s -> UiValue.of(s.getArmModelType()), (s, v) -> s.setArmModelType(str(v)),
            oneOf("arm model", SettingsConfig.ARM_MODEL_TYPES), Live.ON_APPLY);
        add("selectedHat", DataType.string(), s -> UiValue.of(s.getSelectedHat()), (s, v) -> s.setSelectedHat(str(v)),
            null, Live.ON_APPLY);
        add("selectedHair", DataType.string(), s -> UiValue.of(s.getSelectedHair()), (s, v) -> s.setSelectedHair(str(v)),
            null, Live.ON_APPLY);
        // crosshair
        add("crosshairStyle", DataType.string(), s -> UiValue.of(s.getCrosshairStyle()),
            (s, v) -> s.setCrosshairStyle(str(v)), oneOf("crosshair style", SettingsConfig.CROSSHAIR_STYLES), Live.ON_APPLY);
        add("crosshairSize", DataType.number(), s -> exact(s.getCrosshairSize()), (s, v) -> s.setCrosshairSize(f(v)),
            range(SettingsConfig.MIN_CROSSHAIR_SIZE, SettingsConfig.MAX_CROSSHAIR_SIZE, "crosshair size"), Live.ON_APPLY);
        add("crosshairThickness", DataType.number(), s -> exact(s.getCrosshairThickness()),
            (s, v) -> s.setCrosshairThickness(f(v)), range(1, 8, "crosshair thickness"), Live.ON_APPLY);
        add("crosshairGap", DataType.number(), s -> exact(s.getCrosshairGap()), (s, v) -> s.setCrosshairGap(f(v)),
            range(0, 16, "crosshair gap"), Live.ON_APPLY);
        add("crosshairOpacity", DataType.number(), s -> exact(s.getCrosshairOpacity()),
            (s, v) -> s.setCrosshairOpacity(f(v)), range(0.1, 1, "crosshair opacity"), Live.ON_APPLY);
        add("crosshairColorR", DataType.number(), s -> exact(s.getCrosshairColorR()),
            (s, v) -> s.setCrosshairColor(f(v), s.getCrosshairColorG(), s.getCrosshairColorB()),
            range(0, 1, "crosshair red"), Live.ON_APPLY);
        add("crosshairColorG", DataType.number(), s -> exact(s.getCrosshairColorG()),
            (s, v) -> s.setCrosshairColor(s.getCrosshairColorR(), f(v), s.getCrosshairColorB()),
            range(0, 1, "crosshair green"), Live.ON_APPLY);
        add("crosshairColorB", DataType.number(), s -> exact(s.getCrosshairColorB()),
            (s, v) -> s.setCrosshairColor(s.getCrosshairColorR(), s.getCrosshairColorG(), f(v)),
            range(0, 1, "crosshair blue"), Live.ON_APPLY);
        add("crosshairOutline", DataType.bool(), s -> UiValue.of(s.getCrosshairOutline()),
            (s, v) -> s.setCrosshairOutline(b(v)), null, Live.ON_APPLY);
        // graphics (the renderers read these every frame)
        add("leafTransparency", DataType.bool(), s -> UiValue.of(s.getLeafTransparency()),
            (s, v) -> s.setLeafTransparency(b(v)), null, Live.PUSHED);
        add("waterShaderEnabled", DataType.bool(), s -> UiValue.of(s.getWaterShaderEnabled()),
            (s, v) -> s.setWaterShaderEnabled(b(v)), null, Live.READ_LIVE);
        add("cloudsEnabled", DataType.bool(), s -> UiValue.of(s.getCloudsEnabled()), (s, v) -> s.setCloudsEnabled(b(v)),
            null, Live.READ_LIVE);
        add("godRaysEnabled", DataType.bool(), s -> UiValue.of(s.getGodRaysEnabled()),
            (s, v) -> s.setGodRaysEnabled(b(v)), null, Live.READ_LIVE);
        add("shadowsEnabled", DataType.bool(), s -> UiValue.of(s.getShadowsEnabled()),
            (s, v) -> s.setShadowsEnabled(b(v)), null, Live.READ_LIVE);
        add("playerNameTagsEnabled", DataType.bool(), s -> UiValue.of(s.getPlayerNameTagsEnabled()),
            (s, v) -> s.setPlayerNameTagsEnabled(b(v)), null, Live.READ_LIVE);
        add("shadowQuality", DataType.string(), s -> UiValue.of(s.getShadowQuality()),
            (s, v) -> s.setShadowQuality(str(v)), oneOf("shadow quality", SettingsConfig.SHADOW_QUALITY_VALUES),
            Live.READ_LIVE);
        add("shadowDistance", DataType.integer(), s -> UiValue.of(s.getShadowDistance()),
            (s, v) -> s.setShadowDistance(i(v)), range(Settings.MIN_SHADOW_DISTANCE, Settings.MAX_SHADOW_DISTANCE,
                "shadow distance"), Live.READ_LIVE);
        add("smoothLightingEnabled", DataType.bool(), s -> UiValue.of(s.getSmoothLightingEnabled()),
            (s, v) -> s.setSmoothLightingEnabled(b(v)), null, Live.PUSHED);
        // world
        add("renderDistance", DataType.integer(), s -> UiValue.of(s.getRenderDistance()),
            (s, v) -> s.setRenderDistance(i(v)),
            range(WorldConfiguration.MIN_RENDER_DISTANCE, WorldConfiguration.MAX_RENDER_DISTANCE, "render distance"),
            Live.ON_APPLY);
        add("lodDistance", DataType.integer(), s -> UiValue.of(s.getLodDistance()), (s, v) -> s.setLodDistance(i(v)),
            range(WorldConfiguration.MIN_LOD_RANGE, WorldConfiguration.MAX_LOD_RANGE, "LOD distance"), Live.ON_APPLY);
        add("lodEnabled", DataType.bool(), s -> UiValue.of(s.getLodEnabled()), (s, v) -> s.setLodEnabled(b(v)),
            null, Live.PUSHED);
        add("lodQuality", DataType.string(), s -> UiValue.of(s.getLodQuality()), (s, v) -> s.setLodQuality(str(v)),
            oneOf("LOD quality", SettingsConfig.LOD_QUALITY_VALUES), Live.PUSHED);
        // UI
        add("uiScale", DataType.number(), s -> exact(s.getUiScale()), (s, v) -> s.setUiScale(f(v)),
            range(0.5, 2, "UI scale"), Live.ON_APPLY);
        add("uiTextScale", DataType.number(), s -> exact(s.getUiTextScale()), (s, v) -> s.setUiTextScale(f(v)),
            range(0.5, 3, "text scale"), Live.ON_APPLY);
        add("reducedMotion", DataType.bool(), s -> UiValue.of(s.isReducedMotion()), (s, v) -> s.setReducedMotion(b(v)),
            null, Live.ON_APPLY);
    }

    /** The {@code settings} root: every field, all required. */
    public static final DataType.Obj TYPE;
    /** The commit argument: any subset of the fields (a whole draft or only the staged ones). */
    public static final DataType.Obj PARTIAL;

    static {
        Map<String, DataType> all = new LinkedHashMap<>();
        Map<String, DataType> partial = new LinkedHashMap<>();
        FIELDS.forEach((k, f) -> {
            all.put(k, f.type());
            partial.put(k, f.type().orNull());
        });
        TYPE = DataType.object(all);
        PARTIAL = DataType.object(partial);
    }

    private SettingsContract() {
    }

    public static Map<String, Field> fields() {
        return java.util.Collections.unmodifiableMap(FIELDS);
    }

    /** Names of the fields that take effect before Apply. */
    public static Set<String> liveFields() {
        Set<String> out = new java.util.LinkedHashSet<>();
        FIELDS.forEach((k, f) -> {
            if (f.live() != Live.ON_APPLY) {
                out.add(k);
            }
        });
        return out;
    }

    /** The current settings as the {@code settings} root value. */
    public static UiValue.Obj read(Settings s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        FIELDS.forEach((k, f) -> m.put(k, f.read().apply(s)));
        return new UiValue.Obj(m);
    }

    /** Why {@code value} is not acceptable for {@code field}, or null. Unknown fields are a problem. */
    public static String problem(String field, UiValue value) {
        Field f = FIELDS.get(field);
        if (f == null) {
            return "unknown setting '" + field + "'";
        }
        if (value == null || value instanceof UiValue.Null) {
            return null; // absent from a partial commit
        }
        String typeProblem = f.type().problem(value);
        if (typeProblem != null) {
            return field + ": " + typeProblem;
        }
        return f.problem() == null ? null : f.problem().apply(value);
    }

    /** First problem among the present fields of {@code partial}, or null. */
    public static String problem(UiValue.Obj partial) {
        for (Map.Entry<String, UiValue> e : partial.fields().entrySet()) {
            String p = problem(e.getKey(), e.getValue());
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * Writes the present, non-null fields of {@code partial} into {@code s} (already validated).
     * Fields equal to the stored value are left untouched, so a whole-draft commit does not
     * re-run setters the player never changed.
     *
     * @return the names of the fields that changed
     */
    public static List<String> write(Settings s, UiValue.Obj partial) {
        java.util.ArrayList<String> changed = new java.util.ArrayList<>();
        for (Map.Entry<String, Field> e : FIELDS.entrySet()) {
            UiValue v = partial.get(e.getKey());
            if (v == null || v instanceof UiValue.Null) {
                continue;
            }
            Field f = e.getValue();
            if (!v.equals(f.read().apply(s))) {
                f.write().accept(s, v);
                changed.add(e.getKey());
            }
        }
        return changed;
    }

    /**
     * Applies one live field now, as the legacy menu does when its widget changes.
     *
     * @return null, or why the field cannot be set live
     */
    public static String setLive(Settings s, String field, UiValue value, Effects effects) {
        Field f = FIELDS.get(field);
        if (f == null) {
            return "unknown setting '" + field + "'";
        }
        if (f.live() == Live.ON_APPLY) {
            return "setting '" + field + "' takes effect on apply";
        }
        String p = problem(field, value);
        if (p != null) {
            return p;
        }
        f.write().accept(s, value);
        if (f.live() == Live.PUSHED) {
            effects.pushed(field, s);
        }
        return null;
    }

    /** A float as its shortest decimal, so it round-trips through a document unchanged. */
    public static UiValue exact(float f) {
        return Float.isFinite(f) ? UiValue.of(Double.parseDouble(Float.toString(f))) : UiValue.of(0);
    }

    private static void add(String name, DataType type, Function<Settings, UiValue> read,
                            BiConsumer<Settings, UiValue> write, Function<UiValue, String> problem, Live live) {
        FIELDS.put(name, new Field(name, type, read, write, problem, live));
    }

    private static Function<UiValue, String> range(double min, double max, String what) {
        return v -> {
            double d = v instanceof UiValue.Num n ? n.value() : Double.NaN;
            return !(d >= min && d <= max) ? what + " must be between " + fmt(min) + " and " + fmt(max) : null;
        };
    }

    private static Function<UiValue, String> oneOf(String what, String[] allowed) {
        List<String> list = Arrays.asList(allowed);
        return v -> v instanceof UiValue.Str s && list.contains(s.value()) ? null
            : what + " must be one of " + String.join(", ", list);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static int i(UiValue v) {
        return v instanceof UiValue.Num n ? (int) Math.round(n.value()) : 0;
    }

    private static float f(UiValue v) {
        return v instanceof UiValue.Num n ? (float) n.value() : 0f;
    }

    private static boolean b(UiValue v) {
        return v instanceof UiValue.Bool x && x.value();
    }

    private static String str(UiValue v) {
        return v instanceof UiValue.Str s ? s.value() : "";
    }
}
