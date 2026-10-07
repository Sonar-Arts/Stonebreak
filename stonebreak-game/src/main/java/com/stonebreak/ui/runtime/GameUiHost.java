package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionHandler;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.EditPolicy;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.ui.PauseMenuActions;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.contracts.ContainerSlots;
import com.stonebreak.ui.runtime.contracts.GlossaryView;
import com.stonebreak.ui.runtime.contracts.LoadingRecord;
import com.stonebreak.ui.runtime.contracts.MainMenuContracts;
import com.stonebreak.ui.runtime.contracts.MultiplayerContracts;
import com.stonebreak.ui.runtime.contracts.SettingsMenuContracts;
import com.stonebreak.ui.runtime.contracts.WorldSelectContracts;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.runtime.contracts.SlotGridSource;
import com.stonebreak.ui.runtime.contracts.SlotRecords;
import com.stonebreak.ui.runtime.contracts.StatsRecord;
import com.stonebreak.ui.runtime.contracts.Vitals;
import com.stonebreak.ui.settingsMenu.managers.SettingsEffects;
import com.stonebreak.ui.settingsMenu.managers.SettingsManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Stonebreak's implementation of the UI host contract (#289): the live data sources and game
 * actions every migrated screen (and the {@code -Dstonebreak.uidoc} overlay) binds to. The
 * editor preview offers the same contracts from fixtures ({@code FixtureHost}; the canonical
 * fixture is the {@code ui/fixtures/game-host.fixture.json} resource, kept equal to these
 * schemas by {@code GameHostFixtureParityTest}), so a document behaves the same in both.
 *
 * <table>
 *   <caption>Contracts</caption>
 *   <tr><th>Contract</th><th>Offers</th></tr>
 *   <tr><td>{@code stonebreak:session} 1</td><td>root {@code session}: {@code mode}, {@code online},
 *       {@code hosting}; follows {@link MultiplayerSession} mode changes</td></tr>
 *   <tr><td>{@code stonebreak:furnace} 3</td><td>root {@code furnace}: the open furnace's {@code open},
 *       {@code lit}, {@code progress} and {@code fuel} (0–1); since 2 its {@code ingredientSlot},
 *       {@code fuelSlot} and {@code outputSlot} stacks; since 3 {@code cooking} (a smelt is under way:
 *       the furnace screen's crucible glows while cooking or with fuel left, #298)</td></tr>
 *   <tr><td>{@code stonebreak:inventory} 1</td><td>collection {@code inventory} ({@code main:0..26}),
 *       root {@code carried} (the stack on the cursor); slot actions {@code stonebreak:inventory.slot-click},
 *       {@code .slot-press}, {@code .slot-drag}, {@code .slot-release}, {@code .sort}, {@code .craft-all},
 *       {@code .close} acting on the open container screen</td></tr>
 *   <tr><td>{@code stonebreak:hotbar} 1</td><td>collection {@code hotbar} ({@code hotbar:0..8}, the
 *       selected one flagged {@code selected}); {@code stonebreak:hotbar.select}</td></tr>
 *   <tr><td>{@code stonebreak:player.vitals} 1</td><td>root {@code vitals}: health, stamina, mana
 *       (and their maxima), level, xp, xpNext, dead, present</td></tr>
 *   <tr><td>{@code stonebreak:settings} 2</td><td>editable root {@code settings} with every player
 *       setting ({@link SettingsContract}), applied by {@code stonebreak:settings.apply}; since 2
 *       {@code .set-live}, {@code .keep-ui-scale}, {@code .revert-ui-scale}</td></tr>
 *   <tr><td>{@code stonebreak:screen.pause} 1</td><td>{@code stonebreak:screen.pause.resume},
 *       {@code .statistics}, {@code .glossary}, {@code .settings}, {@code .quit}</td></tr>
 *   <tr><td>{@code stonebreak:player.stats} 1</td><td>root {@code stats}: the local player's activity
 *       statistics ({@link StatsRecord}) and each written as the statistics screen writes it ({@code text})</td></tr>
 *   <tr><td>{@code stonebreak:screen.statistics} 1</td><td>{@code stonebreak:screen.statistics.back}</td></tr>
 *   <tr><td>{@code stonebreak:screen.loading} 1</td><td>root {@code loading}: {@code stage}, {@code progress}
 *       (0-1) and {@code percent} of the world loading screen ({@link LoadingRecord})</td></tr>
 *   <tr><td>{@code stonebreak:screen.glossary} 1</td><td>roots {@code glossary} (with the sidebar's
 *       {@code entries}), {@code glossaryAbilities} ({@link GlossaryView}, published while the glossary is open);
 *       {@code .select {index}}, {@code .cycle {delta}}, {@code .back}</td></tr>
 *   <tr><td>{@code stonebreak:screen.death} 1</td><td>{@code stonebreak:screen.death.respawn} (refused
 *       unless the death menu is up, #299)</td></tr>
 *   <tr><td>{@code stonebreak:screen.multiplayer}, {@code .host-world}, {@code .join-world} 1</td><td>the
 *       multiplayer screens ({@link MultiplayerContracts})</td></tr>
 *   <tr><td>{@code stonebreak:screen.main-menu} 1</td><td>the main menu ({@link MainMenuContracts})</td></tr>
 *   <tr><td>{@code stonebreak:screen.settings} 1</td><td>the settings screen's widgets
 *       ({@link SettingsMenuContracts}; the values themselves are {@code stonebreak:settings})</td></tr>
 *   <tr><td>{@code stonebreak:screen.world-select} 1</td><td>the world select screen
 *       ({@link WorldSelectContracts})</td></tr>
 *   <tr><td>{@code stonebreak:network.resync} 1</td><td>{@code stonebreak:network.resync} →
 *       {@code {audited}}</td></tr>
 * </table>
 *
 * <p>Producers on other threads (the integrated server ticking furnaces, network echoes) only
 * request a refresh; values are read and published on the UI thread when the frame loop
 * {@link #drain}s, so a value from a furnace that has since been closed or replaced can never
 * land on the cell. Item stacks change in place everywhere in the game, with no event to hook,
 * so slot grids, the carried stack and vitals are compared against a primitive snapshot each
 * drain and republished only when they changed. Leaving a world or disconnecting advances the
 * host epoch, which cancels every pending action of every open screen and resets the per-world
 * roots to their empty values.
 */
public final class GameUiHost {

    public static final HostContract SESSION = HostContract.of("stonebreak:session", 1);
    public static final HostContract FURNACE = HostContract.of("stonebreak:furnace", 3);
    public static final HostContract SETTINGS = HostContract.of("stonebreak:settings", 2);
    public static final HostContract PAUSE = HostContract.of("stonebreak:screen.pause", 1);
    public static final HostContract STATS = HostContract.of("stonebreak:player.stats", 1);
    public static final HostContract STATISTICS = HostContract.of("stonebreak:screen.statistics", 1);
    public static final HostContract LOADING = HostContract.of("stonebreak:screen.loading", 1);
    public static final HostContract GLOSSARY = HostContract.of("stonebreak:screen.glossary", 1);
    public static final HostContract DEATH = HostContract.of("stonebreak:screen.death", 1);
    public static final HostContract RESYNC = HostContract.of("stonebreak:network.resync", 1);
    public static final HostContract INVENTORY = HostContract.of("stonebreak:inventory", 1);
    public static final HostContract HOTBAR = HostContract.of("stonebreak:hotbar", 1);
    public static final HostContract VITALS = HostContract.of("stonebreak:player.vitals", 1);

    /**
     * Draw providers the game implements, id → version, declared in the host profile so the
     * editor checks exports against them. The same table the implementations are checked against
     * ({@code providers.GameDrawProviders}): item icons and 3D model previews.
     */
    public static final Map<String, Integer> PROVIDERS = com.stonebreak.ui.runtime.providers.GameDrawProviders.DECLARED;

    /**
     * Classpath resource of the canonical preview fixture for every contract above
     * ({@code FixtureHost} format): what the editor preview binds to when a document has no
     * fixture of its own. {@code GameHostFixtureParityTest} keeps it true to these schemas.
     */
    public static final String FIXTURE_RESOURCE = "ui/fixtures/game-host.fixture.json";

    /**
     * The bytes of {@link #FIXTURE_RESOURCE}. Read here because module resources are only visible
     * to their own module: the editor (another module) asks this method instead of the classpath.
     */
    public static byte[] fixtureJson() {
        try (java.io.InputStream in = GameUiHost.class.getResourceAsStream("/" + FIXTURE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(FIXTURE_RESOURCE + " is missing from the game resources");
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static final DataType.Obj SESSION_TYPE = DataType.object("mode", DataType.string(), "online", DataType.bool(),
        "hosting", DataType.bool());
    static final DataType.Obj FURNACE_TYPE = DataType.object("open", DataType.bool(), "lit", DataType.bool(),
        "progress", DataType.number(), "fuel", DataType.number(), "ingredientSlot", SlotRecords.STACK,
        "fuelSlot", SlotRecords.STACK, "outputSlot", SlotRecords.STACK, "cooking", DataType.bool());
    static final DataType.Obj SETTINGS_TYPE = SettingsContract.TYPE;

    private static final DataType.Obj SLOT_ARGS = DataType.object("slot", DataType.string(),
        "button", DataType.integer().orNull(), "shift", DataType.bool().orNull());
    private static final DataType.Obj DRAG_ARGS = DataType.object("slot", DataType.string(),
        "button", DataType.integer().orNull());
    private static final DataType.Obj APPLY_RESULT = DataType.object("uiScaleChanged", DataType.bool(),
        "previousUiScale", DataType.number());

    /** The game behind the host. Tests substitute it; nothing here reaches game singletons directly. */
    public interface Services extends MultiplayerContracts.Services, MainMenuContracts.Services,
            WorldSelectContracts.Services, SettingsMenuContracts.Services {
        void resume();

        void openStatistics();

        void openGlossary();

        void openSettings();

        void quitToMenu();

        /**
         * The death menu's Respawn.
         *
         * @return null, or why it was refused (no death menu showing)
         */
        default String respawn() {
            return "no death menu is showing";
        }

        /** @return chunks audited, or -1 when not connected */
        int resync();

        /** Every field of {@link SettingsContract}. */
        UiValue.Obj settings();

        /**
         * Writes the present fields of an already validated (partial) settings object, pushes them to
         * the game systems and saves, holding a UI-scale change for confirmation (applied, not saved
         * until {@link #keepUiScale}) like the legacy menu's Apply.
         */
        void applySettings(UiValue.Obj value);

        /** @return null, or why {@code field} cannot be set before Apply */
        default String setLiveSetting(String field, UiValue value) {
            return "live settings are not available";
        }

        /** Persists the UI scale held since the last apply. */
        default void keepUiScale() {
        }

        /** Restores the UI scale from before the last apply. */
        default void revertUiScale() {
        }

        /** The open container screen's slot rules (furnace, workbench or inventory), or null. */
        default ContainerSlotInput openContainer() {
            return null;
        }

        /** Closes the open container screen the way its legacy close does (carried stack put back). */
        default void closeContainer() {
        }

        /** Pixel size the container screens lay out in. */
        default int[] screenSize() {
            return new int[]{1920, 1080};
        }

        /** The local player's inventory, or null outside a world. */
        default Inventory inventory() {
            return null;
        }

        default Vitals vitals() {
            return Vitals.NONE;
        }

        default StatsRecord stats() {
            return StatsRecord.NONE;
        }

        /** The world loading screen's progress (nothing while it is hidden). */
        default LoadingRecord loading() {
            return LoadingRecord.NONE;
        }

        /** The open glossary (its selection and data), or null while it is closed. */
        default com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen() {
            return null;
        }

        /** The glossary's Back. @return null, or why it was refused */
        default String closeGlossary() {
            return "no glossary is showing";
        }

        /** The statistics screen's Back. @return null, or why it was refused */
        default String closeStatistics() {
            return "no statistics screen is showing";
        }
    }

    private static volatile GameUiHost instance;

    private final UiHost host = new UiHost();
    private final Services services;
    private final DataCell session;
    private final DataCell furnace;
    private final DataCell settings;
    private final DataCell carried;
    private final DataCell vitals;
    private final DataCell stats;
    private final GlossaryView glossary = new GlossaryView();
    private final DataCell loading;
    private final MultiplayerContracts multiplayer;
    private final MainMenuContracts mainMenu;
    private final WorldSelectContracts worldSelect;
    private final SettingsMenuContracts settingsMenu;
    private LoadingRecord lastLoading = LoadingRecord.NONE;
    private final SlotGridSource inventory = new SlotGridSource("main");
    private final SlotGridSource hotbar = new SlotGridSource("hotbar");
    private final AtomicBoolean furnaceRefreshQueued = new AtomicBoolean();
    private FurnaceState openFurnace;
    private UiValue lastFurnace;
    private ItemStack lastCarried;
    private Vitals lastVitals = Vitals.NONE;
    private StatsRecord lastStats = StatsRecord.NONE;
    private Inventory polledInventory;
    private java.util.function.IntFunction<ItemStack> mainSlots;
    private java.util.function.IntFunction<ItemStack> hotbarSlots;

    public GameUiHost(Services services, MultiplayerSession.Mode mode) {
        this.services = services;
        session = host.data().register("session", new DataCell(SESSION_TYPE, sessionValue(mode)), SESSION);
        furnace = host.data().register("furnace", new DataCell(FURNACE_TYPE, closedFurnace()), FURNACE);
        settings = host.data().registerEditable("settings", new DataCell(SETTINGS_TYPE, settingsValue()), SETTINGS,
            new EditPolicy("stonebreak:settings.apply", GameUiHost::settingsProblem));
        host.data().register("inventory", inventory.collection(), INVENTORY);
        carried = host.data().register("carried", new DataCell(SlotRecords.STACK, SlotRecords.emptyStack()), INVENTORY);
        host.data().register("hotbar", hotbar.collection(), HOTBAR);
        vitals = host.data().register("vitals", new DataCell(Vitals.TYPE, Vitals.NONE.value()), VITALS);
        stats = host.data().register("stats", new DataCell(StatsRecord.TYPE, StatsRecord.NONE.value()), STATS);
        loading = host.data().register("loading", new DataCell(LoadingRecord.TYPE, LoadingRecord.NONE.value()), LOADING);
        host.data().register("glossary", glossary.root(), GLOSSARY);
        multiplayer = new MultiplayerContracts(host, services);
        mainMenu = new MainMenuContracts(host, services);
        worldSelect = new WorldSelectContracts(host, services);
        settingsMenu = new SettingsMenuContracts(host, services);
        host.data().register("glossaryAbilities", glossary.abilities(), GLOSSARY);
        PROVIDERS.forEach(host::offerProvider);
        registerActions();
    }

    /** The game's host, created on first use (the first frame). */
    public static GameUiHost get() {
        GameUiHost h = instance;
        if (h == null) {
            synchronized (GameUiHost.class) {
                h = instance;
                if (h == null) {
                    h = new GameUiHost(new GameServices(), MultiplayerSession.getMode());
                    GameUiHost created = h;
                    MultiplayerSession.addModeListener(created::sessionChanged);
                    Settings.getInstance().addSaveListener(created::settingsSaved);
                    instance = h;
                }
            }
        }
        return h;
    }

    /**
     * What the game's host offers — every contract, data root type, action and provider —
     * without a running game (C15): the editor checks exports and fixtures against it.
     */
    public static UiHostProfile declaredProfile() {
        return declaration().host().profile();
    }

    /** A host with every contract registered and no game behind it (declarations, schema checks). */
    public static GameUiHost declaration() {
        return new GameUiHost(new Declaration(), MultiplayerSession.Mode.MENU);
    }

    /** Runs {@code action} with the host if it exists; game code paths that run headless in tests use this. */
    public static void ifPresent(Consumer<GameUiHost> action) {
        GameUiHost h = instance;
        if (h != null) {
            action.accept(h);
        }
    }

    public UiHost host() {
        return host;
    }

    /**
     * Once per frame on the UI thread: publishes what changed in the game since the last frame,
     * then runs posted work (cross-thread data, async action completions).
     */
    public int drain() {
        pollLive();
        return host.drain();
    }

    // ── sources ─────────────────────────────────────────────────────────────

    /** Republishes {@code session} for the current mode, e.g. after {@code UiOnlineState.override} (#296). */
    public void refreshSession() {
        session.post(sessionValue(MultiplayerSession.getMode()));
    }

    /** Any thread. Leaving to the menu also ends the world epoch: pending actions are cancelled, world data reset. */
    public void sessionChanged(MultiplayerSession.Mode mode) {
        session.post(sessionValue(mode));
        if (mode == MultiplayerSession.Mode.MENU) {
            host.queue().post(() -> {
                host.advanceEpoch("left world");
                resetWorldData();
            });
        }
    }

    /** UI thread: the furnace UI opened on {@code state}; its changes are mirrored until {@link #furnaceClosed}. */
    public void furnaceOpened(FurnaceState state) {
        if (openFurnace != null) {
            openFurnace.setChangeListener(null);
        }
        openFurnace = Objects.requireNonNull(state, "state");
        state.setChangeListener(this::requestFurnaceRefresh);
        lastFurnace = null;
        publishFurnace();
    }

    /** UI thread: the furnace UI closed. A refresh still queued from the furnace finds nothing to publish. */
    public void furnaceClosed() {
        if (openFurnace != null) {
            openFurnace.setChangeListener(null);
            openFurnace = null;
        }
        lastFurnace = null;
        furnace.set(closedFurnace());
    }

    /**
     * The services' settings over the built-in defaults: a service (or a test double) that only
     * knows some fields still yields a complete {@code settings} root.
     */
    private UiValue.Obj settingsValue() {
        UiValue.Obj given = services.settings();
        if (given.fields().keySet().containsAll(SettingsContract.fields().keySet())) {
            return given;
        }
        Map<String, UiValue> m = new LinkedHashMap<>(SettingsContract.read(Settings.defaults()).fields());
        m.putAll(given.fields());
        return new UiValue.Obj(m);
    }

    public void settingsSaved() {
        settings.post(settingsValue());
    }

    /** Any thread (a furnace tick or a server echo): re-read the open furnace on the UI thread. */
    private void requestFurnaceRefresh() {
        if (furnaceRefreshQueued.compareAndSet(false, true)) {
            host.queue().post(() -> {
                furnaceRefreshQueued.set(false);
                publishFurnace();
            });
        }
    }

    /** UI thread: publishes the open furnace when it differs from what the cell shows. */
    private void publishFurnace() {
        FurnaceState s = openFurnace;
        if (s == null) {
            return;
        }
        UiValue.Obj v = furnaceValue(s);
        if (!v.equals(lastFurnace)) {
            lastFurnace = v;
            furnace.set(v);
        }
    }

    private void pollLive() {
        publishFurnace(); // slots change in place from the UI's own slot rules: no event for those
        Inventory inv = services.inventory();
        if (inv != null) {
            if (inv != polledInventory) { // slot readers bound once per inventory, not per frame
                polledInventory = inv;
                mainSlots = inv::getMainInventorySlot;
                hotbarSlots = inv::getHotbarSlot;
            }
            inventory.refresh(Inventory.MAIN_INVENTORY_SIZE, mainSlots, -1);
            hotbar.refresh(Inventory.HOTBAR_SIZE, hotbarSlots, inv.getSelectedHotbarSlotIndex());
        }
        ContainerSlotInput screen = services.openContainer();
        ItemStack held = screen == null ? null : screen.getDragState().draggedItemStack;
        if (!sameStack(held, lastCarried)) {
            lastCarried = held == null || held.isEmpty() ? null : held.copy();
            carried.set(SlotRecords.stack(held));
        }
        Vitals v = services.vitals();
        if (v != null && !v.equals(lastVitals)) {
            lastVitals = v;
            vitals.set(v.value());
        }
        StatsRecord st = services.stats();
        if (st != null && !st.equals(lastStats)) {
            lastStats = st;
            stats.set(st.value());
        }
        glossary.refresh(services.glossaryScreen());
        multiplayer.poll();
        mainMenu.poll();
        worldSelect.poll();
        settingsMenu.poll();
        LoadingRecord ld = services.loading();
        if (ld != null && !ld.equals(lastLoading)) {
            lastLoading = ld;
            loading.set(ld.value());
        }
    }

    /** Per-world roots back to empty (world left; nothing of the old world may stay on screen). */
    private void resetWorldData() {
        furnaceClosed();
        inventory.clear();
        hotbar.clear();
        polledInventory = null;
        lastCarried = null;
        carried.set(SlotRecords.emptyStack());
        lastVitals = Vitals.NONE;
        vitals.set(Vitals.NONE.value());
        lastStats = StatsRecord.NONE;
        stats.set(StatsRecord.NONE.value());
        glossary.clear();
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        boolean ea = a == null || a.isEmpty();
        boolean eb = b == null || b.isEmpty();
        if (ea || eb) {
            return ea == eb;
        }
        return a.getBlockTypeId() == b.getBlockTypeId() && a.getCount() == b.getCount()
            && Objects.equals(a.getState(), b.getState());
    }

    DataCell furnaceCell() {
        return furnace;
    }

    DataCell sessionCell() {
        return session;
    }

    static UiValue.Obj sessionValue(MultiplayerSession.Mode mode) {
        return new UiValue.Obj(Map.of("mode", UiValue.of(mode.name().toLowerCase(java.util.Locale.ROOT)),
            "online", UiValue.of(com.stonebreak.ui.UiOnlineState.isOnline(mode)), // overridable (#296)
            "hosting", UiValue.of(mode == MultiplayerSession.Mode.HOST)));
    }

    static UiValue.Obj furnaceValue(FurnaceState s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("open", UiValue.TRUE);
        m.put("lit", UiValue.of(s.isLit()));
        m.put("progress", SettingsContract.exact(Math.clamp(s.getCookProgressRatio(), 0f, 1f)));
        m.put("fuel", SettingsContract.exact(s.getFuelRatio()));
        m.put("ingredientSlot", SlotRecords.stack(s.getIngredient()));
        m.put("fuelSlot", SlotRecords.stack(s.getFuel()));
        m.put("outputSlot", SlotRecords.stack(s.getOutput()));
        m.put("cooking", UiValue.of(s.isCooking()));
        return new UiValue.Obj(m);
    }

    private static UiValue.Obj closedFurnace() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("open", UiValue.FALSE);
        m.put("lit", UiValue.FALSE);
        m.put("progress", UiValue.of(0));
        m.put("fuel", UiValue.of(0));
        m.put("ingredientSlot", SlotRecords.emptyStack());
        m.put("fuelSlot", SlotRecords.emptyStack());
        m.put("outputSlot", SlotRecords.emptyStack());
        m.put("cooking", UiValue.FALSE);
        return new UiValue.Obj(m);
    }

    /** The same ranges the settings setters clamp to, refused up front instead of silently clamped. */
    static String settingsProblem(DataPath path, UiValue value, UiValue draft) {
        String p = path.toString();
        return p.startsWith("settings.") ? SettingsContract.problem(p.substring("settings.".length()), value) : null;
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private void registerActions() {
        pause("resume", services::resume);
        pause("statistics", services::openStatistics);
        pause("glossary", services::openGlossary);
        pause("settings", services::openSettings);
        pause("quit", services::quitToMenu);
        screenAction(DEATH, "respawn", services::respawn);
        screenAction(STATISTICS, "back", services::closeStatistics);
        screenAction(GLOSSARY, "back", services::closeGlossary);
        glossaryAction("select", DataType.object("index", DataType.integer()),
            (g, args) -> g.select(integer(args, "index", -1)) ? null : "no glossary row " + integer(args, "index", -1));
        glossaryAction("cycle", DataType.object("delta", DataType.integer()),
            (g, args) -> g.cycleVariant(Integer.signum(integer(args, "delta", 1)))
                ? null : "this entity has fewer than two discovered variants");
        host.actions().register(ActionSpec.of("stonebreak:network.resync", RESYNC, null,
            DataType.object("audited", DataType.integer())), (args, ctx) -> done(new UiValue.Obj(
                Map.of("audited", UiValue.of(services.resync())))));
        registerSettingsActions();
        registerSlotActions();
    }

    private void registerSettingsActions() {
        host.actions().register(ActionSpec.of("stonebreak:settings.apply", SETTINGS,
            DataType.object("value", SettingsContract.PARTIAL), APPLY_RESULT), (args, ctx) -> {
                UiValue.Obj value = (UiValue.Obj) args.get("value");
                String problem = SettingsContract.problem(value);
                if (problem != null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(problem));
                }
                UiValue previousScale = settingsValue().get("uiScale");
                services.applySettings(value);
                UiValue requested = value.get("uiScale");
                boolean scaleChanged = requested instanceof UiValue.Num && !requested.equals(previousScale);
                settings.set(settingsValue());
                return done(new UiValue.Obj(Map.of("uiScaleChanged", UiValue.of(scaleChanged),
                    "previousUiScale", previousScale == null ? UiValue.of(1) : previousScale)));
            });
        host.actions().register(new ActionSpec("stonebreak:settings.set-live", SETTINGS, 2,
            DataType.object("field", DataType.string(), "value", DataType.ANY), DataType.ANY,
            ActionSpec.Reentrancy.PARALLEL, false), (args, ctx) -> {
                String field = ((UiValue.Str) args.get("field")).value();
                String problem = services.setLiveSetting(field, args.get("value"));
                if (problem != null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(problem));
                }
                settings.set(settingsValue());
                return done(UiValue.NULL);
            });
        host.actions().register(new ActionSpec("stonebreak:settings.keep-ui-scale", SETTINGS, 2, null, DataType.ANY,
            ActionSpec.Reentrancy.REJECT_WHILE_PENDING, false), (args, ctx) -> {
                services.keepUiScale();
                settings.set(settingsValue());
                return done(UiValue.NULL);
            });
        host.actions().register(new ActionSpec("stonebreak:settings.revert-ui-scale", SETTINGS, 2, null, DataType.ANY,
            ActionSpec.Reentrancy.REJECT_WHILE_PENDING, false), (args, ctx) -> {
                services.revertUiScale();
                settings.set(settingsValue());
                return done(UiValue.NULL);
            });
    }

    private void registerSlotActions() {
        slotAction("slot-click", SLOT_ARGS, (screen, args, size) -> ContainerSlots.click(screen, str(args, "slot"),
            integer(args, "button", ContainerSlots.LEFT), bool(args, "shift"), size[0], size[1]));
        slotAction("slot-press", SLOT_ARGS, (screen, args, size) -> ContainerSlots.press(screen, str(args, "slot"),
            integer(args, "button", ContainerSlots.LEFT), bool(args, "shift"), size[0], size[1]));
        slotAction("slot-drag", DRAG_ARGS, (screen, args, size) -> ContainerSlots.drag(screen, str(args, "slot"),
            integer(args, "button", ContainerSlots.RIGHT), size[0], size[1]));
        slotAction("slot-release", null, (screen, args, size) -> {
            ContainerSlots.release(screen, size[0], size[1]);
            return null;
        });
        slotAction("sort", null, (screen, args, size) -> screen.sort() ? null : "this screen cannot sort");
        slotAction("craft-all", null, (screen, args, size) -> screen.craftAll() ? null : "this screen cannot craft");
        host.actions().register(ActionSpec.of("stonebreak:inventory.close", INVENTORY, null, DataType.ANY),
            (args, ctx) -> {
                services.closeContainer();
                return done(UiValue.NULL);
            });
        host.actions().register(ActionSpec.of("stonebreak:hotbar.select", HOTBAR,
            DataType.object("index", DataType.integer()), DataType.ANY).withReentrancy(ActionSpec.Reentrancy.PARALLEL),
            (args, ctx) -> {
                int i = integer(args, "index", -1);
                Inventory inv = services.inventory();
                if (inv == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("not in a world"));
                }
                if (i < 0 || i >= Inventory.HOTBAR_SIZE) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "hotbar index must be 0-" + (Inventory.HOTBAR_SIZE - 1)));
                }
                inv.setSelectedHotbarSlotIndex(i);
                return done(UiValue.NULL);
            });
    }

    /** One slot action on the open container screen; the rule returns null or why it refused. */
    @FunctionalInterface
    private interface SlotRule {
        String run(ContainerSlotInput screen, UiValue.Obj args, int[] size);
    }

    private void slotAction(String name, DataType.Obj params, SlotRule rule) {
        // Slot clicks complete synchronously and players click fast: never refuse a click as a duplicate.
        host.actions().register(ActionSpec.of("stonebreak:inventory." + name, INVENTORY, params, DataType.ANY)
            .withReentrancy(ActionSpec.Reentrancy.PARALLEL), (args, ctx) -> {
                ContainerSlotInput screen = services.openContainer();
                if (screen == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("no container screen is open"));
                }
                String problem = rule.run(screen, args, services.screenSize());
                if (problem != null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(problem));
                }
                pollLive(); // the document sees the result before the next frame
                return done(UiValue.NULL);
            });
    }

    private void pause(String name, Runnable run) {
        ActionHandler h = (args, ctx) -> {
            run.run();
            return done(UiValue.NULL);
        };
        host.actions().register(ActionSpec.of("stonebreak:screen.pause." + name, PAUSE, null, DataType.ANY), h);
    }

    /** A no-argument screen action ({@code <contract id>.<name>}); the service returns null or why it refused. */
    private void screenAction(HostContract contract, String name, java.util.function.Supplier<String> run) {
        host.actions().register(ActionSpec.of(contract.id() + "." + name, contract, null, DataType.ANY), (args, ctx) -> {
            String problem = run.get();
            return problem == null ? done(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    /** An action on the open glossary's selection; the rule returns null or why it refused. */
    private void glossaryAction(String name, DataType.Obj params,
                                java.util.function.BiFunction<com.stonebreak.ui.glossaryScreen.GlossaryScreen, UiValue.Obj, String> rule) {
        host.actions().register(ActionSpec.of(GLOSSARY.id() + "." + name, GLOSSARY, params, DataType.ANY)
            .withReentrancy(ActionSpec.Reentrancy.PARALLEL), (args, ctx) -> {
                var screen = services.glossaryScreen();
                if (screen == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("no glossary is showing"));
                }
                String problem = rule.apply(screen, args);
                if (problem != null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(problem));
                }
                glossary.refresh(screen); // the document sees the new selection before the next frame
                return done(UiValue.NULL);
            });
    }

    private static String str(UiValue.Obj args, String k) {
        return args.get(k) instanceof UiValue.Str s ? s.value() : "";
    }

    private static int integer(UiValue.Obj args, String k, int fallback) {
        return args.get(k) instanceof UiValue.Num n ? (int) n.value() : fallback;
    }

    private static boolean bool(UiValue.Obj args, String k) {
        return args.get(k) instanceof UiValue.Bool b && b.value();
    }

    private static CompletableFuture<UiValue> done(UiValue v) {
        return CompletableFuture.completedFuture(v);
    }

    /** No game: defaults only. Backs {@link #declaredProfile()}. */
    private static final class Declaration implements Services {
        @Override public void resume() { }
        @Override public void openStatistics() { }
        @Override public void openGlossary() { }
        @Override public void openSettings() { }
        @Override public void quitToMenu() { }
        @Override public int resync() { return -1; }

        @Override
        public UiValue.Obj settings() {
            return SettingsContract.read(Settings.defaults());
        }

        @Override
        public void applySettings(UiValue.Obj value) {
            throw new IllegalStateException("declaration host: no game");
        }
    }

    /** The live game. */
    private static final class GameServices implements Services {
        /** UI scale from before the last apply that changed it, until kept or reverted. */
        private Float uiScaleBeforeApply;

        @Override
        public void resume() {
            if (inWorld("resume")) {
                PauseMenuActions.resume(Game.getInstance());
            }
        }

        @Override
        public void openStatistics() {
            if (inWorld("statistics")) {
                PauseMenuActions.openStatistics(Game.getInstance());
            }
        }

        @Override
        public void openGlossary() {
            if (inWorld("glossary")) {
                PauseMenuActions.openGlossary(Game.getInstance());
            }
        }

        @Override
        public void openSettings() {
            if (inWorld("settings")) {
                PauseMenuActions.openSettings(Game.getInstance());
            }
        }

        @Override
        public void quitToMenu() {
            if (inWorld("quit")) {
                PauseMenuActions.quitToMenu(Game.getInstance());
            }
        }

        @Override
        public int resync() {
            return inWorld("resync") ? PauseMenuActions.resync(Game.getInstance()) : -1;
        }

        @Override
        public String respawn() {
            Game game = Game.getInstance();
            com.stonebreak.ui.DeathMenu death = game == null ? null : game.getDeathMenu();
            if (death == null || !death.isVisible()) {
                return "no death menu is showing";
            }
            com.stonebreak.ui.DeathMenuActions.respawn(game);
            return null;
        }

        /**
         * The pause-menu actions toggle the in-world pause state; outside a world (intro, menus,
         * loading) they would push the game into PAUSED and wedge it there, so they refuse.
         */
        private static boolean inWorld(String action) {
            if (Game.getWorld() != null && Game.getPlayer() != null) {
                return true;
            }
            org.slf4j.LoggerFactory.getLogger(GameUiHost.class)
                .info("[ui-host] pause action '{}' ignored: not in a world", action);
            return false;
        }

        @Override
        public UiValue.Obj settings() {
            return SettingsContract.read(Settings.getInstance());
        }

        /** The legacy Apply: everything but a UI-scale change is written, pushed and saved; the scale waits. */
        @Override
        public void applySettings(UiValue.Obj value) {
            Settings s = Settings.getInstance();
            float previous = s.getUiScale();
            UiValue requested = value.get("uiScale");
            Map<String, UiValue> rest = new LinkedHashMap<>(value.fields());
            rest.remove("uiScale");
            SettingsContract.write(s, new UiValue.Obj(rest));
            s.saveSettings(); // still records the previous scale
            SettingsManager manager = new SettingsManager(s);
            manager.applyAudioSettings();
            manager.applyCrosshairSettings();
            manager.applyDisplaySettings();
            manager.applyWorldDistanceSettings();
            boolean scaleChanged = requested instanceof UiValue.Num n && Math.abs((float) n.value() - previous) >= 0.001f;
            if (scaleChanged) {
                uiScaleBeforeApply = previous;
                s.setUiScale((float) ((UiValue.Num) requested).value());
            }
        }

        @Override
        public String setLiveSetting(String field, UiValue value) {
            return SettingsContract.setLive(Settings.getInstance(), field, value, GameServices::pushed);
        }

        private static void pushed(String field, Settings s) {
            switch (field) {
                case "musicVolume" -> SettingsEffects.musicVolume(s.getMusicVolume());
                case "musicEnabled" -> SettingsEffects.musicEnabled(s.getMusicEnabled());
                case "lodEnabled" -> SettingsEffects.lodEnabled(s.getLodEnabled());
                case "lodQuality" -> SettingsEffects.lodQuality(s.getLodQuality());
                case "vsyncEnabled" -> SettingsEffects.vsync();
                case "leafTransparency" -> SettingsEffects.leafTransparency();
                case "smoothLightingEnabled" -> SettingsEffects.smoothLighting();
                default -> { }
            }
        }

        @Override
        public void keepUiScale() {
            if (uiScaleBeforeApply != null) {
                uiScaleBeforeApply = null;
                Settings.getInstance().saveSettings();
            }
        }

        @Override
        public void revertUiScale() {
            if (uiScaleBeforeApply != null) {
                Settings.getInstance().setUiScale(uiScaleBeforeApply); // disk already holds it
                uiScaleBeforeApply = null;
            }
        }

        @Override
        public ContainerSlotInput openContainer() {
            Game game = Game.getInstance();
            if (game == null) {
                return null;
            }
            if (game.getFurnaceScreen() != null && game.getFurnaceScreen().isVisible()) {
                return game.getFurnaceScreen().getController().getInputManager();
            }
            if (game.getWorkbenchScreen() != null && game.getWorkbenchScreen().isVisible()) {
                return game.getWorkbenchScreen().getSlotInput();
            }
            if (game.getInventoryScreen() != null && game.getInventoryScreen().isVisible()) {
                return game.getInventoryScreen().getSlotInput();
            }
            return null;
        }

        @Override
        public void closeContainer() {
            Game game = Game.getInstance();
            if (game.getFurnaceScreen() != null && game.getFurnaceScreen().isVisible()) {
                game.closeFurnaceScreen();
            } else if (game.getWorkbenchScreen() != null && game.getWorkbenchScreen().isVisible()) {
                game.closeWorkbenchScreen();
            } else if (game.getInventoryScreen() != null && game.getInventoryScreen().isVisible()) {
                game.toggleInventoryScreen();
            }
        }

        @Override
        public int[] screenSize() {
            return new int[]{Game.getWindowWidth(), Game.getWindowHeight()};
        }

        @Override
        public Inventory inventory() {
            Player p = Game.getPlayer();
            return p == null ? null : p.getInventory();
        }

        @Override
        public Vitals vitals() {
            return Vitals.of(Game.getPlayer());
        }

        @Override
        public StatsRecord stats() {
            Player p = Game.getPlayer();
            return p == null ? StatsRecord.NONE : StatsRecord.of(p.getStats());
        }

        @Override
        public com.stonebreak.ui.MainMenu mainMenu() {
            Game game = Game.getInstance();
            return game != null && game.getState() == com.stonebreak.core.GameState.MAIN_MENU ? game.getMainMenu() : null;
        }

        @Override
        public int[] menuWindow() {
            return new int[]{Game.getWindowWidth(), Game.getWindowHeight()};
        }

        @Override
        public float menuScale() {
            return Settings.getInstance().getUiScale();
        }

        @Override
        public String mainMenuChoose(int index) {
            var menu = mainMenu();
            if (menu == null) {
                return "no main menu is showing";
            }
            menu.choose(index);
            return null;
        }

        @Override
        public String mainMenuTitle() {
            var menu = mainMenu();
            if (menu == null) {
                return "no main menu is showing";
            }
            menu.clickTitle(Game.getWindowWidth(), Game.getWindowHeight());
            return null;
        }

        @Override
        public String multiplayerChoice(String choice) {
            Game game = Game.getInstance();
            if (game == null || game.getState() != com.stonebreak.core.GameState.MULTIPLAYER_MENU) {
                return "no multiplayer menu is showing";
            }
            game.setState(switch (choice) {
                case "host" -> com.stonebreak.core.GameState.HOST_WORLD_SELECT;
                case "join" -> com.stonebreak.core.GameState.JOIN_WORLD_SCREEN;
                default -> com.stonebreak.core.GameState.MAIN_MENU;
            });
            return null;
        }

        @Override
        public String multiplayerBack() {
            Game game = Game.getInstance();
            var state = game == null ? null : game.getState();
            if (state != com.stonebreak.core.GameState.HOST_WORLD_SELECT
                    && state != com.stonebreak.core.GameState.JOIN_WORLD_SCREEN) {
                return "no multiplayer screen is showing";
            }
            game.setState(com.stonebreak.core.GameState.MULTIPLAYER_MENU);
            return null;
        }

        @Override
        public com.stonebreak.ui.multiplayerMenu.HostWorldScreen hostWorld() {
            Game game = Game.getInstance();
            return game != null && game.getState() == com.stonebreak.core.GameState.HOST_WORLD_SELECT
                ? game.getHostWorldScreen() : null;
        }

        @Override
        public String hostSelect(int index) {
            var screen = hostWorld();
            if (screen == null) {
                return "no host screen is showing";
            }
            return screen.selectWorld(index) ? null : "no world row " + index;
        }

        @Override
        public String hostStart(String port) {
            var screen = hostWorld();
            if (screen == null) {
                throw new IllegalStateException("no host screen is showing");
            }
            return screen.startHosting(port);
        }

        @Override
        public com.stonebreak.ui.multiplayerMenu.JoinWorldScreen joinWorld() {
            Game game = Game.getInstance();
            return game != null && game.getState() == com.stonebreak.core.GameState.JOIN_WORLD_SCREEN
                ? game.getJoinWorldScreen() : null;
        }

        @Override
        public String joinConnect(String host, String port, String username) {
            var screen = joinWorld();
            if (screen == null) {
                throw new IllegalStateException("no join screen is showing");
            }
            return screen.connect(host, port, username);
        }

        @Override
        public com.stonebreak.ui.settingsMenu.SettingsMenu settingsMenu() {
            Game game = Game.getInstance();
            // only while its document shows: the menu then lays out here, once a frame
            return game != null && game.getState() == com.stonebreak.core.GameState.SETTINGS
                && com.stonebreak.ui.runtime.screens.StateScreens.get().showing(com.stonebreak.core.GameState.SETTINGS)
                ? game.getSettingsMenu() : null;
        }

        @Override
        public String settingsAction(String name, UiValue.Obj args) {
            return SettingsMenuContracts.perform(settingsMenu(), name, args);
        }

        @Override
        public com.stonebreak.ui.worldSelect.WorldSelectScreen worldSelect() {
            Game game = Game.getInstance();
            return game != null && game.getState() == com.stonebreak.core.GameState.WORLD_SELECT
                ? game.getWorldSelectScreen() : null;
        }

        @Override
        public String worldSelectAction(String name, double arg) {
            return WorldSelectContracts.perform(worldSelect(), name, arg);
        }

        @Override
        public LoadingRecord loading() {
            Game game = Game.getInstance();
            return LoadingRecord.of(game == null ? null : game.getLoadingScreen());
        }

        @Override
        public com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen() {
            Game game = Game.getInstance();
            var screen = game == null ? null : game.getGlossaryScreen();
            return screen != null && screen.isVisible() ? screen : null;
        }

        @Override
        public String closeGlossary() {
            if (glossaryScreen() == null) {
                return "no glossary is showing";
            }
            Game.getInstance().closeGlossaryScreen();
            return null;
        }

        @Override
        public String closeStatistics() {
            Game game = Game.getInstance();
            var screen = game == null ? null : game.getStatisticsScreen();
            if (screen == null || !screen.isVisible()) {
                return "no statistics screen is showing";
            }
            game.closeStatisticsScreen();
            return null;
        }
    }
}
