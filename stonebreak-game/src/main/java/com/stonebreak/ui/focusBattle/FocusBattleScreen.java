package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleInput;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.CommandAvailability;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.focusBattle.elements.ActionBanner;
import com.stonebreak.ui.focusBattle.elements.CommandWindow;
import com.stonebreak.ui.focusBattle.elements.BattleFloaters;
import com.stonebreak.ui.focusBattle.elements.EncounterTransition;
import com.stonebreak.ui.focusBattle.elements.EnemyPlate;
import com.stonebreak.ui.focusBattle.elements.PartyStatusWindow;
import com.stonebreak.ui.focusBattle.elements.ResultPanel;
import com.stonebreak.ui.focusBattle.elements.TargetCursor;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * Focus battle HUD screen: owns the command-menu cursor, routes keyboard and mouse to the battle,
 * and delegates drawing to {@link SkijaFocusBattleRenderer}.
 *
 * <p>The screen only <em>reads</em> the battle through {@link BattleView} and changes it through
 * {@link BattleInput}; everything else it may ask for goes through {@link BattleScreenHost}. Input
 * routing is strictly ordered so one key never means two things in the same frame:
 * <ol>
 *   <li>intro playing → any confirm skips it;</li>
 *   <li>battle over → the result panel (every press is swallowed until the panel has risen);</li>
 *   <li>Focus Combo prompt open → direction keys answer it;</li>
 *   <li>timing ring or parry prompt open → confirm resolves it;</li>
 *   <li>command window open → menu navigation, the target step and command submission.</li>
 * </ol>
 * Escape steps back out of the target step or the submenu, and otherwise opens the pause menu in
 * any phase. While bound the screen consumes every key and click, so nothing leaks through to
 * gameplay bindings.
 *
 * <p>Commands aimed at the enemy pass through a target step (E8) before they are submitted;
 * commands that act on the monk submit at once, so a reaction Guard is always a single confirm.
 *
 * <p>The screen owns the HUD's windows ({@link SkijaFocusBattleRenderer.Windows}: compositions over
 * MasonryUI widgets that animate themselves) and ticks them, the frame-level {@link HudAnimator} and
 * the stateful layers (banner, floaters, transition, result panel) from one event list per frame.
 * All of them reset on {@link #bind}, which is also what a retry is.
 *
 * <p>Logic paths are GL-free and touch no {@code Game}/{@code Renderer} singleton: the screen is
 * constructed and driven headlessly in tests (a null backend simply renders nothing).
 */
public final class FocusBattleScreen {

    private final SkijaFocusBattleRenderer renderer;
    private final SkijaFocusBattleRenderer.Windows windows = new SkijaFocusBattleRenderer.Windows();
    private final BattleMenuState menu = new BattleMenuState();
    private final BattleHudAnimState anim = new BattleHudAnimState();
    private final HudAnimator animator = new HudAnimator(anim);
    private final ActionBanner banner = new ActionBanner();
    private final TargetCursor targetCursor = new TargetCursor(menu);
    private final BattleFloaters floaters = new BattleFloaters();
    private final EncounterTransition transition = new EncounterTransition();
    private final ResultPanel result = new ResultPanel();
    /** Timed-input visuals: Flurry ring, parry brackets/flash, combo strip, vignettes and letterbox. */
    private final com.stonebreak.ui.focusBattle.timed.TimedInputLayers timed =
            new com.stonebreak.ui.focusBattle.timed.TimedInputLayers(null);

    private BattleView view;
    private BattleInput input;
    private BattleStageLayout stageLayout;
    private BattleScreenHost host;
    /** The event list already consumed: the model republishes the same list until its next update. */
    private List<BattleEvent> consumedEvents;
    /** Camera of the last rendered frame, for hit-testing the target under the pointer. */
    private final Matrix4f lastViewProjection = new Matrix4f();
    private boolean hasViewProjection;

    public FocusBattleScreen(SkijaUIBackend backend) {
        this.renderer = new SkijaFocusBattleRenderer(backend, windows);
        renderer.addOverlay(targetCursor);
        renderer.addOverlay(banner);
        // Ring, parry and combo strip sit under the floating numbers so damage stays readable over them.
        timed.install(renderer);
        // One owner per word: the ring shows its own PERFECT/GOOD/MISS in sync with its burst; the
        // floaters show PARRY!/BLOCK (they carry the blocked amount).
        floaters.setGradeWordsEnabled(false);
        timed.setGuardWordsEnabled(false);
        renderer.addOverlay(floaters);
        renderer.addTopmost(result);
        renderer.addTopmost(transition);
    }

    /** Attach to a running battle. Called on battle start and on retry. */
    public void bind(BattleView view, BattleInput input, BattleStageLayout layout, BattleScreenHost host) {
        this.view = view;
        this.input = input;
        this.stageLayout = layout;
        this.host = host;
        menu.syncWindowOpen(false);
        menu.reset();
        consumedEvents = null;
        hasViewProjection = false;
        animator.reset(view, menu);
        windows.reset();
        banner.reset();
        floaters.reset();
        floaters.setStage(layout);
        timed.setStage(layout);
        timed.reset();
        targetCursor.setStage(layout);
        transition.reset();
        result.reset();
    }

    /** Detach; the screen renders nothing until bound again. */
    public void unbind() {
        view = null;
        input = null;
        stageLayout = null;
        host = null;
        consumedEvents = null;
        hasViewProjection = false;
        menu.syncWindowOpen(false);
        windows.reset();
        floaters.reset();
        floaters.setStage(null);
        timed.setStage(null);
        timed.reset();
        targetCursor.setStage(null);
    }

    public boolean isBound() { return view != null; }

    /** The cursor state (read by the renderer; exposed for tests). */
    public BattleMenuState menuState() { return menu; }

    public CommandWindow commandWindow() { return windows.command; }

    public PartyStatusWindow partyWindow() { return windows.party; }

    public EnemyPlate enemyPlate() { return windows.enemy; }

    /** Frame-level motion (slides, shakes, fades); {@link #animator()} is its only writer. */
    public BattleHudAnimState animState() { return anim; }

    public HudAnimator animator() { return animator; }

    public ActionBanner actionBanner() { return banner; }

    public BattleFloaters floaters() { return floaters; }

    public EncounterTransition encounterTransition() { return transition; }

    public ResultPanel resultPanel() { return result; }

    public SkijaFocusBattleRenderer renderer() { return renderer; }

    /** Where the battle is staged, for world-anchored elements; null while unbound. */
    public BattleStageLayout stageLayout() { return stageLayout; }

    /** Per-frame animation + event consumption. Call after the battle model's update. */
    public void update(float dt) {
        if (view == null) return;
        syncMenu();
        float step = Math.max(0f, dt);
        List<BattleEvent> events = freshEvents();
        animator.update(step, view, menu, events);
        windows.update(step, view, menu, events);
        banner.update(step, view, events);
        floaters.update(step, view, events);
        timed.update(view, step);
        transition.update(step, view);
        result.update(step, view);
    }

    /**
     * This frame's events, or none when the model has not published a new list since the last call
     * (two HUD updates inside one model update must not spawn every floater twice).
     */
    private List<BattleEvent> freshEvents() {
        List<BattleEvent> events = view.frameEvents();
        if (events == null || events.isEmpty() || events == consumedEvents) return List.of();
        consumedEvents = events;
        return events;
    }

    /**
     * Draw the HUD. {@code viewProjection} is projection × view of the live camera, used to place
     * world-anchored elements; may be null (those elements then fall back to their HUD windows).
     */
    public void render(int windowWidth, int windowHeight, Matrix4fc viewProjection) {
        if (view == null) return;
        syncMenu();
        hasViewProjection = viewProjection != null;
        if (hasViewProjection) lastViewProjection.set(viewProjection);
        float uiScale = Settings.getInstance().getUiScale();
        animator.setHudScale(FocusBattleLayout.effectiveScale(windowWidth, windowHeight, uiScale));
        // Floaters take their screen position from the first camera they meet, drawn or not.
        floaters.resolvePending(windowWidth, windowHeight, uiScale, viewProjection);
        renderer.render(windowWidth, windowHeight, view, menu, anim, viewProjection);
    }

    // ─────────────────────────────────────────────── Keyboard

    /** GLFW key event. Returns true when consumed. */
    public boolean handleKeyInput(int key, int action, int mods) {
        if (view == null) return false;
        syncMenu();
        boolean press = action == GLFW_PRESS;
        if (!press && action != GLFW_REPEAT) return true;   // releases are swallowed, never routed

        if (key == GLFW_KEY_ESCAPE) {
            if (press) handleEscape();
            return true;
        }

        PromptView prompt = view.prompt();
        if (view.phase() == BattlePhase.INTRO) {
            if (press && isConfirm(key) && host != null) host.skipIntro();
        } else if (battleOver()) {
            handleResultKey(key, press);
        } else if (prompt instanceof PromptView.Combo) {
            // Press only: a held key must not answer the next prompt too.
            ComboDirection direction = directionOf(key);
            if (press && direction != null && input != null) input.pressDirection(direction);
        } else if (prompt != null) {
            if (press && isConfirm(key) && input != null) input.pressConfirm();
        } else if (view.commandWindowOpen()) {
            handleMenuKey(key, press);
        }
        return true;
    }

    private void handleEscape() {
        if (menuIsLive() && menu.back()) return;
        if (host != null) host.openPauseMenu();
    }

    private void handleMenuKey(int key, boolean press) {
        ComboDirection direction = directionOf(key);
        if (menu.targeting()) {
            // One target: directions have nowhere to go, and must not move the list underneath.
            if (press && isConfirm(key)) confirmTarget();
            else if (press && key == GLFW_KEY_Q) menu.cancelTargeting();
            return;
        }
        if (direction != null) {
            switch (direction) {
                case UP -> menu.moveUp();
                case DOWN -> menu.moveDown();
                case RIGHT -> menu.openSubmenu();
                case LEFT -> menu.closeSubmenu();
            }
        } else if (press && isConfirm(key)) {
            confirmSelection();
        } else if (press && key == GLFW_KEY_Q) {
            if (!menu.back() && host != null) host.openPauseMenu();
        }
    }

    /**
     * Acts on the row under the cursor: opens the submenu, moves to the target step for a command
     * aimed at the enemy, or submits a self-targeted command straight away.
     */
    private void confirmSelection() {
        BattleMenu.Row row = menu.selectedRow();
        if (row.opensQiArts()) {
            menu.openSubmenu();
            return;
        }
        BattleCommand command = row.command();
        if (command == null || input == null) return;
        // An unavailable row does nothing; its reason is already showing in the help strip.
        if (!available(command)) return;
        if (!menu.beginTargeting(command)) input.submit(command);
    }

    /** Target step confirm: submits the pending command at the (only) target. */
    private void confirmTarget() {
        BattleCommand command = menu.targetCommand();
        if (command == null || input == null) return;
        // It may have become unusable while the player was aiming; the help strip says why.
        if (!available(command)) return;
        menu.cancelTargeting();
        input.submit(command);
    }

    private boolean available(BattleCommand command) {
        CommandAvailability availability = view.availability(command);
        return availability == null || availability.available();
    }

    /** Result panel keys. Everything is swallowed until the panel has risen. */
    private void handleResultKey(int key, boolean press) {
        if (!result.interactive()) return;
        ComboDirection direction = directionOf(key);
        if (direction != null) {
            result.moveFocus(direction == ComboDirection.LEFT || direction == ComboDirection.UP ? -1 : 1);
        } else if (press && isConfirm(key)) {
            // The host may re-bind (retry) or unbind the screen inside this call: touch nothing after.
            result.activate(host);
        }
    }

    /** True once the battle has an outcome: input belongs to the result panel from here on. */
    private boolean battleOver() {
        return view.phase() == BattlePhase.RESULT
                || (view.outcome() != null && view.outcome() != BattleOutcome.NONE);
    }

    /** True while the command window is what input is talking to. */
    private boolean menuIsLive() {
        return BattleHudRules.menuLive(view);
    }

    private static boolean isConfirm(int key) {
        return key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_SPACE || key == GLFW_KEY_E;
    }

    private static ComboDirection directionOf(int key) {
        return switch (key) {
            case GLFW_KEY_W, GLFW_KEY_UP -> ComboDirection.UP;
            case GLFW_KEY_S, GLFW_KEY_DOWN -> ComboDirection.DOWN;
            case GLFW_KEY_A, GLFW_KEY_LEFT -> ComboDirection.LEFT;
            case GLFW_KEY_D, GLFW_KEY_RIGHT -> ComboDirection.RIGHT;
            default -> null;
        };
    }

    // ─────────────────────────────────────────────── Mouse

    /** GLFW mouse button event in UI coordinates. Returns true when consumed. */
    public boolean handleMouseClick(double x, double y, int windowWidth, int windowHeight, int button, int action) {
        if (view == null) return false;
        syncMenu();
        if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) return true;
        float px = (float) x, py = (float) y;

        if (view.phase() == BattlePhase.INTRO) {
            if (host != null) host.skipIntro();
        } else if (battleOver()) {
            if (result.interactive()) {
                int hit = ResultPanel.buttonAt(px, py, windowWidth, windowHeight, uiScale());
                if (hit >= 0) {
                    result.focus(hit);
                    result.activate(host);   // may re-bind or unbind: nothing after this
                }
            }
        } else if (view.prompt() instanceof PromptView.Combo) {
            // The combo is answered with directions only.
        } else if (view.prompt() != null) {
            if (input != null) input.pressConfirm();
        } else if (view.commandWindowOpen()) {
            // Rows first: the HUD is drawn over the world, so a row in front of the Archon wins.
            if (pointAtRow(px, py, windowWidth, windowHeight, true)) {
                confirmSelection();
            } else if (menu.targeting() && targetAt(px, py, windowWidth, windowHeight)) {
                confirmTarget();
            }
        }
        return true;
    }

    public void handleMouseMove(double x, double y, int windowWidth, int windowHeight) {
        if (view == null) return;
        syncMenu();
        if (battleOver()) {
            result.focus(ResultPanel.buttonAt((float) x, (float) y, windowWidth, windowHeight, uiScale()));
        } else if (menuIsLive() && !menu.targeting()) {
            // While aiming, the pointer crosses the lists on its way to the target: hover is inert.
            pointAtRow((float) x, (float) y, windowWidth, windowHeight, false);
        }
    }

    /** True when the pointer is on the target: its projected body, the hand cursor or the name tag. */
    private boolean targetAt(float px, float py, int w, int h) {
        return TargetCursor.place(stageLayout, view, hasViewProjection ? lastViewProjection : null, w, h, uiScale())
                .contains(px, py);
    }

    private static float uiScale() {
        return Settings.getInstance().getUiScale();
    }

    /**
     * Moves the cursor to the row under the pointer, asking the menu lists themselves
     * ({@code MMenuList.rowAt}: the rects they draw). While the submenu is open a plain hover over the root list is ignored — the pointer
     * crosses the Qi Arts row on its way into the submenu — but a click there takes over.
     *
     * @return true when the pointer is on a row (which is now the selected one)
     */
    private boolean pointAtRow(float px, float py, int w, int h, boolean click) {
        float uiScale = uiScale();
        float scale = FocusBattleLayout.effectiveScale(w, h, uiScale);
        if (menu.submenuOpen()) {
            int row = windows.command.artsRowAt(px, py, FocusBattleLayout.submenuRect(w, h, uiScale), scale);
            if (row >= 0) {
                menu.selectSubmenu(row);
                return true;
            }
            if (!click) return false;
        }
        int row = windows.command.rootRowAt(px, py, FocusBattleLayout.commandWindowRect(w, h, uiScale), scale);
        if (row < 0) return false;
        boolean wasOpenHere = menu.submenuOpen() && menu.rootIndex() == row;
        menu.selectRoot(row);
        // Clicking the opener of an already-open submenu just closes it.
        return !wasOpenHere;
    }

    // ─────────────────────────────────────────────── Lifecycle

    /** Keeps the cursor in step with the model's command window (resets it on every open/close edge). */
    private void syncMenu() {
        menu.syncWindowOpen(view != null && view.commandWindowOpen());
    }

    public void cleanup() {
        unbind();
        renderer.dispose();
    }
}
