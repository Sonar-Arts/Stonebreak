package com.stonebreak.ui.runtime.screens;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.ui.runtime.input.UiInputGate;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiScriptServices;
import com.stonebreak.core.Game;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiInput;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.UiFrameClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Hosts UI document screens in the game window (#297 onward): the game-side lifecycle every
 * migrated screen goes through.
 *
 * <ul>
 *   <li><b>Open</b> - {@link #open(String, DocumentScreen.Options)} opens the shipped export
 *       {@code ui/documents/<id>.sbui} through {@code GameUiDocuments.openBound} (activation,
 *       asset and input gates) unless the screen is rolled back ({@link DocumentScreenPolicy}).
 *       An empty result means "use the legacy screen": not shipped, rolled back, or refused (the
 *       refusal is logged and shown as a red chat line, never a blank screen).</li>
 *   <li><b>Order</b> - the one ordered stack in {@link GameUiInput#views()} (by {@link UiLayer},
 *       then open order) is both the draw order ({@link #render}) and, reversed, the input order.</li>
 *   <li><b>Frame</b> - {@link #frame()} advances every screen by the shared
 *       {@link UiFrameClock} ({@code ui} clock by frame time, {@code game} clock by simulated
 *       time) and runs their scripts.</li>
 *   <li><b>Close and navigate</b> - {@code ui.close()} / {@code ui.navigate()} and
 *       {@link #requestClose} only queue; {@link #endFrame()} performs them after the frame was
 *       drawn, so no script state or scope is ever torn down underneath the dispatch that asked
 *       (C4). Navigation opens the target's document when it has one, else a
 *       {@link LegacyNavigation} target, and replaces the asking screen unless {@code push: true}.</li>
 *   <li><b>Pointer</b> - while a screen that {@code releasesPointer} is open the cursor stays
 *       free ({@code MouseCaptureManager}); the capture state is refreshed on every open/close.</li>
 *   <li><b>World</b> - {@code perWorld} screens close when the player leaves the world.</li>
 *   <li><b>Locale</b> - each shipped screen's input gate is re-polled every frame (cheap: only a
 *       locale or tree change re-evaluates); a screen the window can no longer serve closes with
 *       a refusal report and its {@code onClosed} runs, so the caller can fall back to legacy.</li>
 * </ul>
 *
 * <p>Main thread only, except {@link #worldLeft()}.
 */
public final class DocumentScreenHost {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentScreenHost.class);
    private static final float[] RED = {1f, 0.33f, 0.33f, 1f};
    private static final int MAX_OPS_PER_FRAME = 64;

    /** Builds the view of a shipped screen; the game's goes through {@code GameUiDocuments.openScreen}. */
    @FunctionalInterface
    public interface Opener {
        UiDocumentView open(String id, UiScriptServices services, UiLayer layer) throws Exception;
    }

    private static volatile DocumentScreenHost instance;

    private final DocumentScreenPolicy policy;
    private final Opener opener;
    private final java.util.function.BooleanSupplier inWorld;
    private final List<DocumentScreen> screens = new ArrayList<>();
    private final Map<UiDocumentView, DocumentScreen> byView = new IdentityHashMap<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private volatile boolean worldLeft;

    public DocumentScreenHost(DocumentScreenPolicy policy, Opener opener) {
        this(policy, opener, () -> true);
    }

    /** @param inWorld whether a world is running: {@code perWorld} screens never open outside one */
    public DocumentScreenHost(DocumentScreenPolicy policy, Opener opener, java.util.function.BooleanSupplier inWorld) {
        this.policy = policy;
        this.opener = opener;
        this.inWorld = inWorld;
    }

    /** The game's host, created on first use. */
    public static DocumentScreenHost get() {
        DocumentScreenHost h = instance;
        if (h == null) {
            synchronized (DocumentScreenHost.class) {
                h = instance;
                if (h == null) {
                    h = new DocumentScreenHost(DocumentScreenPolicy.fromSystem(), DocumentScreenHost::openShipped,
                        DocumentScreenHost::worldRunning);
                    MultiplayerSession.addModeListener(h::sessionChanged);
                    instance = h;
                }
            }
        }
        return h;
    }

    /** The host if one was created (headless code paths). */
    public static Optional<DocumentScreenHost> ifCreated() {
        return Optional.ofNullable(instance);
    }

    private static UiDocumentView openShipped(String id, UiScriptServices services, UiLayer layer) throws Exception {
        MasonryBackend backend = backend();
        return GameUiDocuments.openScreen(id, backend == null ? () -> null : backend::typeface, services, layer);
    }

    /** A world is loaded and the player exists (not the intro, menus or the loading screen). */
    public static boolean worldRunning() {
        Game game = Game.getInstance();
        return Game.getWorld() != null && Game.getPlayer() != null && game != null
            && game.getState() != com.stonebreak.core.GameState.LOADING;
    }

    private static MasonryBackend backend() {
        var renderer = Game.getRenderer();
        return renderer == null ? null : renderer.getSkijaBackend();
    }

    public DocumentScreenPolicy policy() {
        return policy;
    }

    // ── open ───────────────────────────────────────────────────────────────

    /**
     * Opens the document screen {@code id}, or returns the already open one.
     *
     * @return empty when the legacy screen must be used: no shipped document, rolled back, or
     *         refused by a gate (reported in the log and chat)
     */
    public Optional<DocumentScreen> open(String id, DocumentScreen.Options options) {
        Optional<DocumentScreen> open = find(id);
        if (open.isPresent()) {
            return open;
        }
        if (!policy.useDocument(id)) {
            return Optional.empty();
        }
        if (options.perWorld() && !inWorld.getAsBoolean()) {
            LOGGER.warn("[ui-screen] {} is world-scoped and no world is running; not opened", id);
            return Optional.empty();
        }
        DocumentScreen screen = new DocumentScreen(id, options);
        UiDocumentView view;
        try {
            view = opener.open(id, services(screen), options.layer());
        } catch (Exception | LinkageError e) {
            reportRefusal(id, e);
            return Optional.empty();
        }
        install(screen, view);
        // Re-checked whenever the locale or the tree changes: a switch to a script the game
        // cannot type or draw closes the screen instead of leaving input silently missing.
        screen.inputGate(UiInputGate.monitor(view.instance(), GameUiInput.CAPABILITIES));
        screen.inputGate().poll(Locale.getDefault());
        return Optional.of(screen);
    }

    /**
     * Adopts an already built view (the developer overlay, tests): it joins the stack and the
     * frame/close lifecycle like a shipped screen.
     */
    public DocumentScreen adopt(String id, UiDocumentView view, DocumentScreen.Options options) {
        DocumentScreen screen = reserve(id, options);
        adopt(screen, view);
        return screen;
    }

    /**
     * A screen not yet showing anything: build its view with {@link #services(DocumentScreen)}
     * (so its scripts' close/navigate reach this host), then {@link #adopt(DocumentScreen, UiDocumentView)}.
     */
    public DocumentScreen reserve(String id, DocumentScreen.Options options) {
        return new DocumentScreen(id, options);
    }

    /** Installs a {@link #reserve reserved} screen's view. */
    public void adopt(DocumentScreen screen, UiDocumentView view) {
        if (screen.view() != null) {
            throw new IllegalStateException("screen " + screen.id() + " already has a view");
        }
        install(screen, view);
    }

    /** Scripted clicks for screenshot runs ({@code UiAutoClick}), ticked with the screen's frame. */
    public void attachAutoClick(DocumentScreen screen, com.openmason.engine.ui.runtime.input.UiAutoClick clicks) {
        attachAutoClick(screen, clicks, null);
    }

    /**
     * As above; the schedule only advances while {@code armed} holds (the dev overlay waits for a
     * running world, so a scheduled click never fires a pause action during the intro or menus).
     */
    public void attachAutoClick(DocumentScreen screen, com.openmason.engine.ui.runtime.input.UiAutoClick clicks,
                                java.util.function.BooleanSupplier armed) {
        screen.autoClick(clicks);
        screen.autoClickArmed(armed);
    }

    /** Script services bound to {@code screen}: close and navigate queue on this host. */
    public UiScriptServices services(DocumentScreen screen) {
        return new GameUiScriptServices(() -> requestClose(screen),
            (target, args) -> requestNavigate(screen, target, args));
    }

    private void install(DocumentScreen screen, UiDocumentView view) {
        screen.attach(view);
        GameUiInput input = GameUiInput.get();
        if (!input.isOpen(view)) {
            input.open(view, screen.options().layer());
        }
        input.setClaimsKeyboard(view, screen.options().claimsKeyboard());
        input.setClaimsGamepad(view, screen.options().claimsGamepad());
        screens.add(screen);
        byView.put(view, screen);
        refreshPointerCapture();
        LOGGER.info("[ui-screen] opened {} ({})", screen.id(), screen.options().layer());
    }

    // ── queries ────────────────────────────────────────────────────────────

    public Optional<DocumentScreen> find(String id) {
        for (DocumentScreen s : screens) {
            if (s.id().equals(id) && !s.isClosing()) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public boolean isOpen(String id) {
        return find(id).isPresent();
    }

    /** Open screens, oldest first. */
    public List<DocumentScreen> screens() {
        return List.copyOf(screens);
    }

    /** True while an open screen needs a free cursor. */
    public boolean needsPointer() {
        for (DocumentScreen s : screens) {
            if (!s.isClosed() && s.options().releasesPointer()) {
                return true;
            }
        }
        return false;
    }

    // ── requests (performed at frame end) ──────────────────────────────────

    /** Queues closing {@code screen}; it happens in {@link #endFrame()}. */
    public void requestClose(DocumentScreen screen) {
        if (screen != null && screen.requestClose()) {
            pending.add(() -> closeNow(screen));
        }
    }

    /** Queues closing the screen {@code id}, if open. */
    public void requestClose(String id) {
        find(id).ifPresent(this::requestClose);
    }

    /**
     * Queues navigation from {@code from} (null: from no screen) to {@code target}.
     *
     * @return false when {@code target} is neither a document screen nor a legacy target
     */
    public boolean requestNavigate(DocumentScreen from, String target, UiValue.Obj args) {
        if (target == null || target.isBlank()) {
            return false;
        }
        boolean document = policy.useDocument(target);
        if (!document && !LegacyNavigation.has(target)) {
            return false;
        }
        boolean push = args != null && args.get("push") instanceof UiValue.Bool b && b.value();
        pending.add(() -> {
            if (from != null && !push) {
                closeNow(from);
            }
            if (document) {
                DocumentScreen.Options opts = from != null ? from.options().withOnClosed(null)
                    : DocumentScreen.Options.screen();
                if (open(target, opts).isPresent()) {
                    return;
                }
            }
            LegacyNavigation.go(target, args);
        });
        return true;
    }

    /** Any thread: the player left the world; per-world screens close at the next frame end. */
    public void worldLeft() {
        worldLeft = true;
    }

    private void sessionChanged(MultiplayerSession.Mode mode) {
        if (mode == MultiplayerSession.Mode.MENU) {
            worldLeft();
        }
    }

    // ── frame ──────────────────────────────────────────────────────────────

    /** Advances every open screen by this frame's {@link UiFrameClock} and runs its scripts. */
    public void frame() {
        if (screens.isEmpty()) {
            return;
        }
        UiFrameClock clock = UiFrameClock.get();
        for (DocumentScreen s : List.copyOf(screens)) {
            if (s.isClosing() || s.isClosed()) {
                continue;
            }
            UiInputGate.Monitor gate = s.inputGate();
            if (gate != null && !gate.poll(Locale.getDefault()).isEmpty()) {
                reportRefusal(s.id(), new IllegalStateException("input gate: "
                    + gate.poll(Locale.getDefault()).stream().map(UiInputGate.Block::reason).toList()));
                requestClose(s);
                continue;
            }
            try {
                GameUiDocuments.frame(s.view(), clock.uiDt(), clock.gameDt());
                if (s.autoClick() != null && s.autoClickArmed()) {
                    s.autoClick().tick(s.view().instance(), s.view().input(), clock.uiDt());
                }
            } catch (RuntimeException e) {
                LOGGER.error("[ui-screen] {} failed its frame; closing it", s.id(), e);
                requestClose(s);
            }
        }
    }

    /** Draws the open screens bottom to top, in the same order input is routed (reversed). */
    public void render(MasonryBackend backend, int width, int height, float uiScale) {
        if (screens.isEmpty() || backend == null || !backend.isAvailable()) {
            return;
        }
        for (UiDocumentView v : GameUiInput.get().views()) {
            DocumentScreen s = byView.get(v);
            if (s == null || s.isClosed() || s.options().ownerPaints()) {
                continue;
            }
            paint(s, backend, width, height, uiScale);
        }
    }

    /**
     * Paints one screen now, as one Masonry frame: what {@link #render} does per screen, and what
     * the owner of an {@link DocumentScreen.Options#ownerPaints ownerPaints} screen calls where its
     * legacy screen used to draw. A screen that throws is closed at frame end.
     *
     * @return false when nothing was painted (closed, or no backend)
     */
    public boolean paint(DocumentScreen s, MasonryBackend backend, int width, int height, float uiScale) {
        if (s == null || s.isClosed() || s.view() == null || backend == null || !backend.isAvailable()) {
            return false;
        }
        if (s.masonry() == null) {
            s.masonry(new MasonryUI(backend));
        }
        try {
            GameUiDocuments.render(s.view(), s.masonry(), width, height, uiScale);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("[ui-screen] {} failed to render; closing it", s.id(), e);
            requestClose(s);
            return false;
        }
    }

    /** Performs the closes and navigations requested during this frame (after it was drawn). */
    public void endFrame() {
        if (worldLeft) {
            worldLeft = false;
            for (DocumentScreen s : List.copyOf(screens)) {
                if (s.options().perWorld()) {
                    requestClose(s);
                }
            }
        }
        int ops = 0;
        Runnable op;
        while ((op = pending.poll()) != null) {
            if (++ops > MAX_OPS_PER_FRAME) {
                pending.addFirst(op); // a navigation loop: finish next frame instead of hanging this one
                LOGGER.warn("[ui-screen] more than {} screen operations in one frame; deferring the rest",
                    MAX_OPS_PER_FRAME);
                break;
            }
            try {
                op.run();
            } catch (RuntimeException e) {
                LOGGER.error("[ui-screen] screen operation failed", e);
            }
        }
    }

    /** Closes every screen now (shutdown). */
    public void closeAll() {
        for (DocumentScreen s : List.copyOf(screens)) {
            closeNow(s);
        }
        pending.clear();
    }

    private void closeNow(DocumentScreen screen) {
        if (screen.isClosed()) {
            return;
        }
        screen.requestClose();
        screen.markClosed();
        screens.remove(screen);
        UiDocumentView view = screen.view();
        if (view != null) {
            byView.remove(view);
            GameUiDocuments.close(view);
        }
        if (screen.masonry() != null) {
            screen.masonry().dispose();
            screen.masonry(null);
        }
        refreshPointerCapture();
        LOGGER.info("[ui-screen] closed {}", screen.id());
        Runnable after = screen.options().onClosed();
        if (after != null) {
            after.run();
        }
    }

    private static void refreshPointerCapture() {
        try {
            Game game = Game.getInstance();
            MouseCaptureManager capture = game == null ? null : game.getMouseCaptureManager();
            if (capture != null) {
                capture.updateCaptureState();
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[ui-screen] no pointer capture to refresh: {}", e.toString());
        }
    }

    private static void reportRefusal(String id, Throwable e) {
        LOGGER.error("[ui-screen] cannot open {}; keeping the legacy screen", id, e);
        try {
            Game game = Game.getInstance();
            ChatSystem chat = game == null ? null : game.getChatSystem();
            if (chat != null) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                chat.addMessage("[UI] " + id + " refused: " + msg, RED);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // headless: the log line is the report
        }
    }
}
