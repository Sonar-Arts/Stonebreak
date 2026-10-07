package com.stonebreak.ui.statisticsScreen;

/**
 * How the statistics screen writes its values, in one place: the legacy renderer and the UI
 * host's {@code stonebreak:player.stats} display strings (#299) both use it. {@code String.format}
 * with the default locale, so grouping and decimal separators follow the player's locale.
 */
public final class StatisticsFormat {

    private StatisticsFormat() {
    }

    public static String count(long v) {
        return String.format("%,d", v);
    }

    public static String damage(double v) {
        return String.format("%.1f", v);
    }

    public static String distance(double meters) {
        if (meters >= 1000.0) {
            return String.format("%.2f km", meters / 1000.0);
        }
        return String.format("%.1f m", meters);
    }

    public static String time(double seconds) {
        long total = (long) seconds;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }
}
