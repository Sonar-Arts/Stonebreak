package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.startupIntro.IntroConfig;
import com.stonebreak.ui.startupIntro.IntroScene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The startup intro ({@code stonebreak:screen.intro} 1, #301): root {@code intro} with the scene's
 * {@code time} (intro seconds, also the document's {@code intro} clock), {@code phase} (submarine, hold,
 * fade, complete), {@code reveal} (logo opacity), {@code fade} (black over the scene, 0..1) and
 * {@code scale} (the intro's own fit to 1920x1080, which ignores the UI scale) and {@code logo}
 * {@code {x, y, width, height, shadowX, shadowY}} (device px, as {@code SonarLogoRenderer} places it); action
 * {@code stonebreak:screen.intro.skip} (Esc, Space, Enter or a left press). The game's
 * {@link IntroScene} stays the timeline: the document only presents it.
 */
public final class IntroContracts {

    public static final HostContract INTRO = HostContract.of("stonebreak:screen.intro", 1);

    public static final DataType.Obj LOGO = DataType.object("x", DataType.number(), "y", DataType.number(),
        "width", DataType.number(), "height", DataType.number(), "shadowX", DataType.number(),
        "shadowY", DataType.number());
    public static final DataType.Obj TYPE = DataType.object("time", DataType.number(), "phase", DataType.string(),
        "reveal", DataType.number(), "fade", DataType.number(), "scale", DataType.number(), "logo", LOGO);

    /** The game behind the intro. */
    public interface Services {
        /** The showing intro's scene, or null. */
        default IntroScene introScene() {
            return null;
        }

        /** Skips to the main menu; null on success or why it refused. */
        default String introSkip() {
            return "no intro is showing";
        }
    }

    private final Services services;
    private final DataCell cell;
    private UiValue last;

    public IntroContracts(UiHost ui, Services services) {
        this.services = services;
        cell = ui.data().register("intro", new DataCell(TYPE, value(null)), INTRO);
        ui.actions().register(ActionSpec.of(INTRO.id() + ".skip", INTRO, null, DataType.ANY), (args, ctx) -> {
            String problem = services.introSkip();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    /** UI thread: every frame the intro runs (its scene changes every frame). */
    public void poll() {
        UiValue v = value(services.introScene());
        if (!v.equals(last)) {
            last = v;
            cell.set(v);
        }
    }

    static UiValue.Obj value(IntroScene s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("time", SettingsContract.exact(s == null ? 0f : s.time()));
        m.put("phase", UiValue.of(s == null ? IntroScene.Phase.SUBMARINE_WITH_SONAR.wire() : s.phase().wire()));
        m.put("reveal", SettingsContract.exact(s == null ? 0f : s.reveal()));
        m.put("fade", SettingsContract.exact(s == null ? 0f : s.fade()));
        m.put("scale", SettingsContract.exact(s == null || !s.entered() ? 1f : IntroConfig.scale()));
        float[] at = s == null || !s.entered() ? new float[5]
            : com.stonebreak.ui.startupIntro.render.SonarLogoRenderer.placement(s.width(), s.height());
        Map<String, UiValue> logo = new LinkedHashMap<>();
        logo.put("x", SettingsContract.exact(at[0]));
        logo.put("y", SettingsContract.exact(at[1]));
        logo.put("width", SettingsContract.exact(at[2]));
        logo.put("height", SettingsContract.exact(at[3]));
        logo.put("shadowX", SettingsContract.exact(at[0] + at[4]));
        logo.put("shadowY", SettingsContract.exact(at[1] + at[4]));
        m.put("logo", new UiValue.Obj(logo));
        return new UiValue.Obj(m);
    }
}
