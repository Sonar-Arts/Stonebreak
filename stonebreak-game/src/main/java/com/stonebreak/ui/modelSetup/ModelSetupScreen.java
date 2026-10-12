package com.stonebreak.ui.modelSetup;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Outcome;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Snapshot;

import java.util.List;
import java.util.function.Supplier;

/**
 * Game-launch setup of the terrain model ({@link ModelSetup}), shown between the intro and the main
 * menu while there is something to install or compile: every step with its live status, what it is
 * doing right now, and how long it has taken. Setup keeps running if the player continues to the
 * menu; the menu's terrain model waits for it. A launch with nothing to do never shows this screen.
 *
 * <p>The screen is the controller (when it leaves for the menu, what its buttons do); it shows as the
 * shipped document {@value #DOCUMENT_ID}, fed by {@code stonebreak:screen.model-setup}.
 */
public final class ModelSetupScreen {

    /** The shipped document's screen id ({@code ui/documents/model_setup.sbui}). */
    public static final String DOCUMENT_ID = "model_setup";

    /** How long a finished setup stays on screen (all ticks green) before the main menu. */
    private static final float READY_HOLD_SECONDS = 1.6f;

    /** A button of the screen: what it is called on the wire and what it says. */
    public record Button(String id, String label) { }

    private final Supplier<Snapshot> source;
    private final Runnable toMenu;
    private final Runnable retry;
    private float readyTimer;

    public ModelSetupScreen() {
        this(ModelSetup.getInstance()::snapshot, () -> Game.getInstance().setState(GameState.MAIN_MENU),
            ModelSetup.getInstance()::retry);
    }

    /**
     * @param source what to show (the live setup; tests pass fixed snapshots)
     * @param toMenu leaves for the main menu (setup carries on in the background)
     * @param retry  runs a failed setup again
     */
    public ModelSetupScreen(Supplier<Snapshot> source, Runnable toMenu, Runnable retry) {
        this.source = source;
        this.toMenu = toMenu;
        this.retry = retry;
    }

    public Snapshot snapshot() {
        return source.get();
    }

    public void update(float deltaTime) {
        Snapshot snap = source.get();
        if (snap.outcome() == Outcome.READY && !snap.workNeeded()) {
            toMenu.run();   // an up-to-date install, seen only if the intro was skipped before the check
        } else if (snap.outcome() == Outcome.READY) {
            readyTimer += deltaTime;
            if (readyTimer >= READY_HOLD_SECONDS) toMenu.run();
        } else {
            readyTimer = 0f;
        }
    }

    /** The buttons for {@code outcome}: continuing while it runs, retrying a failure, continuing without it. */
    public static List<Button> buttons(Outcome outcome) {
        return switch (outcome) {
            case RUNNING -> List.of(new Button("continue", "Continue in background"));
            case FAILED -> List.of(new Button("retry", "Retry"), new Button("continue", "Continue without it"));
            case UNAVAILABLE -> List.of(new Button("continue", "Continue"));
            default -> List.of();
        };
    }

    /**
     * A press on a button ({@code continue}: the main menu, setup carries on; {@code retry}: setup again), or
     * Escape ({@code continue} whatever is showing). @return false for an unknown button
     */
    public boolean press(String id) {
        switch (id) {
            case "continue" -> toMenu.run();
            case "retry" -> {
                if (source.get().outcome() != Outcome.FAILED) return true; // no Retry showing: nothing to do
                retry.run();
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
