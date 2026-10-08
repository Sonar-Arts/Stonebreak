package com.stonebreak.ui.hotbar;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.player.combat.arcanist.ResonanceTracker;
import com.stonebreak.player.combat.dodge.DodgeController;
import com.stonebreak.rendering.UI.components.hotbar.HealthHeartsRenderer;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import com.stonebreak.ui.support.UiTestFixtures;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The player both sides of the HUD's fidelity gate (#300) show: a mocked player (health, stamina,
 * mana, dodge cooldown, class) and a hotbar, per variant. Block items only (their icons are both
 * sides' GL phase, ledger hard visual 2). Not a test class.
 *
 * <ul>
 *   <li>{@code full}: 20/20 HP, a full stamina bar, dodge ready, an empty hotbar;</li>
 *   <li>{@code hurt}: 7/20 HP (three full hearts, a half, six empty), 15/40 stamina, dodge 40 %
 *       charged, a stocked hotbar with slot 4 selected;</li>
 *   <li>{@code tip}: {@code hurt} with the selected item's name tooltip up (fully opaque); {@code fade}: the
 *       same tooltip halfway through its fade (alpha 0.5);</li>
 *   <li>{@code hardy}: 60 max HP (30 hearts compressed into rows), 33 HP, no stamina bar;</li>
 *   <li>{@code arcanist}: the Arcanist's mana bar (6/10) and its resonance gauge (2 stacks);</li>
 *   <li>{@code kit}: SBO-sprite items in the hotbar (drawn by both sides with Skia: the slot icon inset is
 *       compared too), slot 1 selected.</li>
 * </ul>
 */
public final class HudFixtures {

    private HudFixtures() {
    }

    /** One HUD: the player and the hotbar it shows. */
    public record Hud(Player player, Inventory inventory, HotbarScreen hotbar) {
    }

    public static Hud hud(String variant) {
        Inventory inv = UiTestFixtures.emptyInventory();
        HotbarScreen hotbar = new HotbarScreen(inv);
        float health = 20, maxHealth = 20, stamina = 40, maxStamina = 40, mana = 0, maxMana = 0, dodge = 1;
        CharacterStats stats = new CharacterStats(null);
        ArcanistAbilityController arcanist = null;
        switch (variant) {
            case "full" -> { }
            case "hurt", "tip", "fade" -> {
                health = 7;
                stamina = 15;
                dodge = 0.4f;
                stock(inv);
                if (!variant.equals("hurt")) {
                    hotbar.displayItemTooltip(inv.getHotbarSlot(4));
                }
                if (variant.equals("fade")) {
                    hotbar.update(1.75f); // 0.25 s into the 0.5 s fade: alpha 0.5
                }
            }
            case "kit" -> {
                inv.setHotbarSlot(0, new ItemStack(com.stonebreak.items.ItemType.BANANA, 3));
                inv.setHotbarSlot(1, new ItemStack(com.stonebreak.items.ItemType.SNOWBALL, 16));
                inv.setHotbarSlot(3, new ItemStack(com.stonebreak.items.ItemType.STONE_PICKAXE, 1));
                inv.setSelectedHotbarSlotIndex(1);
            }
            case "hardy" -> {
                health = 33;
                maxHealth = 60;
                stamina = 0;
                maxStamina = 0;
            }
            case "arcanist" -> {
                mana = 6;
                maxMana = 10;
                stats.selectClass(ArcanistAbilityController.CLASS_ID);
                arcanist = mock(ArcanistAbilityController.class);
                ResonanceTracker resonance = mock(ResonanceTracker.class);
                when(resonance.getResonanceStacks()).thenReturn(2);
                when(arcanist.getResonance()).thenReturn(resonance);
            }
            default -> throw new IllegalArgumentException("unknown HUD variant " + variant);
        }
        Player p = mock(Player.class);
        when(p.getHealth()).thenReturn(health);
        when(p.getMaxHealth()).thenReturn(maxHealth);
        when(p.getStamina()).thenReturn(stamina);
        when(p.getMaxStamina()).thenReturn(maxStamina);
        when(p.getMana()).thenReturn(mana);
        when(p.getMaxMana()).thenReturn(maxMana);
        when(p.getCharacterStats()).thenReturn(stats);
        when(p.getArcanistAbilities()).thenReturn(arcanist);
        DodgeController d = mock(DodgeController.class);
        when(d.getCooldownProgress()).thenReturn(dodge);
        when(p.getDodge()).thenReturn(d);
        return new Hud(p, inv, hotbar);
    }

    private static void stock(Inventory inv) {
        inv.setHotbarSlot(0, new ItemStack(BlockType.WOOD, 12));
        inv.setHotbarSlot(2, new ItemStack(BlockType.GRAVEL, 5));
        inv.setHotbarSlot(4, new ItemStack(BlockType.STONE, 64));
        inv.setHotbarSlot(5, new ItemStack(BlockType.SAND, 1));
        inv.setHotbarSlot(8, new ItemStack(BlockType.DIRT, 2));
        inv.setSelectedHotbarSlotIndex(4);
    }

    /**
     * The legacy HUD's geometry at the current UI scale: {@code hotbar} (the background), {@code slot0..8},
     * {@code heart0..n} and, when shown, {@code stamina} and {@code mana}.
     */
    public static Map<String, float[]> rects(int w, int h, Hud hud) {
        HotbarLayoutCalculator.HotbarLayout l = HotbarLayoutCalculator.calculateLayout(w, h);
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("hotbar", new float[]{l.backgroundX, l.backgroundY, l.backgroundWidth, l.backgroundHeight});
        for (int i = 0; i < l.slotCount; i++) {
            HotbarLayoutCalculator.SlotPosition s = HotbarLayoutCalculator.calculateSlotPosition(i, l);
            out.put("slot" + i, new float[]{s.x, s.y, s.width, s.height});
        }
        Player p = hud.player();
        int size = HealthHeartsRenderer.heartSize();
        int total = (int) Math.ceil(p.getMaxHealth() / 2.0f);
        float[][] hearts = HealthHeartsRenderer.heartRects(total, size, l);
        for (int i = 0; i < hearts.length; i++) {
            out.put("heart" + i, hearts[i]);
        }
        float topRow = HealthHeartsRenderer.topRowY(total, size, l);
        if (p.getMaxStamina() > 0) {
            float y = topRow - 6 - 8;
            out.put("stamina", new float[]{l.backgroundX, y, l.backgroundWidth, 8});
            if (p.getMaxMana() > 0 && ArcanistAbilityController.CLASS_ID.equals(p.getCharacterStats().getSelectedClassId())) {
                out.put("mana", new float[]{l.backgroundX, y - 6 - 8, l.backgroundWidth, 8});
            }
        }
        return out;
    }
}
