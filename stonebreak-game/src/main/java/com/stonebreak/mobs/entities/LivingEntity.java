package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.world.World;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterFlowPhysics;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.rendering.UI.components.DamageNumberRenderer;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.status.StatusEffect;
import com.stonebreak.mobs.entities.status.StatusEffectType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Base class for all living entities that can move, interact, and have AI behavior.
 * Extends Entity with health management, movement, and interaction capabilities.
 */
public abstract class LivingEntity extends Entity {
    // Health and damage system
    protected boolean invulnerable;
    protected float invulnerabilityTimer;
    private static final float INVULNERABILITY_DURATION = 0.5f; // 500ms after taking damage
    
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

    /** Appearance variant rendered from the SBE asset (case-insensitive). */
    protected String textureVariant = "Default";

    // Interaction system
    protected float interactionRange;
    protected long lastInteractionTime;
    private static final float INTERACTION_COOLDOWN = 1.0f; // 1 second between interactions

    // Status effects (timed debuffs — burning, stun, armor break, etc.)
    private final List<StatusEffect> statusEffects = new ArrayList<>();

    /**
     * Optional per-enemy stealth awareness (sight/sound detection driving UNAWARE/SUSPICIOUS/
     * ALERTED). Null on entities that don't react to a stealthed player; subclasses opt in by
     * assigning one. Exposed so the combat and UI layers can query any entity generically.
     */
    protected AwarenessController awareness;

    /**
     * Position of the most recent attacker, set per damage application. Lets knockback push
     * away from the actual attacker (e.g. a remote player on a host) instead of always the
     * local player. Null when the attacker is unknown or is the local player.
     */
    private Vector3f lastAttackerPosition;

    /**
     * Illusionist Fracture stubs. {@code bewilderedTimer} counts down while the entity is in a
     * panic/friendly-fire state; {@code forcedAttackTarget} names an entity the AI should attack
     * next instead of its normal selection. Both are inert today (no hostile mob AI exists yet)
     * and will be consulted once hostile target selection is implemented.
     */
    private float bewilderedTimer;
    private LivingEntity forcedAttackTarget;

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
        this.invulnerable = false;
        this.invulnerabilityTimer = 0.0f;
        this.targetDirection = new Vector3f(0, 0, 0);
        this.isMoving = false;
        this.interactionRange = 3.0f;
        this.lastInteractionTime = 0;
        this.animationController = new AnimationController(this);
    }
    
    /**
     * Updates the living entity's state, including invulnerability and movement.
     */
    @Override
    public void update(float deltaTime) {
        // Update invulnerability timer
        if (invulnerable) {
            invulnerabilityTimer -= deltaTime;
            if (invulnerabilityTimer <= 0) {
                invulnerable = false;
                invulnerabilityTimer = 0;
            }
        }

        // Check if entity is in water and apply flow forces
        if (isInWater()) {
            WaterFlowPhysics.applyWaterFlowForce(world, position, velocity,
                deltaTime, width, height);
        }

        // Update movement state
        isMoving = velocity.length() > 0.1f;

        // Rooted entities are pinned in place — kill residual horizontal drift
        // (knockback slide, water flow) before it integrates into position.
        if (isRooted()) {
            velocity.x = 0f;
            velocity.z = 0f;
        }

        // Apply basic physics
        applyPhysics(deltaTime);

        // Tick timed debuffs (burning DOT, stun, armor break, ...)
        updateStatusEffects(deltaTime);

        // Tick the Illusionist Bewildered/panic timer; clear the forced target when it lapses.
        if (bewilderedTimer > 0f) {
            bewilderedTimer -= deltaTime;
            if (bewilderedTimer <= 0f) {
                bewilderedTimer = 0f;
                forcedAttackTarget = null;
            }
        }

        // Update AI behavior — suppressed while stunned
        if (!isStunned()) {
            updateAI(deltaTime);
        }

        // Advance the clip clock; SBE renderers sample animations from it.
        animationController.updateAnimations(deltaTime);
    }

    /**
     * Runs this entity's AI for one tick. The default drives the shared mob
     * framework: stealth awareness (investigate/pursue) overrides the passive
     * {@link MobAI} while the entity is SUSPICIOUS/ALERTED. Subclasses with
     * bespoke behaviour may override.
     */
    protected void updateAI(float deltaTime) {
        if (awareness != null) {
            awareness.update(deltaTime);
            if (awareness.drive(deltaTime)) {
                return;
            }
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
        if (isOnGround()) {
            velocity.y = jumpVelocity;
            setOnGround(false);
        }
    }
    
    /**
     * Handles damage to the living entity with invulnerability frames.
     */
    @Override
    public void damage(float amount) {
        damage(amount, DamageSource.UNKNOWN);
    }

    public void damage(float amount, DamageSource source) {
        damage(amount, source, null, true);
    }

    /**
     * Authoritative damage application.
     *
     * <p>On a network shadow this never mutates local state: the authoritative entity lives
     * on the server, so the hit is forwarded as an {@code EntityDamageC2S} intent and a
     * predicted damage number is shown for feedback. (Mutating the shadow would also wedge
     * it permanently invulnerable — shadows never tick, so i-frames would never expire.)
     *
     * @param attackerPos       authoritative attacker position for knockback direction, or
     *                          null to fall back to the local player's position
     * @param creditLocalPlayer whether PLAYER-source stats/XP credit the local player; the
     *                          server passes false for remote attackers so the host isn't
     *                          credited for their kills
     */
    public void damage(float amount, DamageSource source, Vector3f attackerPos, boolean creditLocalPlayer) {
        if (isNetworkShadow()) {
            if (getNetworkId() >= 0 && amount > 0f) {
                DamageNumberRenderer.getInstance().spawn(
                    position.x, position.y + height * 0.9f, position.z, amount);
                MultiplayerSession.onLocalEntityDamage(this, amount, source);
            }
            return;
        }
        if (!alive || invulnerable) return;
        float effectiveAmount = amount * getIncomingDamageMultiplier(source);
        if (source == DamageSource.ARCANE) {
            effectiveAmount *= consumeSpellmark();
        }
        super.damage(effectiveAmount);
        invulnerable = true;
        invulnerabilityTimer = INVULNERABILITY_DURATION;
        // Damage numbers are client UI — only spawn them for entities living in the world
        // being rendered. Authoritative entities live in the headless server world, where
        // this would emit a duplicate of the client's predicted number from the tick thread.
        if (Game.getWorld() == world) {
            DamageNumberRenderer.getInstance().spawn(
                position.x, position.y + height * 0.9f, position.z, effectiveAmount);
        }
        if ((source == DamageSource.PLAYER || source == DamageSource.ARCANE) && creditLocalPlayer) {
            Player player = Game.getPlayer();
            if (player != null) {
                player.getStats().addDamageDealt(effectiveAmount);
                if (!alive) {
                    player.getStats().incrementEntitiesKilled();
                    player.getStats().incrementKillsForType(getType());
                    int xpReward = getXpReward();
                    if (xpReward > 0) {
                        player.getCharacterStats().addXp(xpReward);
                    }
                }
            }
        }
        lastAttackerPosition = attackerPos;
        onDamage(effectiveAmount, source);
    }

    // ─────────────────────────────────────────────── Status effects

    /** Applies (or refreshes) a timed debuff. Same-type effects are refreshed rather than stacked. */
    public void applyStatusEffect(StatusEffectType type, float duration, float magnitude) {
        if (!alive) return;
        for (StatusEffect existing : statusEffects) {
            if (existing.getType() == type) {
                existing.refresh(duration);
                return;
            }
        }
        statusEffects.add(new StatusEffect(type, duration, magnitude));
    }

    private void updateStatusEffects(float deltaTime) {
        if (statusEffects.isEmpty()) return;

        // Tick and prune first so damage()/onDamage() (which may itself touch statusEffects,
        // e.g. via applyStatusEffect) never runs while we're iterating the live list.
        float burningTickDamage = 0f;
        float bleedTickDamage = 0f;
        Iterator<StatusEffect> it = statusEffects.iterator();
        while (it.hasNext()) {
            StatusEffect effect = it.next();
            boolean dotTick = effect.tick(deltaTime);
            if (dotTick && effect.getType() == StatusEffectType.BURNING) {
                burningTickDamage += effect.getMagnitude() * StatusEffect.DOT_TICK_INTERVAL;
            }
            if (dotTick && effect.getType() == StatusEffectType.BLEED) {
                bleedTickDamage += effect.getMagnitude() * StatusEffect.DOT_TICK_INTERVAL;
            }
            if (effect.isExpired()) {
                it.remove();
            }
        }

        // Combine concurrent DOT ticks into a single damage() call — the 0.5s
        // invulnerability window would otherwise swallow the second application.
        float totalTickDamage = burningTickDamage + bleedTickDamage;
        if (totalTickDamage > 0f && alive) {
            DamageSource source;
            if (bleedTickDamage <= 0f) {
                source = DamageSource.FIRE;
            } else if (burningTickDamage <= 0f) {
                source = DamageSource.BLEED;
            } else {
                source = DamageSource.UNKNOWN;
            }
            damage(totalTickDamage, source);
        }
    }

    /** Removes any active status effect of the given type (no-op if absent). */
    public void removeStatusEffect(StatusEffectType type) {
        statusEffects.removeIf(effect -> effect.getType() == type);
    }

    /** True while a status effect of the given type is active. */
    public boolean hasStatusEffect(StatusEffectType type) {
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == type) {
                return true;
            }
        }
        return false;
    }

    /** True while any STUNNED effect is active — suppresses AI updates. */
    public boolean isStunned() {
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.STUNNED) {
                return true;
            }
        }
        return false;
    }

    /** True while any ROOT (or STUNNED — stun implies immobility) effect is active. */
    public boolean isRooted() {
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.ROOT || effect.getType() == StatusEffectType.STUNNED) {
                return true;
            }
        }
        return false;
    }

    /** Multiplier applied to incoming damage; {@code 1.0} with no Armor Break active, higher otherwise. */
    public float getArmorBreakDamageMultiplier() {
        float bonus = 0f;
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.ARMOR_BREAK) {
                bonus = Math.max(bonus, effect.getMagnitude());
            }
        }
        return 1f + bonus;
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
        float exposedBonus = 0f;
        float amplifiedBonus = 0f;
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.EXPOSED) {
                exposedBonus = Math.max(exposedBonus, effect.getMagnitude());
            }
            if (effect.getType() == StatusEffectType.AMPLIFIED) {
                amplifiedBonus = Math.max(amplifiedBonus, effect.getMagnitude());
            }
        }
        float multiplier = getArmorBreakDamageMultiplier() * (1f + exposedBonus);
        if (source.isMagical()) {
            multiplier *= 1f + amplifiedBonus;
        }
        return multiplier;
    }

    /**
     * Consumes an active Spellmarked debuff: removes it and returns the one-shot bonus
     * multiplier for the arcane hit that triggered it, or {@code 1.0} when unmarked.
     */
    private float consumeSpellmark() {
        Iterator<StatusEffect> it = statusEffects.iterator();
        while (it.hasNext()) {
            if (it.next().getType() == StatusEffectType.SPELLMARKED) {
                it.remove();
                return 1f + PlayerConstants.SPELLMARKED_BONUS_DAMAGE_MULT;
            }
        }
        return 1f;
    }

    /** Multiplier applied to movement speed; {@code 1.0} with no Cripple active, lower otherwise. */
    public float getMoveSpeedMultiplier() {
        float reduction = 0f;
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.CRIPPLE) {
                reduction = Math.max(reduction, effect.getMagnitude());
            }
        }
        return Math.max(0f, 1f - reduction);
    }

    /** XP awarded to the player when this entity is killed. Override in subclasses. */
    public int getXpReward() { return 0; }

    /** This entity's stealth awareness component, or null if it doesn't track the player. */
    public AwarenessController getAwareness() { return awareness; }

    // ─────────────────────────────────────────────── Illusionist Fracture stubs

    /** World position of the most recent attacker (null if unknown / the local player). */
    public Vector3f getLastAttackerPosition() { return lastAttackerPosition; }

    /** Puts this entity into the Bewildered panic state for {@code duration} seconds. */
    public void setBewildered(float duration) {
        this.bewilderedTimer = Math.max(this.bewilderedTimer, duration);
    }

    /** True while this entity is panicked (Fracture at full Doubt). */
    public boolean isBewildered() { return bewilderedTimer > 0f; }

    /** Names an entity this one should attack next, overriding normal AI target selection. */
    public void setForcedAttackTarget(LivingEntity target) { this.forcedAttackTarget = target; }

    /** The forced attack target set by Fracture, or null. */
    public LivingEntity getForcedAttackTarget() { return forcedAttackTarget; }
    
    /**
     * Moves the entity toward a target position.
     */
    public void moveToward(Vector3f target, float deltaTime) {
        if (!alive || isRooted()) return;

        Vector3f direction = new Vector3f(target).sub(position).normalize();
        direction.y = 0; // Don't move vertically through movement

        // Apply movement velocity (Cripple slows, Root pins entirely above)
        Vector3f movement = new Vector3f(direction).mul(moveSpeed * getMoveSpeedMultiplier() * deltaTime);
        velocity.x = movement.x;
        velocity.z = movement.z;
        
        // Face the movement direction
        faceDirection(direction, deltaTime);
    }
    
    /**
     * Makes the entity face a specific direction, honoring the entity type's
     * model yaw offset so all rotation paths (AI steering, awareness pursuit)
     * agree on which way the model points.
     */
    public void faceDirection(Vector3f direction, float deltaTime) {
        if (direction.length() < 0.1f) return;

        float targetYaw = (float) Math.toDegrees(Math.atan2(direction.x, direction.z))
                + getType().getModelYawOffsetDegrees();

        // Smoothly rotate toward target along the shortest arc
        float yawDiff = targetYaw - rotation.y;
        while (yawDiff > 180.0f) yawDiff -= 360.0f;
        while (yawDiff < -180.0f) yawDiff += 360.0f;

        float maxRotation = turnSpeed * deltaTime;
        if (Math.abs(yawDiff) > maxRotation) {
            yawDiff = Math.signum(yawDiff) * maxRotation;
        }

        rotation.y += yawDiff;
    }

    /**
     * The world-space horizontal direction this entity's model front points,
     * derived from the current yaw and the type's model yaw offset. The inverse
     * of {@link #faceDirection}; used by sight cones and any forward probing.
     */
    public Vector3f getForwardDirection() {
        float travelYawRad = (float) Math.toRadians(
                rotation.y - getType().getModelYawOffsetDegrees());
        return new Vector3f((float) Math.sin(travelYawRad), 0f, (float) Math.cos(travelYawRad));
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
        float angle = (float) (Math.random() * 2 * Math.PI);
        return new Vector3f(
            (float) Math.sin(angle),
            0,
            (float) Math.cos(angle)
        );
    }
    
    /**
     * Checks if the entity can move to a specific position.
     * This method performs collision detection starting from the very bottom of the entity's legs.
     */
    public boolean canMoveTo(Vector3f targetPosition) {
        // Create bounding box starting from the bottom of the legs
        // targetPosition.y represents the bottom of the body, so subtract legHeight to get leg bottom
        float legBottomY = targetPosition.y - legHeight;
        BoundingBox targetBounds = new BoundingBox(
            targetPosition.x - width / 2.0f,
            legBottomY, // Start from bottom of legs
            targetPosition.z - length / 2.0f,
            targetPosition.x + width / 2.0f,
            targetPosition.y + height, // Extend to full height
            targetPosition.z + length / 2.0f
        );
        
        // Check for solid blocks in the target area
        int minX = (int) Math.floor(targetBounds.minX);
        int maxX = (int) Math.ceil(targetBounds.maxX);
        int minY = (int) Math.floor(targetBounds.minY);
        int maxY = (int) Math.ceil(targetBounds.maxY);
        int minZ = (int) Math.floor(targetBounds.minZ);
        int maxZ = (int) Math.ceil(targetBounds.maxZ);
        
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z) != null && 
                        world.getBlockAt(x, y, z).isSolid()) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * Checks if the entity can move to a specific position while avoiding flowers.
     * This method is specifically for cows and other passive mobs that should avoid trampling flowers.
     */
    public boolean canMoveToAvoidingFlowers(Vector3f targetPosition) {
        // First do basic collision check
        if (!canMoveTo(targetPosition)) {
            return false;
        }
        
        // Check for flowers at ground level to avoid trampling them
        // Use bottom of legs position for ground checking
        float legBottomY = targetPosition.y - legHeight;
        int groundX = (int) Math.floor(targetPosition.x);
        int groundY = (int) Math.floor(legBottomY);
        int groundZ = (int) Math.floor(targetPosition.z);
        
        // Check current ground block and surrounding area
        for (int x = groundX - 1; x <= groundX + 1; x++) {
            for (int z = groundZ - 1; z <= groundZ + 1; z++) {
                // Check at ground level and one block up (where flowers typically are)
                for (int y = groundY; y <= groundY + 1; y++) {
                    var blockType = world.getBlockAt(x, y, z);
                    if (blockType != null && isFlower(blockType)) {
                        return false; // Avoid trampling flowers
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * Helper method to identify flower blocks.
     */
    private boolean isFlower(BlockType blockType) {
        return blockType == BlockType.ROSE ||
               blockType == BlockType.DANDELION ||
               blockType == BlockType.WILDGRASS;
    }
    
    /**
     * Applies knockback away from the attacking player. Call from {@link #onDamage} when
     * source is {@link DamageSource#PLAYER}.
     */
    protected void applyPlayerKnockback() {
        Vector3f attackerPos = lastAttackerPosition;
        if (attackerPos == null) {
            Player player = Game.getPlayer();
            if (player == null) return;
            attackerPos = player.getPosition();
        }
        Vector3f knockbackDir = new Vector3f(position).sub(attackerPos);
        knockbackDir.y = 0;
        if (knockbackDir.length() > 0.01f) {
            knockbackDir.normalize();
            applyKnockback(knockbackDir, 3.0f, 1.5f);
        }
    }

    /**
     * Applies an instantaneous knockback impulse in an arbitrary horizontal direction
     * (e.g. away from a charge line or an ability's impact point), plus a vertical lift.
     * {@code horizontalDirection} need not be normalized; only its horizontal (XZ) component is used.
     */
    public void applyKnockback(Vector3f horizontalDirection, float horizontalForce, float verticalForce) {
        Vector3f dir = new Vector3f(horizontalDirection.x, 0f, horizontalDirection.z);
        if (dir.lengthSquared() <= 0.0001f) return;
        dir.normalize();
        velocity.x += dir.x * horizontalForce;
        velocity.z += dir.z * horizontalForce;
        velocity.y += verticalForce;
        if (verticalForce > 0f) {
            setOnGround(false);
        }
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontalSpeed > 8.0f) {
            float scale = 8.0f / horizontalSpeed;
            velocity.x *= scale;
            velocity.z *= scale;
        }
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

    /** Client shadow: apply the server's replicated animation state to the (otherwise frozen) AI. */
    @Override
    public void applyNetworkState(String sbeStateName) {
        if (mobAI != null) {
            mobAI.setState(com.stonebreak.mobs.sbe.MobStateMapping.behaviorState(sbeStateName));
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
    }

    // Getters
    public float getMoveSpeed() { return moveSpeed; }
    public float getTurnSpeed() { return turnSpeed; }
    public boolean isInvulnerable() { return invulnerable; }
    public float getInvulnerabilityTimer() { return invulnerabilityTimer; }
    public boolean isMoving() { return isMoving; }
    public float getInteractionRange() { return interactionRange; }
    public Vector3f getTargetDirection() { return new Vector3f(targetDirection); }
    public float getLegHeight() { return legHeight; }
    
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
    }
}