package com.stonebreak.network.server;

import com.stonebreak.items.ItemStack;
import com.stonebreak.world.save.model.PlayerData;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.time.LocalDateTime;

/**
 * Assembles the SERVER-BUILT persistence blob for one player (issue #334): position/rotation
 * from the cached {@code PlayerStateC2S}, prefs from the validated mirror, the inventory from
 * the {@link ServerInventory} replica and progression from the server's own stats — nothing is
 * taken from the client's word. The JSON bytes stay in the existing format, so old saves load
 * unchanged; only the author of the bytes changed.
 */
public final class ServerPlayerDataFactory {

    private ServerPlayerDataFactory() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static PlayerData build(ServerPlayer sp, String worldName) {
        // Refresh the baseline from serverStats first: XP and level-up pools granted since the
        // last accepted prefs commit (or on a client that withholds prefs) are server-owned and
        // must be what hits disk.
        sp.refreshProgressionBaseline();
        PlayerData baseline = sp.progressionBaseline() != null
                ? sp.progressionBaseline()
                : PlayerData.createDefault(worldName);
        ServerInventory inv = sp.inventory();
        ItemStack[] slots = new ItemStack[ServerInventory.TOTAL_SLOTS];
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            slots[i] = inv.slot(i).copy();
        }
        return new PlayerData.Builder(baseline)
                .position(new Vector3f(sp.x(), sp.y(), sp.z()))
                .rotation(new Vector2f(sp.yaw(), sp.pitch()))
                .health(sp.health())
                .flightEnabled(sp.flightEnabled())
                .flying(sp.flying())
                .spectator(sp.spectator())
                .gameMode(sp.gameMode())
                .inventory(slots)
                .selectedHotbarSlot(inv.selectedHotbarSlot())
                .lastSaved(LocalDateTime.now())
                .worldName(worldName)
                .build();
    }
}
