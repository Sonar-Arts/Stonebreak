package com.stonebreak.mobs.sheep;

import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.util.DropUtil;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.FleeBehavior;
import com.stonebreak.mobs.entities.ai.behavior.StandStillBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WanderBehavior;
import com.stonebreak.mobs.entities.ai.nav.Steering;

/**
 * Sheep mob implementation. Behaviour comes entirely from shared
 * {@link com.stonebreak.mobs.entities.ai.behavior.Behavior}s with sheep tuning.
 */
public class Sheep extends LivingEntity {

    /** Quick turns, and the mid-air drive a long body needs to clear ledge edges. */
    private static final float ROTATION_SPEED = 200.0f;
    private static final float HOP_BOOST_SPEED = 2.2f;
    private static final float HOP_DURATION = 0.8f;

    /** Appearance variant the renderer swaps to once the sheep is sheared. */
    public static final String SHEARED_VARIANT = "Sheared";

    /** True while the previous tick's AI state was Grazing — feeds the regrow transition. */
    private boolean grazingLastTick;
    /** Whether the current (or last) graze started while the sheep was shorn. */
    private boolean grazedWhileSheared;

    public Sheep(World world, Vector3f position) {
        this(world, position, "default");
    }

    public Sheep(World world, Vector3f position, String textureVariant) {
        super(world, position, EntityType.SHEEP);
        this.textureVariant = textureVariant != null ? textureVariant : "default";
        // Sheep personality: slightly restless — more wandering than a cow, and it bolts when hit.
        this.mobAI = new MobAI(this, new Steering(this, ROTATION_SPEED, HOP_BOOST_SPEED, HOP_DURATION),
                new FleeBehavior(10.0f, 4.0f, 1.0f),
                StandStillBehavior.idle(0.35f, 2.5f, 7.0f),
                new WanderBehavior(0.45f, 3.0f, 8.0f, 0.85f),
                StandStillBehavior.graze(0.2f, 2.5f, 7.0f));
        this.interactionRange = 2.5f;
        this.turnSpeed = 200.0f;
    }

    @Override
    public void render(Renderer renderer) {
        // Handled by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.SHEEP;
    }

    @Override
    public void onInteract(Player player) {
        if (!isAlive()) return;
    }

    /**
     * Marks the sheep sheared: the renderer swaps to the registered
     * {@code "Sheared"} SBE variant (narrower wool-less body) and 1-3 wool
     * blocks scatter at the sheep (mirrors the cow death-drop rule). Idempotent —
     * an already-sheared sheep stays sheared and drops nothing more. See
     * {@link SheepShearing#tryShear}.
     *
     * <p>Wool drops as a {@link com.stonebreak.mobs.entities.BlockDrop} (like the
     * player-toss path, which resolves {@code BlockType.getById} first): an
     * ItemStack-backed ItemDrop of a block id renders invisible because the drop
     * renderer resolves items via {@code getItemType()}, which is null for block
     * stacks. Server-side shears spawn the drops in the authoritative world where
     * they replicate as BLOCK_DROP shadows; entities living in the rendered world
     * drop locally.
     */
    public void shear() {
        textureVariant = SHEARED_VARIANT;
        int woolCount = 1 + (int) (Math.random() * 3); // 1-3 wool, Minecraft-style
        for (int i = 0; i < woolCount; i++) {
            DropUtil.createBlockDrop(world, getPosition(), BlockType.WOOL);
        }
    }

    public boolean isSheared() {
        return SHEARED_VARIANT.equalsIgnoreCase(textureVariant);
    }

    /**
     * Wool regrowth: a sheared sheep regrows its wool when it finishes a graze
     * that STARTED while it was shorn. Tracking the graze's start state matters:
     * shearing a mid-graze sheep must not regrow when THAT graze ends, or a
     * re-shear mid-graze would loop wool every ~10 s.
     *
     * <p>Shadows never tick AI, so regrowth is authoritative — the server-side
     * sheep regrows here and the variant change replicates via {@code EntityVariantS2C}
     * ({@code broadcastVariantIfChanged}); sheep living in the rendered world regrow
     * locally.
     */
    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        boolean grazing = mobAI != null && mobAI.getCurrentState() == MobBehaviorState.GRAZING;
        if (grazing && !grazingLastTick) {
            // A graze just started — remember whether the sheep was shorn at its start.
            grazedWhileSheared = isSheared();
        } else if (grazingLastTick && !grazing && isSheared() && grazedWhileSheared) {
            // "default" is the unregistered fallback the renderer resolves to the
            // wooled base model — the same value the constructor spawns with.
            textureVariant = "default";
            grazedWhileSheared = false;
        }
        grazingLastTick = grazing;
    }

    @Override
    public void onDamage(float damage, DamageSource source) {
        if (source == DamageSource.PLAYER) {
            applyPlayerKnockback();
        }
        mobAI.onDamaged(damage);
    }

    @Override
    protected void onDeath() {
        mobAI.cleanup();
    }

    @Override
    public ItemStack[] getDrops() {
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() { return 4; }
}
