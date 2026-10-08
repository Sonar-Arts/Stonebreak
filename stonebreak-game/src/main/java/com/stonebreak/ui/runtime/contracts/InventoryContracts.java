package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.player.CharacterStats;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The inventory screen's own contracts (#300): {@code stonebreak:player.character} 1, root
 * {@code character} ({@link CharacterRecord}: the side columns' level, vitals and ability scores), and
 * {@code stonebreak:screen.inventory} 1, action {@code .tab {tab}} switching to one of the character
 * sheet's tabs ({@link #TABS}) as the legacy tab strip did. Slots and crafting are the shared
 * {@code stonebreak:inventory} / {@code stonebreak:crafting} contracts.
 */
public final class InventoryContracts {

    public static final HostContract CHARACTER = HostContract.of("stonebreak:player.character", 1);
    public static final HostContract SCREEN = HostContract.of("stonebreak:screen.inventory", 1);

    /** The tab strip's tabs a click can switch to (the first, Inventory, is the screen itself). */
    public static final List<String> TABS = List.of("character", "classes", "skills", "feats");

    /** The game behind the screen; actions return null on success or why they refused. */
    public interface Services {
        /** The local character, or null outside a world. */
        default CharacterStats characterStats() {
            return null;
        }

        /** Closes the inventory and opens the character sheet on {@code tab} (one of {@link #TABS}). */
        default String inventoryTab(String tab) {
            return "no inventory is showing";
        }
    }

    private final Services services;
    private final DataCell character;
    private CharacterRecord last = CharacterRecord.NONE;

    public InventoryContracts(UiHost ui, Services services) {
        this.services = services;
        character = ui.data().register("character", new DataCell(CharacterRecord.TYPE, CharacterRecord.NONE.value()),
            CHARACTER);
        ui.actions().register(ActionSpec.of(SCREEN.id() + ".tab", SCREEN, DataType.object("tab", DataType.string()),
            DataType.ANY), (args, ctx) -> {
                String tab = args.get("tab") instanceof UiValue.Str s ? s.value() : "";
                String problem = TABS.contains(tab) ? services.inventoryTab(tab)
                    : "tab must be one of " + TABS;
                return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                    : CompletableFuture.failedFuture(new IllegalArgumentException(problem));
            });
    }

    /** UI thread, once per frame: republishes the character when anything shown changed. */
    public void poll() {
        CharacterRecord now = CharacterRecord.of(services.characterStats());
        if (!now.equals(last)) {
            last = now;
            character.set(now.value());
        }
    }

    /** Back to no character (world left). */
    public void clear() {
        last = CharacterRecord.NONE;
        character.set(CharacterRecord.NONE.value());
    }
}
