package com.stonebreak.mobs.goblin;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.items.weapon.DamageType;
import com.stonebreak.items.weapon.WeaponRules;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.ai.behavior.ArrowLauncher;
import com.stonebreak.mobs.entities.ai.behavior.AttackTarget;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.behavior.MeleeAttackBehavior;
import com.stonebreak.mobs.entities.ai.behavior.PursuePlayerBehavior;
import com.stonebreak.mobs.entities.ai.behavior.RangedAttackBehavior;
import com.stonebreak.mobs.entities.ai.behavior.SearchBehavior;
import com.stonebreak.mobs.entities.ai.behavior.StandStillBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WanderBehavior;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.mobs.entities.ai.perception.PerceptionProfile;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.entities.combat.WeaponProfile;
import com.stonebreak.mobs.goblin.behavior.CollectMeatBehavior;
import com.stonebreak.mobs.goblin.behavior.FollowLeaderBehavior;
import com.stonebreak.mobs.goblin.behavior.FightRivalsBehavior;
import com.stonebreak.mobs.goblin.behavior.HuntPreyBehavior;
import com.stonebreak.mobs.goblin.behavior.RaidBehavior;
import com.stonebreak.mobs.goblin.behavior.RivalTarget;
import com.stonebreak.mobs.goblin.behavior.LoiterBehavior;
import com.stonebreak.mobs.goblin.behavior.MakeCampBehavior;
import com.stonebreak.mobs.goblin.behavior.StayHomeBehavior;
import com.stonebreak.mobs.goblin.behavior.TendCampBehavior;
import com.stonebreak.mobs.goblin.behavior.QuarryTarget;
import com.stonebreak.mobs.goblin.food.StackFoodStore;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import com.stonebreak.mobs.goblin.pack.NearbyEntities;
import com.stonebreak.mobs.sbe.AttachmentVisual;
import com.stonebreak.mobs.sbe.BodyMounts;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.BowController;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Hostile goblin, the reference enemy of the stealth system (#321). It notices a player through its
 * {@link AwarenessController} — keen ears, fair sight (its {@link PerceptionProfile}) — goes to
 * look at what it heard, shouts for the pack once sure, closes in and fights with whatever its
 * {@link GoblinLoadout} rolled — stabbing with a dagger, slamming with a patty smacker,
 * or hanging back to shoot a bow. Weapon and cosmetics hang on the model's sockets.
 *
 * <p>Goblins run in a {@link GoblinPack}. When the pack's food runs low it hunts livestock with the
 * same weapons, carrying the meat in the goblins' pockets ({@link #inventory()}) — which spill when
 * one dies. A player it is alerted to always comes first.
 */
public class Goblin extends LivingEntity {

    /** The authored model is ~2.25 blocks tall; goblins stand well under a player. */
    public static final float MODEL_SCALE = 0.6f;

    private static final float CHASE_SPEED = 1.3f;
    /** Creeping up on something it heard or was told about. */
    private static final float INVESTIGATE_SPEED = 0.9f;
    private static final float SEARCH_SPEED = 0.8f;
    /** Where an archer stops closing in: comfortably inside bow range. */
    private static final float ARCHER_HOLD_FRACTION = 0.7f;
    /** Where a brawler stops: just inside its reach, so the swing can start. */
    private static final float BRAWLER_HOLD_FRACTION = 0.9f;

    private static final float ROTATION_SPEED = 300.0f;
    private static final float HOP_BOOST_SPEED = 2.0f;
    private static final float HOP_DURATION = 0.6f;

    static final float WEAPON_DROP_CHANCE = 0.10f;
    /** Pocket slots for carrying meat home. */
    static final int INVENTORY_SLOTS = 4;

    private final GoblinLoadout loadout;
    /** Bow frame currently attached, to re-attach only when the draw moves to the next frame. */
    private String shownBowState;

    private final StackFoodStore inventory = new StackFoodStore(INVENTORY_SLOTS);
    /** Built on first use, so client shadows (which never ask) never have one. */
    private GoblinPack pack;
    private final NearbyEntities nearby;

    /** A goblin with a freshly rolled loadout. */
    public Goblin(World world, Vector3dc position) {
        this(world, position, GoblinLoadout.roll(new Random()));
    }

    public Goblin(World world, Vector3dc position, GoblinLoadout loadout) {
        super(world, position, EntityType.GOBLIN);
        this.loadout = Objects.requireNonNull(loadout, "loadout");
        this.scale.set(MODEL_SCALE);
        this.awareness = new AwarenessController(this);
        this.nearby = NearbyEntities.of(world);
        this.mobAI = new MobAI(this, new Steering(this, ROTATION_SPEED, HOP_BOOST_SPEED, HOP_DURATION),
                attackFor(loadout.weapon(), AttackTarget.playerFirst(AttackTarget.firstEngaged(
                        new RivalTarget(this, damageTypeOf(loadout)), new QuarryTarget(this, damageTypeOf(loadout))))),
                new PursuePlayerBehavior(awareness, holdDistance(loadout.weapon()), INVESTIGATE_SPEED, CHASE_SPEED),
                new SearchBehavior(awareness, SEARCH_SPEED),
                new FightRivalsBehavior(this, holdDistance(loadout.weapon()), CHASE_SPEED),
                new CollectMeatBehavior(this, nearby),
                new RaidBehavior(this),
                new HuntPreyBehavior(this, holdDistance(loadout.weapon()), CHASE_SPEED),
                new TendCampBehavior(this),
                new MakeCampBehavior(this),
                new StayHomeBehavior(this),
                new FollowLeaderBehavior(this),
                new LoiterBehavior(this),
                StandStillBehavior.idle(0.5f, 2.0f, 6.0f),
                new WanderBehavior(0.5f, 3.0f, 8.0f, 0.8f));
        this.interactionRange = 2.5f;
        loadout.applyTo(mounts());
    }

    private static Behavior attackFor(MobWeapon weapon, AttackTarget target) {
        return switch (weapon.profile()) {
            case WeaponProfile.Melee melee -> new MeleeAttackBehavior(melee, target);
            case WeaponProfile.Ranged ranged -> new RangedAttackBehavior(ranged, ArrowLauncher.WORLD, target);
        };
    }

    /** Where a goblin stops closing in on what it fights — the player or its prey. */
    private static float holdDistance(MobWeapon weapon) {
        return switch (weapon.profile()) {
            case WeaponProfile.Melee melee -> melee.reach() * BRAWLER_HOLD_FRACTION;
            case WeaponProfile.Ranged ranged -> ranged.maxRange() * ARCHER_HOLD_FRACTION;
        };
    }

    /** What its weapon deals when it hits an animal; arrows always pierce (see Arrow). */
    private static DamageType damageTypeOf(GoblinLoadout loadout) {
        ItemType weapon = loadout.weapon().itemType();
        return weapon != null ? WeaponRules.statsFor(weapon).damageType() : DamageType.UNTYPED;
    }

    public GoblinLoadout getLoadout() {
        return loadout;
    }

    /** The pack this goblin runs with; a pack of one until it finds or is given others. */
    public GoblinPack pack() {
        if (pack == null) {
            pack = new GoblinPack(this);
        }
        return pack;
    }

    /** Called by {@link GoblinPack#recruit} only. */
    public void setPack(GoblinPack pack) {
        this.pack = pack;
    }

    /** What it carries — meat for the pack. Spills when it dies. */
    public StackFoodStore inventory() {
        return inventory;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        updateBowFrame();
        if (isAlive() && (world == null || !world.isRenderOnly()) && pack().isLeader(this)) {
            pack.tick(deltaTime, nearby);
        }
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
                mounts().put(BodyMounts.Mount.on(loadout.weapon().socket(), new AttachmentVisual.Item(bow, state)));
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
        if (source == DamageSource.PLAYER || source == DamageSource.MOB) {
            applyPlayerKnockback();
        }
        mobAI.onDamaged(damage);
    }

    @Override
    protected void onDeath() {
        mobAI.cleanup();
        mounts().clear();
        for (ItemStack drop : getDrops()) {
            DropUtil.createItemDrop(world, getPosition(), drop);
        }
        for (ItemStack carried : inventory.takeAll()) {
            DropUtil.createItemDrop(world, getPosition(), carried);
        }
        if (pack != null) {
            pack.leave(this);
        }
    }

    @Override
    public void onRemovedFromWorld() {
        if (pack != null) {
            // Despawned or unloaded alive: the pack goes on without it, and a camp goblin goes
            // back on its camp's roster, to return at the fire.
            pack.memberRemoved(this);
        }
    }

    /** A camp goblin is saved with its camp's fire, never as an entity of its own. */
    @Override
    public boolean isPersistent() {
        return !hasCamp();
    }

    /** A camp goblin stays: its camp brings it home, or freezes it with the fire. */
    @Override
    public boolean despawnsWhenFar() {
        return !hasCamp();
    }

    /**
     * Goblins of one camp are allies, and so are the goblins of one band without a camp; any other
     * goblin is a rival. Other mobs follow the default rule (their own kind).
     */
    @Override
    public boolean isAllyOf(LivingEntity other) {
        if (!(other instanceof Goblin g)) {
            return super.isAllyOf(other);
        }
        if (g == this || g.pack() == pack()) {
            return true;
        }
        return pack().camp() != null && pack().camp() == g.pack().camp();
    }

    private boolean hasCamp() {
        return pack != null && pack.camp() != null;
    }

    /** A band the night brought in together hunts as one pack. */
    @Override
    public void onSpawnedWithGroup(List<Entity> group) {
        List<Goblin> band = new ArrayList<>();
        for (Entity e : group) {
            if (e instanceof Goblin g) {
                band.add(g);
            }
        }
        GoblinPack.formBand(band);
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
