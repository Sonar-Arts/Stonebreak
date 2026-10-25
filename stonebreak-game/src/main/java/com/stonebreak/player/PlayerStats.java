package com.stonebreak.player;

import com.stonebreak.mobs.entities.EntityType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Accumulates per-world gameplay statistics for the player.
 * Lives on {@link Player}, snapshotted into PlayerData on save, restored on load.
 */
public class PlayerStats {

    private long entitiesKilled;
    private double damageDealt;
    private double totalDistance;
    private double distanceWalked;
    private double distanceSprinted;
    private double distanceInAir;
    private double timeInAir;
    private double timeFishing;
    private long bassCaught;
    private long itemsCrafted;

    private final Map<EntityType, Long> killsByType = new EnumMap<>(EntityType.class);

    public void incrementEntitiesKilled() { entitiesKilled++; }
    public void addDamageDealt(double amount) { damageDealt += amount; }
    public void addTotalDistance(double d) { totalDistance += d; }
    public void addDistanceWalked(double d) { distanceWalked += d; }
    public void addDistanceSprinted(double d) { distanceSprinted += d; }
    public void addDistanceInAir(double d) { distanceInAir += d; }
    public void addTimeInAir(double seconds) { timeInAir += seconds; }
    public void addTimeFishing(double seconds) { timeFishing += seconds; }
    public void addBassCaught(long count) { bassCaught += count; }
    public void addItemsCrafted(long count) { itemsCrafted += count; }
    public void incrementKillsForType(EntityType type) {
        killsByType.merge(type, 1L, Long::sum);
    }

    public long getEntitiesKilled()    { return entitiesKilled; }
    public double getDamageDealt()     { return damageDealt; }
    public double getTotalDistance()   { return totalDistance; }
    public double getDistanceWalked()  { return distanceWalked; }
    public double getDistanceSprinted(){ return distanceSprinted; }
    public double getDistanceInAir()   { return distanceInAir; }
    public double getTimeInAir()       { return timeInAir; }
    public double getTimeFishing()     { return timeFishing; }
    public long getBassCaught()        { return bassCaught; }
    public long getItemsCrafted()      { return itemsCrafted; }
    public Map<EntityType, Long> getKillsByType() { return Collections.unmodifiableMap(killsByType); }

    public void restore(long entitiesKilled, double damageDealt, double totalDistance,
                        double distanceWalked, double distanceSprinted,
                        double distanceInAir, double timeInAir) {
        this.entitiesKilled  = entitiesKilled;
        this.damageDealt     = damageDealt;
        this.totalDistance   = totalDistance;
        this.distanceWalked  = distanceWalked;
        this.distanceSprinted = distanceSprinted;
        this.distanceInAir   = distanceInAir;
        this.timeInAir       = timeInAir;
    }

    /** Fishing and crafting counters; separate from {@link #restore} so saves predating them stay zero. */
    public void restoreActivity(double timeFishing, long bassCaught, long itemsCrafted) {
        this.timeFishing  = timeFishing;
        this.bassCaught   = bassCaught;
        this.itemsCrafted = itemsCrafted;
    }

    public void restoreKillsByType(Map<EntityType, Long> map) {
        killsByType.clear();
        killsByType.putAll(map);
    }
}
