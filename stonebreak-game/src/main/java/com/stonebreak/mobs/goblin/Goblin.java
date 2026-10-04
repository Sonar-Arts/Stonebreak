package com.stonebreak.mobs.goblin;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.ArrowLauncher;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.behavior.ChasePlayerBehavior;
import com.stonebreak.mobs.entities.ai.behavior.MeleeAttackBehavior;
import com.stonebreak.mobs.entities.ai.behavior.RangedAttackBehavior;
import com.stonebreak.mobs.entities.ai.behavior.StandStillBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WanderBehavior;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.entities.combat.WeaponProfile;
import com.stonebreak.mobs.sbe.AttachmentVisual;
import com.stonebreak.mobs.sbe.EntityAttachments;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.BowController;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.Random;

/**
 * Hostile goblin. Notices a player within {@link #NOTICE_RADIUS} blocks, closes in, and fights with
 * whatever its {@link GoblinLoadout} rolled — stabbing with a dagger, slamming with a patty smacker,
 * or hanging back to shoot a bow. Weapon and cosmetics hang on the model's sockets.
 */
public class Goblin extends LivingEntity {

    /** The authored model is ~2.25 blocks tall; goblins stand well under a player. */
    public static final float MODEL_SCALE = 0.6f;

    static final float NOTICE_RADIUS = 15.0f;
    private static final float GIVE_UP_RADIUS = 24.0f;
    private static final float CHASE_SPEED = 1.3f;
    /** Where an archer stops closing in: comfortably inside bow range. */
    private static final float ARCHER_HOLD_FRACTION = 0.7f;
    /** Where a brawler stops: just inside its reach, so the swing can start. */
    private static final float BRAWLER_HOLD_FRACTION = 0.9f;

    private static final float ROTATION_SPEED = 300.0f;
    private static final float HOP_BOOST_SPEED = 2.0f;
    private static final float HOP_DURATION = 0.6f;

    static final float WEAPON_DROP_CHANCE = 0.10f;

    private final GoblinLoadout loadout;
    /** Bow frame currently attached, to re-attach only when the draw moves to the next frame. */
    private String shownBowState;

    /** A goblin with a freshly rolled loadout. */
    public Goblin(World world, Vector3f position) {
        this(world, position, GoblinLoadout.roll(new Random()));
    }

    public Goblin(World world, Vector3f position, GoblinLoadout loadout) {
        super(world, position, EntityType.GOBLIN);
        this.loadout = Objects.requireNonNull(loadout, "loadout");
        this.scale.set(MODEL_SCALE);
        this.mobAI = new MobAI(this, new Steering(this, ROTATION_SPEED, HOP_BOOST_SPEED, HOP_DURATION),
                attackFor(loadout.weapon()),
                chaseFor(loadout.weapon()),
                StandStillBehavior.idle(0.5f, 2.0f, 6.0f),
                new WanderBehavior(0.5f, 3.0f, 8.0f, 0.8f));
        this.interactionRange = 2.5f;
        loadout.applyTo(this);
    }

    private static Behavior attackFor(MobWeapon weapon) {
        return switch (weapon.profile()) {
            case WeaponProfile.Melee melee -> new MeleeAttackBehavior(melee);
            case WeaponProfile.Ranged ranged -> new RangedAttackBehavior(ranged, ArrowLauncher.WORLD);
        };
    }

    private static Behavior chaseFor(MobWeapon weapon) {
        float hold = switch (weapon.profile()) {
            case WeaponProfile.Melee melee -> melee.reach() * BRAWLER_HOLD_FRACTION;
            case WeaponProfile.Ranged ranged -> ranged.maxRange() * ARCHER_HOLD_FRACTION;
        };
        return new ChasePlayerBehavior(NOTICE_RADIUS, GIVE_UP_RADIUS, hold, CHASE_SPEED);
    }

    public GoblinLoadout getLoadout() {
        return loadout;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        updateBowFrame();
    }

    /** Client shadows run no AI, but the replicated state and its clock still bend the bow. */
    @Override
    public void updateClientVisuals(float deltaTime) {
        super.updateClientVisuals(deltaTime);
        updateBowFrame();
    }

    /**
     * Bends the held bow to match the draw clip. Derived from the animation state and its clock,
     * both of which replicate, so every client shows the same frame without a packet of its own.
     */
    private void updateBowFrame() {
        if (!(loadout.weapon().profile() instanceof WeaponProfile.Ranged ranged) || mobAI == null) {
            return;
        }
        String state = mobAI.getCurrentState() == MobBehaviorState.DRAW_BOW
                ? BowController.sboStateFor(mobAI.getStateTimer() / ranged.drawSeconds())
                : null;
        if (!Objects.equals(state, shownBowState)) {
            shownBowState = state;
            ItemType bow = loadout.weapon().itemType();
            if (bow != null) {
                EntityAttachments.attach(this, loadout.weapon().socket(), new AttachmentVisual.Item(bow, state));
            }
        }
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering handled by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.GOBLIN;
    }

    @Override
    public void onInteract(Player player) {
        // Nothing to do with a goblin but fight it.
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
        EntityAttachments.detach(this, null);
        for (ItemStack drop : getDrops()) {
            DropUtil.createItemDrop(world, getPosition(), drop);
        }
    }

    /** Occasionally the weapon it was carrying. */
    @Override
    public ItemStack[] getDrops() {
        ItemType weapon = loadout.weapon().itemType();
        if (weapon != null && Math.random() < WEAPON_DROP_CHANCE) {
            return new ItemStack[] { new ItemStack(weapon, 1) };
        }
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() {
        return 10;
    }
}
