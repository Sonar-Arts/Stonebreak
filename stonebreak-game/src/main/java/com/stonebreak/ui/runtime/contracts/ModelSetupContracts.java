package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.modelSetup.ModelSetupScreen;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Outcome;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Snapshot;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Status;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Step;
import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The terrain model's launch setup ({@code stonebreak:screen.model-setup} 1, Daedalus). {@link ModelSetupScreen}
 * is the controller; root {@code modelSetup}: the {@code logo} rect (device px), {@code title}, {@code subtitle},
 * the {@code steps}
 * ({@code step}, {@code title}, {@code detail}, {@code status} pending|running|done|skipped|failed and its
 * {@code dim} (pending or skipped) / {@code failed} / {@code skipped} flags, {@code bar}:
 * a measured running step, {@code fraction}, {@code icon}: the {@code stonebreak:setup-icon} params with the
 * running spinner's angle), the footer {@code lines} ({@code line}, {@code text}, {@code tone}
 * activity|faint|done|failed|warn|dim, {@code small}, {@code gap}: 2 px further down) and the {@code buttons}
 * ({@code id}, {@code label}).
 *
 * <p>Action {@code .press {id}} (continue, retry; Escape sends continue), refused unless the screen is showing.
 */
public final class ModelSetupContracts {

    public static final HostContract SETUP = HostContract.of("stonebreak:screen.model-setup", 1);

    /** The game behind the screen. */
    public interface Services {
        /** The setup screen while it is the current state, or null. */
        default ModelSetupScreen modelSetup() {
            return null;
        }

        /** The window in device px ({@code {w, h}}); null: unknown. */
        default int[] screenSize() {
            return null;
        }
    }

    private final Services services;
    private final DataCell state;
    private UiValue last;

    public ModelSetupContracts(UiHost ui, Services services) {
        this.services = services;
        state = ui.data().register("modelSetup", new DataCell(DataType.ANY, closed()), SETUP);
        ui.actions().register(ActionSpec.of(SETUP.id() + ".press", SETUP, DataType.object("id", DataType.string()),
            DataType.ANY), (args, ctx) -> {
                ModelSetupScreen s = services.modelSetup();
                String id = args instanceof UiValue.Obj o && o.get("id") instanceof UiValue.Str str ? str.value() : "";
                String problem = s == null ? "the model setup screen is not showing"
                    : s.press(id) ? null : "no such button";
                poll();
                return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                    : CompletableFuture.failedFuture(new IllegalStateException(problem));
            });
    }

    /** UI thread, once per frame (a running step's spinner turns every frame). */
    public void poll() {
        ModelSetupScreen s = services.modelSetup();
        // the spinner turns on the UI wall clock (UiWallClock: pinned in fixtures)
        int[] size = services.screenSize();
        UiValue v = s == null ? closed() : model(s.snapshot(), com.stonebreak.ui.UiWallClock.millis(),
            size == null ? 1920 : size[0], com.stonebreak.config.Settings.getInstance().getUiScale());
        if (!v.equals(last)) {
            last = v;
            state.set(v);
        }
    }

    private static UiValue closed() {
        return obj("open", false);
    }

    static UiValue model(Snapshot snap, long nowMillis, int width, float scale) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("open", UiValue.TRUE);
        // the logo in device px, in the legacy screen's float maths (its nearest-sampled texels land where they did)
        float h = 84f * scale;
        float w = h * (1641f / 419f);
        m.put("logo", obj("x", width / 2f - w / 2f, "y", 40f * scale, "w", w, "h", h));
        m.put("title", UiValue.of(switch (snap.outcome()) {
            case READY -> "Terrain model ready";
            case FAILED -> "Terrain model setup failed";
            case UNAVAILABLE -> "Terrain model unavailable";
            default -> "Setting up the terrain model";
        }));
        m.put("subtitle", UiValue.of(TGMPipe.generatorLabel().replace(" · ", " ")
            + "  -  set up once, later launches skip this"));
        // the legacy spinner: a 100 degree arc, one turn a second
        float angle = (nowMillis % 1000L) * 0.36f;
        List<UiValue> steps = new ArrayList<>();
        for (Step step : snap.steps()) {
            String detail = step.detail();
            if (step.status() == Status.PENDING) detail = snap.outcome() == Outcome.RUNNING ? "waiting" : "not run";
            boolean bar = step.status() == Status.RUNNING && step.fraction() >= 0;
            String status = step.status().name().toLowerCase(java.util.Locale.ROOT);
            steps.add(obj("step", step.id().name(), "title", step.id().title, "detail", detail == null ? "" : detail,
                "status", status, "dim", step.status() == Status.PENDING || step.status() == Status.SKIPPED,
                "failed", step.status() == Status.FAILED, "skipped", step.status() == Status.SKIPPED,
                "bar", bar, "fraction", (float) Math.min(1.0, Math.max(0.0, step.fraction())),
                "icon", obj("status", status, "angle", step.status() == Status.RUNNING ? angle : 0f)));
        }
        m.put("steps", new UiValue.Arr(steps));
        long secs = snap.elapsedMs() / 1000;
        String elapsed = String.format("%d:%02d", secs / 60, secs % 60);
        List<UiValue> lines = new ArrayList<>();
        switch (snap.outcome()) {
            case RUNNING -> {
                String act = snap.activity() == null || snap.activity().isEmpty() ? "Working..." : snap.activity();
                lines.add(obj("line", "activity", "text", "> " + act, "tone", "activity", "small", true, "gap", false));
                lines.add(obj("line", "elapsed", "text", "Elapsed " + elapsed
                    + "  -  you can keep playing with standard terrain meanwhile", "tone", "faint", "small", true, "gap", false));
            }
            case READY -> lines.add(obj("line", "done", "text", "Done in " + elapsed + ". Starting...", "tone", "done",
                "small", false, "gap", false));
            case FAILED, UNAVAILABLE -> {
                lines.add(obj("line", "message", "text", snap.message() == null ? "" : snap.message(),
                    "tone", snap.outcome() == Outcome.FAILED ? "failed" : "warn", "small", false, "gap", false));
                if (snap.outcome() == Outcome.FAILED) {
                    // 2 px lower than the line rhythm, as the legacy screen drew it
                    lines.add(obj("line", "log", "text", "Details: Models/logs/setup.log", "tone", "dim", "small", true,
                        "gap", true));
                }
            }
            default -> {
            }
        }
        m.put("lines", new UiValue.Arr(lines));
        List<UiValue> buttons = new ArrayList<>();
        for (ModelSetupScreen.Button b : ModelSetupScreen.buttons(snap.outcome())) {
            buttons.add(obj("id", b.id(), "label", b.label()));
        }
        m.put("buttons", new UiValue.Arr(buttons));
        return new UiValue.Obj(m);
    }

    private static UiValue obj(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], switch (v) {
                case UiValue u -> u;
                case String str -> UiValue.of(str);
                case Boolean b -> UiValue.of(b);
                case Float f -> UiValue.of(f);
                default -> throw new IllegalArgumentException(String.valueOf(v));
            });
        }
        return new UiValue.Obj(m);
    }
}
