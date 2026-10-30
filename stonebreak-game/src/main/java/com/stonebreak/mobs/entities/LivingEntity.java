package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.audio.MobSounds;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.weapon.DamageType;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.status.StatusEffectType;

/**
 * Base class for all living entities that can move, interact, and have AI behavior.
 * Extends Entity with health management, movement, and interaction capabilities.
 *
 * <p>Thin facade over three single-responsibility components (issue #233):
 * {@link LivingEntityCombat} (damage, death/XP credit, i-frames, knockback, Fracture stubs),
 * {@link LivingEntityStatusEffects} (timed debuffs and the multipliers they derive) and
 * {@link LivingEntityLocomotion} (jump/swim, steering, knockback impulse, collision probes).
 * The per-tick order in {@link #update} is the contract: physics → status effects →
 * bewildered timer → AI → animation → footsteps.
 */
public abstract class LivingEntity extends Entity {
    /** Weapon id of a hit that was not a melee swing (arrows, spells, abilities, damage over time). */
    public static final int NO_WEAPON = -1;

    // Components
    private final LivingEntityCombat combat = new LivingEntityCombat(this);
    private final LivingEntityStatusEffects statusEffects = new LivingEntityStatusEffects(this);
    private final LivingEntityLocomotion locomotion = new LivingEntityLocomotion(this);

    // Movement and behavior
    protected float moveSpeed;
    protected float turnSpeed;
    protected Vector3f targetDirection;
    protected boolean isMoving;

    // Physical properties
    protected float legHeight; // Distance from ground to bottom of body

    /**
     * Upward velocity applied by {@link #jump()}. Under entity gravity (40),
     * the jump apex is {@code v²/80} blocks: 8.5 peaks at only ~0.90 — never
     * clearing a full block — so the default is 10.5 (apex ~1.38), enough for
     * every mob to mount a one-block ledge even from a standstill flush
     * against it. Subclasses override for stronger hops (chicken: 12).
     */
    protected float jumpVelocity = 10.5f;

    /**
     * This mob's AI controller, or null for AI-less living entities (remote
     * players, decoys). Subclasses assign one in their constructor; the shared
     * update loop, renderer, save system and network replication all consume it
     * through {@link #getAI()}.
     */
    protected MobAI mobAI;

    /** Clip clock for SBE-driven rendering; ticked every update. */
    protected final AnimationController animationController;

    /**
     * Footsteps, sized from this mob's own dimensions. Lives here rather than in each mob class so
     * that every mob has them and no mob has to remember to tick them; null for entities that
     * make none.
     */
    protected final MobSounds footsteps;

    /** Appearance variant rendered from the SBE asset (case-insensitive). */
    protected String textureVariant = "Default";

    // Interaction system
    protected float interactionRange;
    protected long lastInteractionTime;
    private static final float INTERACTION_COOLDOWN = 1.0f; // 1 second between interactions

    /**
     * Optional per-enemy stealth awareness (sight/sound detection driving UNAWARE/SUSPICIOUS/
     * ALERTED/SEARCHING, #321). Null on entities that don't react to a stealthed player; subclasses opt in by
     * assigning one. Exposed so the combat and UI layers can query any entity generically.
     */
    protected AwarenessController awareness;

    /**
     * Creates a new living entity at the specified position.
     */
    public LivingEntity(World world, Vector3f position, EntityType type) {
        super(world, position);

        // Set properties based on entity type
        this.maxHealth = type.getMaxHealth();
        this.health = maxHealth;
        this.moveSpeed = type.getMoveSpeed();
        this.turnSpeed = 90.0f; // Default turn speed in degrees per second
        this.width = type.getWidth();
        this.height = type.getHeight();
        this.length = type.getLength();
        this.legHeight = type.getLegHeight();

        // Initialize state
        this.targetDirection = new Vector3f(0, 0, 0);
        this.isMoving = false;
        this.interactionRange = 3.0f;
        this.lastInteractionTime = 0;
        this.animationController = new AnimationController(this);
        this.footsteps = hasFootsteps() ? MobSounds.forEntity(world, this) : null;
    }

    /**
     * Whether this entity makes stride-counted footstep noises as it walks ({@link MobSounds}).
     * True for anything with feet; player figures are the exception — they step on their gait
     * clock's foot contacts instead, as the local player does ({@code RemotePlayer}).
     */
    protected boolean hasFootsteps() {
        return true;
    }

    /**
     * Updates the living entity's state, including invulnerability and movement.
     */
    @Override
    public void update(float deltaTime) {
        // Update invulnerability timer
        combat.updateInvulnerability(deltaTime);

        // Water forces (buoyancy, drag, current) belong to the physics step, where they act on the
        // velocity about to be integrated — see EntityWaterPhysics.

        // Update movement state
        isMoving = locomotion.computeIsMoving();

        // Rooted entities are pinned in place — kill residual horizontal drift
        // (knockback slide, water flow) before it integrates into position.
        locomotion.pinIfRooted();

        // Apply basic physics
        applyPhysics(deltaTime);

        // Tick timed debuffs (burning DOT, stun, armor break, ...)
        updateStatusEffects(deltaTime);

        // Tick the Illusionist Bewildered/panic timer; clear the forced target when it lapses.
        combat.updateBewildered(deltaTime);

        // Update AI behavior — suppressed while stunned or seated in a vehicle
        if (!isStunned() && !isRiding()) {
            updateAI(deltaTime);
        }

        // Advance the clip clock; SBE renderers sample animations from it.
        animationController.updateAnimations(deltaTime);

        // Footsteps are heard where the mob is RENDERED: a client-local mob in a render-only
        // world plays them here; a server-world mob's are played by its client shadow
        // (updateClientVisuals). Playing them here on the server world drove the client's
        // OpenAL mixer from the server tick thread and stayed silent for remote clients.
        if (footsteps != null && world != null && world.isRenderOnly()) {
            footsteps.updateSounds(this);
        }
    }

    /**
     * Runs this entity's AI for one tick. The default drives the shared mob
     * framework: stealth awareness perceives first, so the {@link MobAI}'s
     * awareness-driven behaviours (investigate, pursue, search) act on this
     * tick's state. Subclasses with
     * bespoke behaviour may override.
     */
    protected void updateAI(float deltaTime) {
        if (awareness != null) {
            awareness.update(deltaTime);
        }
        if (mobAI != null) {
            mobAI.update(deltaTime);
        }
    }

    /**
     * Makes the entity jump by applying {@link #jumpVelocity} upward. Only
     * fires on the ground; immediately clears onGround to prevent double jumps
     * (same scheme as the player).
     */
    public void jump() {
        locomotion.jump();
    }

    /**
     * A swim stroke: pushes the mob up through the water it is in.
     *
     * <p>Distinct from {@link #jump()}, which needs ground to push off and so does nothing to a mob
     * floating in a lake. This is what gets a mob over the lip of a bank and back onto land.
     *
     * <p>It uses the mob's <em>own jump strength</em>, and that is the whole reason it works. A mob
     * floating at the surface is barely submerged, so it is fighting nearly full gravity: a gentle
     * paddle lifts it a few centimetres and it stays pinned against the bank forever. A stroke as
     * strong as its jump clears the same one-block ledge in water that it clears on land.
     */
    public void swimUp() {
        locomotion.swimUp();
    }

    /** How high a swim stroke carries this mob, in blocks — what a shore must be within. */
    public float getSwimStrokeReach() {
        return locomotion.swimStrokeReach();
    }

    /**
     * How high this mob's jump actually carries it, in blocks. Route planning derives its climb
     * limit from this rather than from a constant, so a stronger jumper plans routes a weaker one
     * will not — and the two can never disagree, because both read the same velocity.
     */
    public float getJumpApexHeight() {
        return locomotion.jumpApexHeight();
    }

    /**
     * Whether this mob is <em>at home</em> in water — not whether it can swim at all.
     *
     * <p>Every mob can swim: water holds them up and they can stroke their way to a bank, which is
     * what stops a cow that fell in a lake from being stranded there. This flag is about
     * preference: waterfowl route through water as readily as over grass and will happily settle
     * on it, while everything else pays a steep cost to cross and never chooses to rest there.
     */
    public boolean canSwim() {
        return false;
    }


    /**
     * Handles damage to the living entity with invulnerability frames.
     */
    @Override
    public void damage(float amount) {
        damage(amount, DamageSource.UNKNOWN);
    }

    /** @return whether the hit counted (see {@link LivingEntityCombat#damage}) */
    public boolean damage(float amount, DamageSource source) {
        return damage(amount, source, null, true);
    }

    /**
     * Damage of an explicit {@link DamageType} (e.g. a weapon's), resisted per {@link EntityType#resistance}.
     *
     * @return whether the hit counted (see {@link LivingEntityCombat#damage})
     */
    public boolean damage(float amount, DamageSource source, DamageType type) {
        return damage(amount, source, type, null, true);
    }

    /**
     * A melee hit with the item {@code weaponId}: invulnerability after it only blocks that
     * same weapon, so switching weapons lands a fresh hit.
     *
     * @return whether the hit counted (see {@link LivingEntityCombat#damage})
     */
    public boolean damage(float amount, DamageSource source, DamageType type, int weaponId) {
        return damage(amount, source, type, weaponId, null, true);
    }

    /**
     * Authoritative damage application; see {@link LivingEntityCombat#damage}.
     *
     * @param attackerPos       authoritative attacker position for knockback direction, or
     *                          null when unknown (then no knockback)
     * @param creditLocalPlayer whether PLAYER-source stats/XP credit the local player; the
     *                          server passes false for remote attackers so the host isn't
     *                          credited for their kills
     */
    public boolean damage(float amount, DamageSource source, Vector3f attackerPos, boolean creditLocalPlayer) {
        return damage(amount, source, source.defaultDamageType(), attackerPos, creditLocalPlayer);
    }

    /** As {@link #damage(float, DamageSource, Vector3f, boolean)}, with an explicit damage type. */
    public boolean damage(float amount, DamageSource source, DamageType type,
                          Vector3f attackerPos, boolean creditLocalPlayer) {
        return damage(amount, source, type, NO_WEAPON, attackerPos, creditLocalPlayer);
    }

    /**
     * As {@link #damage(float, DamageSource, DamageType, Vector3f, boolean)}, from the melee weapon
     * {@code weaponId}. A hit with a weapon is a {@link HitKind#MELEE} intent on a network shadow;
     * one without ({@link #NO_WEAPON}) an {@link HitKind#ABILITY} intent.
     */
    public boolean damage(float amount, DamageSource source, DamageType type, int weaponId,
                          Vector3f attackerPos, boolean creditLocalPlayer) {
        HitKind kind = weaponId != NO_WEAPON ? HitKind.MELEE : HitKind.ABILITY;
        return combat.damage(amount, source, type, weaponId, attackerPos, creditLocalPlayer, kind);
    }

    /**
     * The local player's hit of a given {@link HitKind} — on a network shadow the kind tells
     * the server which rules validate the intent (melee reach/cooldown/weapon vs an ability
     * profile). Off a shadow it behaves like {@link #damage(float, DamageSource)}.
     */
    public boolean damage(float amount, DamageSource source, HitKind kind) {
        return combat.damage(amount, source, source.defaultDamageType(), NO_WEAPON, null, true, kind);
    }

    /** Applies already-multiplied damage to health via {@link Entity#damage} (may trigger death). */
    void applyBaseDamage(float effectiveAmount) {
        super.damage(effectiveAmount);
    }

    /** The status-effect component, for sibling components. */
    LivingEntityStatusEffects statusEffects() { return statusEffects; }

    // ─────────────────────────────────────────────── Status effects

    /**
     * Applies (or refreshes) a timed debuff. Same-type effects are refreshed rather than stacked.
     *
     * <p>On a network shadow this never mutates local state (like {@link #damage}): a shadow
     * never ticks, so the effect would neither expire nor reach the real mob. The application
     * is forwarded to the server as an intent instead (#339) and comes back replicated
     * ({@link #applyNetworkStatus}).
     */
    public void applyStatusEffect(StatusEffectType type, float duration, float magnitude) {
        if (isNetworkShadow()) {
            if (alive && statusEffects.shouldForward(type, duration, magnitude, System.nanoTime())) {
                com.stonebreak.network.MultiplayerSession.onLocalEntityStatusEffect(this, type, duration, magnitude);
            }
            return;
        }
        statusEffects.apply(type, duration, magnitude);
    }

    /** The active status effects as a {@link com.stonebreak.mobs.entities.status.StatusEffectBits} set. */
    public int getStatusEffectBits() {
        return statusEffects.activeBits();
    }

    /**
     * Client shadow: adopts the server's replicated health fraction and active status effects
     * (#371). Health is set directly — the shadow never dies from it; the despawn does that.
     */
    public void applyNetworkStatus(float healthFraction, int statusBits) {
        float fraction = healthFraction > 0f ? Math.min(1f, healthFraction) : 0f;
        setHealth(fraction * getMaxHealth());
        statusEffects.setReplicatedBits(statusBits);
    }

    // Package-private so tests can advance the DOT clock directly without a full update().
    void updateStatusEffects(float deltaTime) {
        statusEffects.update(deltaTime);
    }

    /** Removes any active status effect of the given type (no-op if absent). */
    public void removeStatusEffect(StatusEffectType type) {
        statusEffects.remove(type);
    }

    /** True while a status effect of the given type is active. */
    public boolean hasStatusEffect(StatusEffectType type) {
        return statusEffects.has(type);
    }

    /** Magnitude of the active effect of the given type, or {@code 0f} if none is active. */
    public float getStatusEffectMagnitude(StatusEffectType type) {
        return statusEffects.magnitude(type);
    }

    /** True while any STUNNED effect is active — suppresses AI updates. */
    public boolean isStunned() {
        return statusEffects.isStunned();
    }

    /** True while any ROOT (or STUNNED — stun implies immobility) effect is active. */
    public boolean isRooted() {
        return statusEffects.isRooted();
    }

    /** Multiplier applied to incoming damage; {@code 1.0} with no Armor Break active, higher otherwise. */
    public float getArmorBreakDamageMultiplier() {
        return statusEffects.armorBreakDamageMultiplier();
    }

    /**
     * Combined multiplier applied to all incoming damage: Armor Break composed with Exposed
     * (multiplicative across the two debuff types, max-of-magnitude within each).
     */
    public float getIncomingDamageMultiplier() {
        return getIncomingDamageMultiplier(DamageSource.UNKNOWN);
    }

    /**
     * Source-aware variant of {@link #getIncomingDamageMultiplier()}: magical sources are
     * additionally amplified by any active Amplified debuff. Pure — Spellmarked consumption
     * (a mutation) happens separately in {@link #damage}.
     */
    public float getIncomingDamageMultiplier(DamageSource source) {
        return statusEffects.incomingDamageMultiplier(source);
    }

    /** Multiplier applied to movement speed; {@code 1.0} with no Cripple active, lower otherwise. */
    public float getMoveSpeedMultiplier() {
        return statusEffects.moveSpeedMultiplier();
    }

    /** XP awarded to the player when this entity is killed. Override in subclasses. */
    public int getXpReward() { return 0; }

    /** This entity's stealth awareness component, or null if it doesn't track the player. */
    public AwarenessController getAwareness() { return awareness; }

    // ─────────────────────────────────────────────── Illusionist Fracture stubs

    /** World position of the most recent attacker (null if unknown / the local player). */
    public Vector3f getLastAttackerPosition() { return combat.getLastAttackerPosition(); }

    /** Puts this entity into the Bewildered panic state for {@code duration} seconds. */
    public void setBewildered(float duration) { combat.setBewildered(duration); }

    /** True while this entity is panicked (Fracture at full Doubt). */
    public boolean isBewildered() { return combat.isBewildered(); }

    /** Names an entity this one should attack next, overriding normal AI target selection. */
    public void setForcedAttackTarget(LivingEntity target) { combat.setForcedAttackTarget(target); }

    /** The forced attack target set by Fracture, or null. */
    public LivingEntity getForcedAttackTarget() { return combat.getForcedAttackTarget(); }

    /**
     * Makes the entity face a specific direction, honoring the entity type's
     * model yaw offset so all rotation paths (AI steering, awareness pursuit)
     * agree on which way the model points.
     */
    public void faceDirection(Vector3f direction, float deltaTime) {
        locomotion.faceDirection(direction, deltaTime);
    }

    /**
     * The world-space horizontal direction this entity's model front points,
     * derived from the current yaw and the type's model yaw offset. The inverse
     * of {@link #faceDirection}; used by sight cones and any forward probing.
     */
    public Vector3f getForwardDirection() {
        return locomotion.forwardDirection();
    }

    /**
     * Checks if the entity can interact with a player.
     */
    public boolean canInteractWith(Player player) {
        if (!alive || player == null) return false;

        float distance = position.distance(player.getPosition());
        long currentTime = System.currentTimeMillis();

        return distance <= interactionRange &&
               (currentTime - lastInteractionTime) >= (INTERACTION_COOLDOWN * 1000);
    }

    /**
     * Handles interaction with a player.
     */
    public void interact(Player player) {
        if (!canInteractWith(player)) return;

        lastInteractionTime = System.currentTimeMillis();
        onInteract(player);
    }

    /**
     * Gets a random direction for wandering behavior.
     */
    protected Vector3f getRandomDirection() {
        return LivingEntityLocomotion.randomDirection();
    }

    /**
     * Checks if the entity can move to a specific position.
     * This method performs collision detection starting from the very bottom of the entity's legs.
     */
    public boolean canMoveTo(Vector3f targetPosition) {
        return locomotion.canMoveTo(targetPosition);
    }

    /**
     * Checks if the entity can move to a specific position while avoiding flowers.
     * This method is specifically for cows and other passive mobs that should avoid trampling flowers.
     */
    public boolean canMoveToAvoidingFlowers(Vector3f targetPosition) {
        return locomotion.canMoveToAvoidingFlowers(targetPosition);
    }

    /**
     * Applies knockback away from the attacking player. Call from {@link #onDamage} when
     * source is {@link DamageSource#PLAYER}.
     */
    protected void applyPlayerKnockback() {
        combat.applyPlayerKnockback();
    }

    /**
     * Whether the local player's attacks and abilities may target this entity. Hits on a target
     * that can't take damage must not pay out Rage, lifesteal, Quarry or debuffs.
     */
    public boolean isPlayerAttackable() { return isAlive(); }

    /**
     * Applies an instantaneous knockback impulse in an arbitrary horizontal direction
     * (e.g. away from a charge line or an ability's impact point), plus a vertical lift.
     * {@code horizontalDirection} need not be normalized; only its horizontal (XZ) component is used.
     */
    public void applyKnockback(Vector3f horizontalDirection, float horizontalForce, float verticalForce) {
        if (isNetworkShadow()) {
            // A shadow never integrates velocity: send the push to the real entity (the server
            // takes it only right after our hit on it), like damage().
            com.stonebreak.network.MultiplayerSession.onLocalEntityKnockback(
                    this, horizontalDirection, horizontalForce, verticalForce);
            return;
        }
        locomotion.applyKnockback(horizontalDirection, horizontalForce, verticalForce);
    }

    // Abstract methods that must be implemented by subclasses

    /**
     * Called when the player interacts with this entity.
     */
    public abstract void onInteract(Player player);

    /**
     * Called when the entity takes damage.
     */
    public abstract void onDamage(float damage, DamageSource source);

    /**
     * Called when the entity dies.
     */
    @Override
    protected abstract void onDeath();

    /**
     * Gets the items this entity should drop when it dies.
     */
    public abstract ItemStack[] getDrops();

    /** This mob's AI controller, or null for AI-less living entities. */
    public MobAI getAI() { return mobAI; }

    /** The clip clock SBE renderers sample animation time from. */
    public AnimationController getAnimationController() { return animationController; }

    /** Appearance variant rendered from the SBE asset. */
    public String getTextureVariant() { return textureVariant; }

    /**
     * Swaps this entity's appearance variant (e.g. a sheared sheep's
     * {@code "Sheared"} SBE variant). Authoritative mutations go through
     * {@code Sheep.shear()}; the client applies replicated changes to its
     * shadows through this setter.
     */
    public void setTextureVariant(String textureVariant) {
        this.textureVariant = textureVariant;
    }

    /** Client shadow: apply the server's replicated animation state to the (otherwise frozen) AI. */
    @Override
    public void applyNetworkState(String sbeStateName) {
        if (mobAI != null) {
            mobAI.setState(com.stonebreak.mobs.sbe.MobStateMapping.behaviorState(getType(), sbeStateName));
        }
    }

    /**
     * Client shadow: keep the animation clock running so the current clip plays, and advance the
     * AI state timer so one-shot clips (sampled from {@code MobAI.getStateTimer()}) animate.
     */
    @Override
    public void updateClientVisuals(float deltaTime) {
        animationController.updateAnimations(deltaTime);
        if (mobAI != null) {
            mobAI.advanceClientClock(deltaTime);
        }
        if (footsteps != null) {
            footsteps.updateSounds(this); // stride from the interpolated position
        }
    }

    // Getters
    public float getMoveSpeed() { return moveSpeed; }
    public float getTurnSpeed() { return turnSpeed; }
    public boolean isMoving() { return isMoving; }
    public float getInteractionRange() { return interactionRange; }
    public Vector3f getTargetDirection() { return new Vector3f(targetDirection); }
    public float getLegHeight() { return legHeight; }

    /**
     * What hits are tested against — arrows, bolts, swings and targeting: the rendered model,
     * turned to the way the entity faces. {@link #getBoundingBox()} stays the movement body
     * (walls, gaps, shoving), which a long or tall model would wedge into doorways. Falls back
     * to that body when no model is registered.
     */
    public BoundingBox getHitBox() {
        ModelBounds model = ModelBounds.of(modelObjectId(), textureVariant);
        if (model == null) {
            return getBoundingBox();
        }
        return model.placed(position.x, position.y - legHeight, position.z, modelYaw(),
                scale.x, scale.y, scale.z);
    }

    /** The SBE model this entity is drawn with, or null for none. */
    protected String modelObjectId() { return getType().getSbeObjectId(); }

    /** The heading, in degrees, the renderer turns this entity's model to. */
    protected float modelYaw() { return rotation.y; }

    // Setters
    public void setMoveSpeed(float moveSpeed) { this.moveSpeed = moveSpeed; }
    public void setTurnSpeed(float turnSpeed) { this.turnSpeed = turnSpeed; }
    public void setInteractionRange(float range) { this.interactionRange = range; }
    public void setTargetDirection(Vector3f direction) { this.targetDirection.set(direction); }

    /**
     * Enumeration of damage sources for damage handling.
     */
    public enum DamageSource {
        UNKNOWN,
        PLAYER,
        ENVIRONMENT,
        FALL,
        DROWNING,
        FIRE,
        EXPLOSION,
        ARROW,
        BLEED,
        /** Player-cast spell damage. Credits the player and interacts with Amplified/Spellmarked. */
        ARCANE;

        /**
         * Whether this source counts as magical for the Amplified debuff. Only ARCANE for
         * now — FIRE (staff bolts, burning DOT) is deliberately excluded to leave existing
         * balance untouched.
         */
        public boolean isMagical() {
            return this == ARCANE;
        }

        /** Damage type of a hit from this source when the caller names none. */
        public DamageType defaultDamageType() {
            return switch (this) {
                case ARROW -> DamageType.PIERCING;
                case FIRE -> DamageType.FIRE;
                case ARCANE -> DamageType.ARCANE;
                default -> DamageType.UNTYPED;
            };
        }
    }
}
