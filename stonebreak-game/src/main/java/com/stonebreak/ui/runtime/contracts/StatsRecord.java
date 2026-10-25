package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.ui.statisticsScreen.StatisticsFormat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The local player's activity statistics for the statistics screen ({@code stonebreak:player.stats},
 * #299): the values the legacy screen read from {@code player.getStats()}, plus each one written the
 * way that screen writes it ({@code text}, {@link StatisticsFormat}: locale-aware, so a document never
 * re-implements number formatting). A record so the host republishes only when one changed.
 */
public record StatsRecord(boolean present, long entitiesKilled, long killsCow, long killsSheep, long killsChicken,
                          long killsGoblin, double damageDealt, double totalDistance, double distanceWalked,
                          double distanceSprinted, double distanceInAir, double timeInAir, double timeFishing,
                          long bassCaught, long itemsCrafted) {

    private static final String[] FIELDS = {"entitiesKilled", "killsCow", "killsSheep", "killsChicken", "killsGoblin",
        "damageDealt", "totalDistance", "distanceWalked", "distanceSprinted", "distanceInAir", "timeInAir",
        "timeFishing", "bassCaught", "itemsCrafted"};

    /** The whole-number fields; the rest are {@code number}. */
    private static final Set<String> COUNTS = Set.of("entitiesKilled", "killsCow", "killsSheep",
        "killsChicken", "killsGoblin", "bassCaught", "itemsCrafted");

    public static final DataType.Obj TEXT_TYPE = textType();
    public static final DataType.Obj TYPE = type();

    /** No player (menu, loading): every value zero, as the legacy screen showed then. */
    public static final StatsRecord NONE = new StatsRecord(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public static StatsRecord of(PlayerStats s) {
        if (s == null) {
            return NONE;
        }
        Map<EntityType, Long> kills = s.getKillsByType();
        return new StatsRecord(true, s.getEntitiesKilled(), kills.getOrDefault(EntityType.COW, 0L),
            kills.getOrDefault(EntityType.SHEEP, 0L), kills.getOrDefault(EntityType.CHICKEN, 0L),
            kills.getOrDefault(EntityType.GOBLIN, 0L), s.getDamageDealt(), s.getTotalDistance(), s.getDistanceWalked(),
            s.getDistanceSprinted(), s.getDistanceInAir(), s.getTimeInAir(), s.getTimeFishing(), s.getBassCaught(),
            s.getItemsCrafted());
    }

    public UiValue.Obj value() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("present", UiValue.of(present));
        m.put("entitiesKilled", UiValue.of(entitiesKilled));
        m.put("killsCow", UiValue.of(killsCow));
        m.put("killsSheep", UiValue.of(killsSheep));
        m.put("killsChicken", UiValue.of(killsChicken));
        m.put("killsGoblin", UiValue.of(killsGoblin));
        m.put("damageDealt", UiValue.of(damageDealt));
        m.put("totalDistance", UiValue.of(totalDistance));
        m.put("distanceWalked", UiValue.of(distanceWalked));
        m.put("distanceSprinted", UiValue.of(distanceSprinted));
        m.put("distanceInAir", UiValue.of(distanceInAir));
        m.put("timeInAir", UiValue.of(timeInAir));
        m.put("timeFishing", UiValue.of(timeFishing));
        m.put("bassCaught", UiValue.of(bassCaught));
        m.put("itemsCrafted", UiValue.of(itemsCrafted));
        Map<String, UiValue> t = new LinkedHashMap<>();
        t.put("entitiesKilled", UiValue.of(StatisticsFormat.count(entitiesKilled)));
        t.put("killsCow", UiValue.of(StatisticsFormat.count(killsCow)));
        t.put("killsSheep", UiValue.of(StatisticsFormat.count(killsSheep)));
        t.put("killsChicken", UiValue.of(StatisticsFormat.count(killsChicken)));
        t.put("killsGoblin", UiValue.of(StatisticsFormat.count(killsGoblin)));
        t.put("damageDealt", UiValue.of(StatisticsFormat.damage(damageDealt)));
        t.put("totalDistance", UiValue.of(StatisticsFormat.distance(totalDistance)));
        t.put("distanceWalked", UiValue.of(StatisticsFormat.distance(distanceWalked)));
        t.put("distanceSprinted", UiValue.of(StatisticsFormat.distance(distanceSprinted)));
        t.put("distanceInAir", UiValue.of(StatisticsFormat.distance(distanceInAir)));
        t.put("timeInAir", UiValue.of(StatisticsFormat.time(timeInAir)));
        t.put("timeFishing", UiValue.of(StatisticsFormat.time(timeFishing)));
        t.put("bassCaught", UiValue.of(StatisticsFormat.count(bassCaught)));
        t.put("itemsCrafted", UiValue.of(StatisticsFormat.count(itemsCrafted)));
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
            f.put(name, COUNTS.contains(name) ? DataType.integer() : DataType.number());
        }
        f.put("text", TEXT_TYPE);
        return DataType.object(f);
    }
}
