package com.stonebreak.world.save.util;

import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.World;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoSerializableSnapshot;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Inventory;
import org.joml.Vector3f;
import org.joml.Vector2f;
import com.stonebreak.mobs.entities.EntityType;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for converting between game objects and data models.
 * Follows Single Responsibility - only handles conversions.
 * Eliminates duplication across save system.
 */
public final class StateConverter {
    private StateConverter() {} // Utility class

    /**
     * Converts Player to PlayerData.
     * Single source of truth for player state conversion.
     */
    public static PlayerData toPlayerData(Player player, String worldName) {
        PlayerData persistent = com.stonebreak.battletest.BattleTestSession.persistentPlayerData(player);
        if (persistent != null) return persistent;
        // Build combined inventory (9 hotbar + 27 main) from copies: the slots hold the live
        // stacks, which the game keeps editing while a save thread serializes this (#356).
        ItemStack[] combinedInventory = new ItemStack[36];
        Inventory inventory = player.getInventory();

        // Copy hotbar items (slots 0-8)
        for (int i = 0; i < 9; i++) {
            combinedInventory[i] = copyOf(inventory.getHotbarItem(i));
        }

        // Copy main inventory items (slots 9-35)
        for (int i = 0; i < 27; i++) {
            combinedInventory[i + 9] = copyOf(inventory.getMainInventoryItem(i));
        }

        CharacterStats cs = player.getCharacterStats();

        return PlayerData.builder()
            .position(new Vector3f(player.getPosition()))
            .rotation(new Vector2f(player.getCamera().getYaw(), player.getCamera().getPitch()))
            .health(player.getHealth())
            .flightEnabled(player.isFlightEnabled())
            .flying(player.isFlying())
            .spectator(player.isSpectator())
            // The client has no creative mode; claiming one made the server skip the debit and
            // break timing for every action on a cheats world while the client still paid.
            .gameMode(0)
            .inventory(combinedInventory)
            .selectedHotbarSlot(inventory.getSelectedSlot())
            .worldName(worldName)
            .selectedClassId(cs.getSelectedClassId())
            .classInvestmentOrder(cs.getClassInvestmentOrder())
            .spentAbilityCp(new HashMap<>(cs.getSpentAbilityCp()))
            .skillLevels(new HashMap<>(cs.getSkillLevels()))
            .acquiredFeatIds(new HashSet<>(cs.getAcquiredFeatIds()))
            .remainingCp(cs.getRemainingCp())
            .remainingSp(cs.getRemainingSkillPoints())
            .remainingFp(cs.getRemainingFeatPoints())
            .abilityScores(cs.getAbilityScores())
            .remainingAp(cs.getRemainingAp())
            .level(cs.getLevel())
            .xp(cs.getXp())
            .stats(player.getStats())
            .discoveredVariantsByEntityType(snapshotVariants(player))
            .discoveredWeaknessEntityTypes(snapshotWeaknesses(player))
            .build();
    }

    /**
     * Applies only the RPG progression of {@code data} (class, spends, skills, feats, pools,
     * ability scores, level, xp) — what a refused prefs report rolls back to.
     */
    public static void applyProgression(Player player, PlayerData data) {
        CharacterStats cs = player.getCharacterStats();
        cs.restore(
            data.getSelectedClassId(),
            data.getClassInvestmentOrder(),
            new HashMap<>(data.getSpentAbilityCp()),
            new HashMap<>(data.getSkillLevels()),
            new HashSet<>(data.getAcquiredFeatIds()),
            data.getRemainingCp(),
            data.getRemainingSkillPoints(),
            data.getRemainingFeatPoints(),
            data.getAbilityScores(),
            data.getRemainingAp(),
            data.getLevel(),
            data.getXp()
        );
    }

    private static ItemStack copyOf(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }

    private static Map<String, Set<String>> snapshotVariants(Player player) {
        Map<String, Set<String>> result = new HashMap<>();
        player.getEntityDiscoveries().getVariantsSeen().forEach((type, variants) ->
            result.put(type.name(), new HashSet<>(variants)));
        return result;
    }

    private static Set<String> snapshotWeaknesses(Player player) {
        Set<String> result = new HashSet<>();
        player.getEntityDiscoveries().getWeaknessesDiscovered().forEach(type ->
            result.add(type.name()));
        return result;
    }

    /**
     * Applies PlayerData to Player.
     * Single source of truth for player state application.
     */
    public static void applyPlayerData(Player player, PlayerData data) {
        // Mark player as loaded from save to prevent position reset
        player.setLoadedFromSave(true);

        // Apply position and rotation
        player.setPosition(data.getPosition());
        player.getCamera().setYaw(data.getRotation().x);
        player.getCamera().setPitch(data.getRotation().y);

        // Apply player state
        player.setHealth(data.getHealth());
        player.setFlightEnabled(data.isFlightEnabled()); // Enable capability before activating
        player.setFlying(data.isFlying());
        player.setSpectator(data.isSpectator()); // Must be AFTER setFlying — spectator captures prior flight state.

        // Apply inventory
        ItemStack[] loadedInventory = data.getInventory();
        if (loadedInventory != null && loadedInventory.length == 36) {
            Inventory inventory = player.getInventory();

            // Clear existing inventory first to ensure clean slate
            for (int i = 0; i < 9; i++) {
                inventory.setHotbarItem(i, null);
            }
            for (int i = 0; i < 27; i++) {
                inventory.setMainInventoryItem(i, null);
            }

            // Apply hotbar items (slots 0-8)
            for (int i = 0; i < 9; i++) {
                if (loadedInventory[i] != null && !loadedInventory[i].isEmpty()) {
                    inventory.setHotbarItem(i, loadedInventory[i].copy());
                }
            }

            // Apply main inventory items (slots 9-35)
            for (int i = 0; i < 27; i++) {
                if (loadedInventory[i + 9] != null && !loadedInventory[i + 9].isEmpty()) {
                    inventory.setMainInventoryItem(i, loadedInventory[i + 9].copy());
                }
            }

            // Set selected hotbar slot
            inventory.setSelectedSlot(data.getSelectedHotbarSlot());
        }

        // Restore statistics
        player.getStats().restore(
            data.getStatEntitiesKilled(),
            data.getStatDamageDealt(),
            data.getStatTotalDistance(),
            data.getStatDistanceWalked(),
            data.getStatDistanceSprinted(),
            data.getStatDistanceInAir(),
            data.getStatTimeInAir()
        );
        Map<EntityType, Long> killsByType = new EnumMap<>(EntityType.class);
        data.getStatKillsByEntityType().forEach((typeName, count) -> {
            try {
                killsByType.put(EntityType.valueOf(typeName), count);
            } catch (IllegalArgumentException ignored) {}
        });
        player.getStats().restoreKillsByType(killsByType);

        // Restore RPG / character progression
        applyProgression(player, data);

        // Restore entity glossary discoveries
        Map<EntityType, Set<String>> variantsRestored = new EnumMap<>(EntityType.class);
        data.getDiscoveredVariantsByEntityType().forEach((typeName, variants) -> {
            try {
                variantsRestored.put(EntityType.valueOf(typeName), variants);
            } catch (IllegalArgumentException ignored) {}
        });
        Set<EntityType> weaknessesRestored = EnumSet.noneOf(EntityType.class);
        data.getDiscoveredWeaknessEntityTypes().forEach(typeName -> {
            try {
                weaknessesRestored.add(EntityType.valueOf(typeName));
            } catch (IllegalArgumentException ignored) {}
        });
        player.getEntityDiscoveries().restore(variantsRestored, weaknessesRestored);

        // Saved seated in a boat: sit back in it once it streams in (not when respawning below).
        player.getRiding().resumeSeat(data.getHealth() > 0f ? data.getVehiclePosition() : null);

        // Saved while dead (quit or crashed from the death screen): come back respawned rather than
        // alive at 0 health where you died. Last, so full health is the restored character's maximum.
        if (data.getHealth() <= 0f) {
            player.respawn();
        }
    }

    /**
     * Converts Chunk to ChunkData using CCO snapshot API.
     * Water metadata is automatically extracted via CCO integration.
     */
    public static ChunkData toChunkData(Chunk chunk, World world) {
        // CCO snapshot now includes water metadata automatically
        CcoSerializableSnapshot snapshot = chunk.createSnapshot(world);
        return snapshot.toChunkData();
    }

    /**
     * Applies ChunkData to Chunk using CCO snapshot API.
     * Water metadata and entities are automatically applied via CCO integration.
     */
    public static void applyChunkData(Chunk chunk, ChunkData data, World world) {
        // Convert ChunkData back to CCO snapshot (includes water metadata, entities,
        // entity generation flag, per-block SBO state map, AND snow layers).
        // CRITICAL: pass every trailing map — dropping one silently loses that state
        // on load (e.g. the furnace's lit/unlit variant or stacked snow heights).
        CcoSerializableSnapshot snapshot = new CcoSerializableSnapshot(
            data.getChunkX(),
            data.getChunkZ(),
            data.getBlockStorage(),
            data.getLastModified(),
            data.isFeaturesPopulated(),
            data.hasEntitiesGenerated(),
            data.getWaterMetadata(),
            data.getEntities(),
            data.getBlockStates(),        // SBO 1.3 per-block state map
            data.getSnowLayers()          // v3 snow layer counts
        );

        // Load from snapshot (hydrates the chunk's water layer from water metadata)
        chunk.loadFromSnapshot(snapshot, world);

        // NOTE: Do NOT call markClean() here!
        // loadFromSnapshot() marks the chunk as MESH_DIRTY, which is correct - loaded chunks need mesh regeneration.
        // markClean() should ONLY be called after a successful save, not after loading.
    }

    /**
     * Creates ChunkData and applies to existing Chunk.
     * Used when loading chunk from disk.
     */
    public static Chunk createChunkFromData(ChunkData data, World world) {
        Chunk chunk = new Chunk(data.getChunkX(), data.getChunkZ());
        applyChunkData(chunk, data, world);
        return chunk;
    }
}
