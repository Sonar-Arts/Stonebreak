package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.ui.statisticsScreen.StatisticsFormat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The local player's activity statistics for the statistics screen ({@code stonebreak:player.stats},
 * #299): the values the legacy screen read from {@code player.getStats()}, plus each one written the
 * way that screen writes it ({@code text}, {@link StatisticsFormat}: locale-aware, so a document never
 * re-implements number formatting). A record so the host republishes only when one changed.
 */
public record StatsRecord(boolean present, long entitiesKilled, long killsCow, long killsSheep, long killsChicken,
                          double damageDealt, double totalDistance, double distanceWalked, double distanceSprinted,
                          double distanceInAir, double timeInAir) {

    private static final String[] FIELDS = {"entitiesKilled", "killsCow", "killsSheep", "killsChicken", "damageDealt",
        "totalDistance", "distanceWalked", "distanceSprinted", "distanceInAir", "timeInAir"};

    public static final DataType.Obj TEXT_TYPE = textType();
    public static final DataType.Obj TYPE = type();

    /** No player (menu, loading): every value zero, as the legacy screen showed then. */
    public static final StatsRecord NONE = new StatsRecord(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public static StatsRecord of(PlayerStats s) {
        if (s == null) {
            return NONE;
        }
        Map<EntityType, Long> kills = s.getKillsByType();
        return new StatsRecord(true, s.getEntitiesKilled(), kills.getOrDefault(EntityType.COW, 0L),
            kills.getOrDefault(EntityType.SHEEP, 0L), kills.getOrDefault(EntityType.CHICKEN, 0L), s.getDamageDealt(),
            s.getTotalDistance(), s.getDistanceWalked(), s.getDistanceSprinted(), s.getDistanceInAir(), s.getTimeInAir());
    }

    public UiValue.Obj value() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("present", UiValue.of(present));
        m.put("entitiesKilled", UiValue.of(entitiesKilled));
        m.put("killsCow", UiValue.of(killsCow));
        m.put("killsSheep", UiValue.of(killsSheep));
        m.put("killsChicken", UiValue.of(killsChicken));
        m.put("damageDealt", UiValue.of(damageDealt));
        m.put("totalDistance", UiValue.of(totalDistance));
        m.put("distanceWalked", UiValue.of(distanceWalked));
        m.put("distanceSprinted", UiValue.of(distanceSprinted));
        m.put("distanceInAir", UiValue.of(distanceInAir));
        m.put("timeInAir", UiValue.of(timeInAir));
        Map<String, UiValue> t = new LinkedHashMap<>();
        t.put("entitiesKilled", UiValue.of(StatisticsFormat.count(entitiesKilled)));
        t.put("killsCow", UiValue.of(StatisticsFormat.count(killsCow)));
        t.put("killsSheep", UiValue.of(StatisticsFormat.count(killsSheep)));
        t.put("killsChicken", UiValue.of(StatisticsFormat.count(killsChicken)));
        t.put("damageDealt", UiValue.of(StatisticsFormat.damage(damageDealt)));
        t.put("totalDistance", UiValue.of(StatisticsFormat.distance(totalDistance)));
        t.put("distanceWalked", UiValue.of(StatisticsFormat.distance(distanceWalked)));
        t.put("distanceSprinted", UiValue.of(StatisticsFormat.distance(distanceSprinted)));
        t.put("distanceInAir", UiValue.of(StatisticsFormat.distance(distanceInAir)));
        t.put("timeInAir", UiValue.of(StatisticsFormat.time(timeInAir)));
        m.put("text", new UiValue.Obj(t));
        return new UiValue.Obj(m);
    }

    private static DataType.Obj textType() {
        Map<String, DataType> f = new LinkedHashMap<>();
        for (String name : FIELDS) {
            f.put(name, DataType.string());
        }
        return DataType.object(f);
    }

    private static DataType.Obj type() {
        Map<String, DataType> f = new LinkedHashMap<>();
        f.put("present", DataType.bool());
        for (String name : FIELDS) {
            f.put(name, name.startsWith("kills") || name.equals("entitiesKilled") ? DataType.integer() : DataType.number());
        }
        f.put("text", TEXT_TYPE);
        return DataType.object(f);
    }
}
