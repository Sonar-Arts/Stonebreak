package com.stonebreak.ui.fidelity;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import com.stonebreak.ui.worldSelect.managers.WorldBackupService;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldStatsService;
import com.stonebreak.world.save.model.WorldData;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pinned world select screens for the #299 fidelity gates: worlds, sizes, stats and backup states
 * from memory (never the save folder), dates far from today (the "today" wording follows the
 * clock). A variant is a state ({@code worlds}, {@code empty}, {@code many}, {@code card},
 * {@code card-long}, {@code card-measuring}, {@code card-running}, {@code card-done},
 * {@code card-failed}, {@code many-card7}, {@code delete}) optionally followed by
 * {@code -hover-<part>}. Not a test class.
 */
public final class WorldSelectFixtures {

    private WorldSelectFixtures() {
    }

    public static final String LONG_NAME = "The Extremely Long Named World";

    record World(WorldData data, long bytes, int chunks) {
    }

    static World world(String name, long seed, LocalDateTime created, LocalDateTime played, long playMillis,
                       long bytes, int chunks) {
        return new World(WorldData.builder().worldName(name).seed(seed).createdTime(created).lastPlayed(played)
            .totalPlayTimeMillis(playMillis).build(), bytes, chunks);
    }

    static List<World> worlds(String variant) {
        List<World> out = new ArrayList<>();
        if (variant.startsWith("empty")) {
            return out;
        }
        if (variant.startsWith("many")) {
            for (int i = 1; i <= 12; i++) {
                out.add(world(String.format("World %02d", i), 1000L + i, LocalDateTime.of(2025, 1, i, 9, 0),
                    LocalDateTime.of(2025, 6, 30 - i, 18, 30), i * 600_000L, i * 150_000L, i * 12));
            }
            return out;
        }
        out.add(world("Alpha", 12345L, LocalDateTime.of(2025, 11, 2, 8, 15), LocalDateTime.of(2026, 3, 14, 10, 5),
            7_500_000L, 4_404_019L, 1234));
        out.add(world("Beta Base", -998877L, LocalDateTime.of(2025, 7, 4, 12, 0), LocalDateTime.of(2026, 2, 2, 21, 45),
            2_700_000L, 831_488L, 87));
        out.add(world("Gamma", 42L, null, null, 30_000L, -1L, 0));
        out.add(world(LONG_NAME, 0L, LocalDateTime.of(2024, 12, 25, 7, 30), LocalDateTime.of(2025, 12, 31, 23, 59),
            0L, 12_884_901_888L, 99_999));
        return out;
    }

    /** The world list, sizes and stats of a variant; Gamma is still being measured. */
    static final class Worlds extends WorldDiscoveryManager {
        private final Map<String, World> worlds = new LinkedHashMap<>();

        Worlds(List<World> list) {
            list.forEach(w -> worlds.put(w.data().getWorldName(), w));
        }

        @Override
        public List<String> discoverWorlds() {
            return new ArrayList<>(worlds.keySet());
        }

        @Override
        public WorldData getWorldData(String name) {
            World w = worlds.get(name);
            return w == null ? null : w.data();
        }

        @Override
        public long getWorldSizeBytes(String name) {
            World w = worlds.get(name);
            return w == null ? WorldStatsService.PENDING : w.bytes();
        }

        @Override
        public WorldStatsService.Stats getWorldStats(String name) {
            World w = worlds.get(name);
            return w == null || w.bytes() < 0 ? null : new WorldStatsService.Stats(w.bytes(), w.chunks());
        }
    }

    /** A backup state pinned per variant; starting a backup does nothing. */
    static final class Backups extends WorldBackupService {
        private final Status status;

        Backups(Status status) {
            this.status = status;
        }

        @Override
        public Status getStatus(String world) {
            return status;
        }

        @Override
        public boolean isRunning(String world) {
            return status.state() == State.RUNNING;
        }

        @Override
        public void backup(String world) {
        }
    }

    static WorldBackupService.Status backup(String variant) {
        if (variant.startsWith("card-running")) {
            return new WorldBackupService.Status(WorldBackupService.State.RUNNING, 0.4f, "Backing up... 40%");
        }
        if (variant.startsWith("card-done")) {
            return new WorldBackupService.Status(WorldBackupService.State.DONE, 1f, "Saved Alpha-2026-03-14.zip");
        }
        if (variant.startsWith("card-failed")) {
            return new WorldBackupService.Status(WorldBackupService.State.FAILED, 0f,
                "Backup failed: the world folder is being used by another process");
        }
        return new WorldBackupService.Status(WorldBackupService.State.IDLE, 0f, "");
    }

    /** The world (index into the whole list) whose info card the variant opens, or -1. */
    static int cardWorld(String variant) {
        if (variant.startsWith("many-card7")) {
            return 10; // the last visible row, scrolled to 3
        }
        if (variant.startsWith("card-long")) {
            return 3;
        }
        if (variant.startsWith("card-measuring")) {
            return 2;
        }
        return variant.startsWith("card") ? 0 : -1;
    }

    /** A screen in the variant's state (no hover: the captures hover through their own input). */
    public static WorldSelectScreen screen(SkijaUIBackend backend, String variant) {
        WorldSelectScreen s = new WorldSelectScreen(backend, new Worlds(worlds(variant)), new Backups(backup(variant)));
        WorldStateManager state = s.getStateManager();
        if (variant.startsWith("many")) {
            state.setSelectedIndex(10); // scrolls to 3
        }
        openCard(s, cardWorld(variant));
        if (variant.startsWith("delete")) {
            state.openDeleteDialog(state.getSelectedWorld());
        }
        return s;
    }

    /** Opens world {@code index}'s info card at once (the 350 ms rest already over); -1 closes it. */
    static void openCard(WorldSelectScreen s, int index) {
        WorldStateManager state = s.getStateManager();
        state.closeCard();
        if (index >= 0) {
            state.updateCardHover(index, false, 0L);
            state.tickCard(WorldStateManager.CARD_OPEN_DELAY_MS);
        }
    }

    static String hover(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }
}
