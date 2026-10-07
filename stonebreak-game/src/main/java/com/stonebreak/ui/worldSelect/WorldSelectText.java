package com.stonebreak.ui.worldSelect;

import com.stonebreak.ui.worldSelect.managers.WorldStatsService;
import com.stonebreak.world.save.model.WorldData;

import java.time.LocalDateTime;

/**
 * How the world select screen words its rows and its info card, in one place: the legacy renderer
 * and the UI host's {@code stonebreak:screen.world-select} root (#299) both use it.
 *
 * <p>ASCII only: the bundled Minecraft typeface has no em dash, ellipsis or middle dot glyphs.
 */
public final class WorldSelectText {

    public static final String UNKNOWN = "Unknown";
    public static final String MEASURING = "Measuring...";
    public static final String EMPTY_LIST = "No worlds yet - click 'Create New World' to begin.";

    private WorldSelectText() {
    }

    /** A row's second line: last played and the seed, or null when there is neither. */
    public static String meta(WorldData data) {
        if (data == null) return null;
        StringBuilder sb = new StringBuilder();
        if (data.getLastPlayed() != null) {
            LocalDateTime dt = data.getLastPlayed();
            LocalDateTime now = LocalDateTime.now();
            if (dt.toLocalDate().equals(now.toLocalDate())) {
                sb.append("Last played today at ").append(String.format("%d:%02d", dt.getHour(), dt.getMinute()));
            } else {
                sb.append(String.format("Last played %d/%d/%d", dt.getMonthValue(), dt.getDayOfMonth(), dt.getYear()));
            }
        }
        if (data.getSeed() != 0) {
            if (sb.length() > 0) sb.append("   -   ");
            sb.append("Seed: ").append(data.getSeed());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** A row's on-disk size, or null while the background scan has not measured it. */
    public static String size(long sizeBytes) {
        return sizeBytes >= 0 ? WorldStatsService.formatSize(sizeBytes) : null;
    }

    public static String cardSize(WorldStatsService.Stats stats) {
        return stats == null ? MEASURING : WorldStatsService.formatSize(stats.bytes());
    }

    public static String cardChunks(WorldStatsService.Stats stats) {
        return stats == null ? MEASURING : String.format("%,d", stats.chunkCount());
    }

    public static String cardSeed(WorldData data) {
        return data == null ? UNKNOWN : Long.toString(data.getSeed());
    }

    public static String cardCreated(WorldData data) {
        return data == null ? UNKNOWN : date(data.getCreatedTime());
    }

    public static String cardLastPlayed(WorldData data) {
        return data == null ? UNKNOWN : date(data.getLastPlayed());
    }

    public static String cardPlayTime(WorldData data) {
        return data == null ? UNKNOWN : playTime(data.getTotalPlayTimeMillis());
    }

    public static String date(LocalDateTime dt) {
        if (dt == null) return UNKNOWN;
        LocalDateTime now = LocalDateTime.now();
        if (dt.toLocalDate().equals(now.toLocalDate())) {
            return String.format("Today %d:%02d", dt.getHour(), dt.getMinute());
        }
        return String.format("%d/%d/%d", dt.getMonthValue(), dt.getDayOfMonth(), dt.getYear());
    }

    public static String playTime(long millis) {
        if (millis <= 0L) return "0m";
        long minutes = millis / 60_000L;
        if (minutes < 1L) return "< 1m";
        long hours = minutes / 60L;
        if (hours < 1L) return minutes + "m";
        return hours + "h " + (minutes % 60L) + "m";
    }

    /** The delete confirmation's first line. */
    public static String deleteLine(String world) {
        return "\"" + world + "\" will be permanently removed.";
    }
}
