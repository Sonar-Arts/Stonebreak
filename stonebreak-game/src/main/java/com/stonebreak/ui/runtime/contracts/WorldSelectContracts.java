package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.worldSelect.SectionBounds;
import com.stonebreak.ui.worldSelect.WorldSelectLayout;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import com.stonebreak.ui.worldSelect.WorldSelectText;
import com.stonebreak.ui.worldSelect.managers.WorldBackupService;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.world.save.model.WorldData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The world select screen's host contract ({@code stonebreak:screen.world-select} 1, #299). The
 * legacy {@link WorldSelectScreen} stays the controller (selection, scrolling, the info card's
 * open/close delays, the delete confirmation, loading, deleting and backing worlds up); root
 * {@code worldSelect} mirrors it and the actions call it.
 *
 * <p>Root {@code worldSelect}: {@code rows} (the visible page, at most eight: {@code id} = the world's
 * name, {@code index} into the whole list, {@code name}, {@code meta}, {@code size}, {@code selected},
 * {@code hovered}), {@code empty}, {@code hasSelection}, {@code scrollbar} with {@code thumbOffset} and
 * {@code thumbHeight}, {@code card} (the info card: {@code shown}, its rows, backup state) and
 * {@code delete} (the confirmation: {@code shown}, {@code line}). Geometry the legacy screen computes
 * from the window ({@code titleY}, {@code subtitleY}, {@code panelY}, the card's {@code x}/{@code y},
 * the thumb) is in device pixels, from {@link WorldSelectLayout}, the hit tests' source of truth.
 *
 * <p>Actions {@code stonebreak:screen.world-select.<name>}: {@code select {index}}, {@code hover {index}}
 * (-1 = none), {@code hover-card}, {@code wheel {dy}}, {@code move {delta}}, {@code activate}
 * (Enter/Space), {@code play}, {@code create}, {@code delete}, {@code confirm-delete},
 * {@code cancel-delete}, {@code back}, {@code open-folder}, {@code backup}.
 */
public final class WorldSelectContracts {

    public static final HostContract SCREEN = HostContract.of("stonebreak:screen.world-select", 1);

    /** Actions that take a number ({@code index}, {@code dy} or {@code delta}). */
    public static final Map<String, String> NUMERIC = Map.of("select", "index", "hover", "index", "wheel", "dy",
        "move", "delta");
    public static final List<String> PLAIN = List.of("hover-card", "activate", "play", "create", "delete",
        "confirm-delete", "cancel-delete", "back", "open-folder", "backup");

    static final DataType.Obj ROW = DataType.object("id", DataType.string(), "index", DataType.integer(),
        "name", DataType.string(), "meta", DataType.string(), "size", DataType.string(),
        "selected", DataType.bool(), "hovered", DataType.bool());
    static final DataType.Obj CARD = DataType.object("shown", DataType.bool(), "x", DataType.number(),
        "y", DataType.number(), "title", DataType.string(), "size", DataType.string(), "chunks", DataType.string(),
        "seed", DataType.string(), "created", DataType.string(), "lastPlayed", DataType.string(),
        "playTime", DataType.string(), "backupLabel", DataType.string(), "backupEnabled", DataType.bool(),
        "status", DataType.string(), "statusKind", DataType.string(), "running", DataType.bool(),
        "progress", DataType.number());
    static final DataType.Obj DELETE = DataType.object("shown", DataType.bool(), "line", DataType.string());
    public static final DataType.Obj TYPE = DataType.object("rows", DataType.list(ROW, "id"),
        "empty", DataType.bool(), "hasSelection", DataType.bool(), "titleY", DataType.number(),
        "subtitleY", DataType.number(), "panelY", DataType.number(), "scrollbar", DataType.bool(),
        "thumbOffset", DataType.number(), "thumbHeight", DataType.number(), "card", CARD, "delete", DELETE);

    /** The game behind the screen. */
    public interface Services extends MenuWindow {
        /** The showing world select screen, or null. */
        default WorldSelectScreen worldSelect() {
            return null;
        }

        /** Runs action {@code name} ({@link #perform}); null on success or why it refused. */
        default String worldSelectAction(String name, double arg) {
            return "no world select screen is showing";
        }
    }

    private final Services services;
    private final DataCell cell;
    private UiValue last;

    public WorldSelectContracts(UiHost ui, Services services) {
        this.services = services;
        cell = ui.data().register("worldSelect", new DataCell(TYPE, value(null, new int[]{1920, 1080})), SCREEN);
        NUMERIC.forEach((name, arg) -> action(ui, ActionSpec.of(SCREEN.id() + "." + name, SCREEN,
            DataType.object(arg, DataType.number()), DataType.ANY).withReentrancy(ActionSpec.Reentrancy.PARALLEL),
            name, arg));
        for (String name : PLAIN) {
            action(ui, ActionSpec.of(SCREEN.id() + "." + name, SCREEN, null, DataType.ANY)
                .withReentrancy(ActionSpec.Reentrancy.PARALLEL), name, null);
        }
    }

    /**
     * Action {@code name} on {@code screen}, by the legacy handlers' rules: null on success, else why it
     * refused (a disabled button, no card open, no confirmation showing).
     */
    public static String perform(WorldSelectScreen screen, String name, double arg) {
        if (screen == null) {
            return "no world select screen is showing";
        }
        boolean ok = switch (name) {
            case "select" -> screen.selectWorld((int) arg);
            case "hover" -> {
                screen.hover((int) arg, false);
                yield true;
            }
            case "hover-card" -> {
                screen.hover(-1, true);
                yield true;
            }
            case "wheel" -> {
                screen.wheel(arg);
                yield true;
            }
            case "move" -> {
                screen.moveSelection((int) Math.signum(arg));
                yield true;
            }
            case "activate" -> {
                screen.activate();
                yield true;
            }
            case "play" -> screen.playSelected();
            case "create" -> {
                screen.createWorld();
                yield true;
            }
            case "delete" -> screen.requestDelete();
            case "confirm-delete" -> screen.confirmDelete();
            case "cancel-delete" -> screen.cancelDelete();
            case "back" -> {
                screen.back();
                yield true;
            }
            case "open-folder" -> screen.openCardFolder();
            case "backup" -> screen.backupCardWorld();
            default -> throw new IllegalArgumentException("no world select action " + name);
        };
        return ok ? null : name + " is not available now";
    }

    /** UI thread, once per frame: advances the card's delays and republishes what changed. */
    public void poll() {
        WorldSelectScreen screen = services.worldSelect();
        if (screen != null) {
            screen.tick(System.currentTimeMillis());
        }
        UiValue v = value(screen, services.menuWindow());
        if (!v.equals(last)) {
            last = v;
            cell.set(v);
        }
    }

    static UiValue.Obj value(WorldSelectScreen screen, int[] window) {
        WorldSelectLayout layout = WorldSelectLayout.compute(window[0], window[1]);
        Map<String, UiValue> m = new LinkedHashMap<>();
        WorldStateManager state = screen == null ? null : screen.getStateManager();
        List<String> worlds = state == null ? List.of() : state.getWorldList();
        List<UiValue> rows = new ArrayList<>();
        if (state != null) {
            WorldDiscoveryManager discovery = screen.getDiscoveryManager();
            for (int i = state.getVisibleStartIndex(); i < state.getVisibleEndIndex(); i++) {
                String name = worlds.get(i);
                String meta = WorldSelectText.meta(discovery.getWorldData(name));
                String size = WorldSelectText.size(discovery.getWorldSizeBytes(name));
                rows.add(obj("id", UiValue.of(name), "index", UiValue.of(i), "name", UiValue.of(name),
                    "meta", UiValue.of(meta == null ? "" : meta), "size", UiValue.of(size == null ? "" : size),
                    "selected", UiValue.of(i == state.getSelectedIndex()),
                    "hovered", UiValue.of(i == state.getHoveredIndex())));
            }
        }
        m.put("rows", new UiValue.Arr(rows));
        m.put("empty", UiValue.of(worlds.isEmpty()));
        m.put("hasSelection", UiValue.of(screen != null && screen.hasSelection()));
        m.put("titleY", UiValue.of(layout.titleY));
        m.put("subtitleY", UiValue.of(layout.subtitleY));
        m.put("panelY", UiValue.of(layout.panelY));
        int total = worlds.size();
        boolean scrollbar = total > WorldSelectLayout.ITEMS_PER_PAGE;
        // the legacy thumb maths (renderer drawScrollbar), offset from the list top
        float thumbH = scrollbar ? layout.listHeight * WorldSelectLayout.ITEMS_PER_PAGE / (float) total : 0f;
        float thumbOffset = scrollbar ? (layout.listHeight - thumbH) * state.getScrollOffset()
            / Math.max(1, total - WorldSelectLayout.ITEMS_PER_PAGE) : 0f;
        m.put("scrollbar", UiValue.of(scrollbar));
        m.put("thumbOffset", UiValue.of(thumbOffset));
        m.put("thumbHeight", UiValue.of(thumbH));
        m.put("card", card(screen, state, layout));
        String pending = state == null ? null : state.getWorldPendingDelete();
        m.put("delete", obj("shown", UiValue.of(state != null && state.isShowDeleteDialog()),
            "line", UiValue.of(pending == null ? "" : WorldSelectText.deleteLine(pending))));
        return new UiValue.Obj(m);
    }

    private static UiValue.Obj card(WorldSelectScreen screen, WorldStateManager state, WorldSelectLayout layout) {
        String world = state == null ? null : state.getCardWorld();
        int visibleRow = state == null ? -1 : state.getCardIndex() - state.getScrollOffset();
        Map<String, UiValue> m = new LinkedHashMap<>();
        boolean shown = world != null && visibleRow >= 0 && visibleRow < WorldSelectLayout.ITEMS_PER_PAGE;
        m.put("shown", UiValue.of(shown));
        if (!shown) {
            for (String k : List.of("x", "y", "progress")) {
                m.put(k, UiValue.of(0.0));
            }
            for (String k : List.of("title", "size", "chunks", "seed", "created", "lastPlayed", "playTime",
                    "backupLabel", "status", "statusKind")) {
                m.put(k, UiValue.of(""));
            }
            m.put("backupEnabled", UiValue.of(false));
            m.put("running", UiValue.of(false));
            return new UiValue.Obj(m);
        }
        SectionBounds bounds = layout.cardBounds(visibleRow);
        WorldDiscoveryManager discovery = screen.getDiscoveryManager();
        WorldData data = discovery.getWorldData(world);
        var stats = discovery.getWorldStats(world);
        WorldBackupService.Status status = screen.getBackupService().getStatus(world);
        boolean running = status.state() == WorldBackupService.State.RUNNING;
        m.put("x", UiValue.of(bounds.x));
        m.put("y", UiValue.of(bounds.y));
        m.put("title", UiValue.of(world));
        m.put("size", UiValue.of(WorldSelectText.cardSize(stats)));
        m.put("chunks", UiValue.of(WorldSelectText.cardChunks(stats)));
        m.put("seed", UiValue.of(WorldSelectText.cardSeed(data)));
        m.put("created", UiValue.of(WorldSelectText.cardCreated(data)));
        m.put("lastPlayed", UiValue.of(WorldSelectText.cardLastPlayed(data)));
        m.put("playTime", UiValue.of(WorldSelectText.cardPlayTime(data)));
        m.put("backupLabel", UiValue.of(running ? "Backing Up" : "Back Up"));
        m.put("backupEnabled", UiValue.of(!running));
        m.put("status", UiValue.of(status.state() == WorldBackupService.State.IDLE || status.message() == null
            ? "" : status.message()));
        m.put("statusKind", UiValue.of(status.state().name().toLowerCase(java.util.Locale.ROOT)));
        m.put("running", UiValue.of(running));
        m.put("progress", UiValue.of(running ? status.progress() : 0f));
        return new UiValue.Obj(m);
    }

    private static UiValue.Obj obj(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (UiValue) kv[i + 1]);
        }
        return new UiValue.Obj(m);
    }

    private void action(UiHost ui, ActionSpec spec, String name, String arg) {
        ui.actions().register(spec, (args, ctx) -> {
            try {
                double a = arg != null && args != null && args.get(arg) instanceof UiValue.Num n ? n.value() : 0;
                String problem = services.worldSelectAction(name, a);
                poll(); // the document sees the result before the next frame
                return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                    : CompletableFuture.failedFuture(new IllegalStateException(problem));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }
}
