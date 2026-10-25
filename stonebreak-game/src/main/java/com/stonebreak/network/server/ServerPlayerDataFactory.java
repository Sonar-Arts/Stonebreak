package com.stonebreak.network.server;

import com.stonebreak.items.ItemStack;
import com.stonebreak.vehicles.Dinghy;
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
        // The save format holds only the 36 slots: a carried stack and the ephemeral 2×2 craft
        // grid are folded into a COPY of them (issue #334), so no save — periodic ones taken
        // mid-drag included — loses them and none mutates the live replica.
        ItemStack[] slots = inv.persistedSlots();
        Dinghy boat = sp.seatedVehicle();
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
                .clientSecret(sp.clientSecret()) // stored with the save, checked on takeover (#335)
                // Always set from the server's seats: a client's claimed vehicle never reaches disk.
                .vehiclePosition(boat != null ? boat.getPosition() : null)
                .build();
    }
}
