package com.stonebreak.player;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.combat.BowController;
import com.stonebreak.player.combat.ManaController;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.player.combat.dodge.DodgeController;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.rogue.RogueAbilityController;
import com.stonebreak.player.combat.stealth.StealthController;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.rendering.effects.WaterRippleParticles;
import com.stonebreak.rendering.effects.WaterSplashParticles;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.RAGE_T1_DAMAGE_BONUS;
import static com.stonebreak.player.PlayerConstants.RAGE_T3_LIFESTEAL_PCT;
import static com.stonebreak.player.PlayerConstants.SPAWN_X;
import static com.stonebreak.player.PlayerConstants.SPAWN_Y;
import static com.stonebreak.player.PlayerConstants.SPAWN_Z;

/**
 * Master controller for the player. Owns the shared {@link PhysicsState}, the camera,
 * the inventory, and a suite of focused subsystems that each handle one concern
 * (physics, locomotion, combat, interaction, lifecycle). Every publicly-callable
 * behavior on the old monolithic Player class is preserved here as a thin delegate.
 * Controller wiring lives in {@link PlayerControllers}; the per-tick sequencing in
 * {@link PlayerUpdatePipeline}.
 */
public class Player {

    private final PhysicsState state;
    private final Camera camera;
    private final Inventory inventory;

    // Wired controller suite (physics, locomotion, combat, interaction, lifecycle, RPG)
    private final PlayerControllers c;

    // Per-tick orchestration
    private final PlayerUpdatePipeline updatePipeline;

    private final java.util.Random critRandom = new java.util.Random();

    // Statistics
    private final PlayerStats stats = new PlayerStats();

    // Entity glossary discoveries
    private final EntityDiscoveries discoveries = new EntityDiscoveries();

    // Entity sight tracking (variant discovery via proximity+FOV)
    private final EntitySightingTracker sightingTracker = new EntitySightingTracker();

    // Fishing
    private com.stonebreak.mobs.entities.FishingBobber activeBobber = null;

    // Water entry splash + surface ripple particles
    private final PlayerWaterEffects waterEffects = new PlayerWaterEffects();

    // Third-person body model
    public enum Perspective { FIRST_PERSON, THIRD_PERSON }
    private Perspective perspective = Perspective.FIRST_PERSON;
    private final PlayerBodyAnimation bodyAnimation = new PlayerBodyAnimation();
    private static final float WALK_SPEED_THRESHOLD = 0.5f; // blocks/frame

    public Player(World world) {
        this.state = new PhysicsState();
        this.state.getPosition().set(SPAWN_X, SPAWN_Y, SPAWN_Z);
        this.state.setPreviousY(SPAWN_Y);
        this.camera = new Camera();
        this.inventory = new Inventory();

        this.c = new PlayerControllers(this, world, state, camera, inventory);
        this.updatePipeline = new PlayerUpdatePipeline(this, c, state, camera, stats, waterEffects, bodyAnimation);

        updateDerivedStats();
    }

    public void update() {
        updatePipeline.update();
    }

    public void processMovement(boolean forward, boolean backward, boolean left, boolean right,
                                boolean jump, boolean shift, boolean crouch) {
        boolean moving = forward || backward || left || right;
        boolean sprinting = shift && moving && !c.flight.isFlying()
                            && c.stamina.hasStamina()
                            && !c.stealth.isSprintBlocked(); // cannot sprint while stealthed
        c.stamina.setSprinting(sprinting);
        c.jumpHandler.setCanDoubleJump(c.characterStats.hasFeat("double_jump"));
        float speedMultiplier = c.rangerAbilities.getSpeedMultiplier(this,
                computeIntendedMoveDirection(forward, backward, left, right));
        speedMultiplier *= c.stealth.getMovementMultiplier(this); // stealth movement penalty
        c.movement.processMovement(forward, backward, left, right, jump, shift, crouch, sprinting, speedMultiplier);
    }

    /**
     * Horizontal direction the WASD input is asking for (same camera math as
     * {@link com.stonebreak.player.physics.MovementController}), normalized, or the zero
     * vector when no movement keys are held or the inputs cancel out.
     */
    private Vector3f computeIntendedMoveDirection(boolean forward, boolean backward,
                                                  boolean left, boolean right) {
        Vector3f front = camera.getFront();
        Vector3f rightVec = camera.getRight();
        Vector3f frontDirection = new Vector3f(front.x, 0, front.z);
        Vector3f rightDirection = new Vector3f(rightVec.x, 0, rightVec.z);
        Vector3f intended = new Vector3f();
        if (forward) intended.add(frontDirection);
        if (backward) intended.sub(frontDirection);
        if (right) intended.add(rightDirection);
        if (left) intended.sub(rightDirection);
        if (intended.lengthSquared() > 0.0001f) {
            intended.normalize();
        } else {
            intended.set(0f, 0f, 0f);
        }
        return intended;
    }

    public void updateDerivedStats() {
        c.health.applyNewMaxHealth(c.characterStats.computeMaxHealth());
        c.stamina.setMaxStamina(c.characterStats.computeMaxStamina());
        c.mana.setMaxMana(c.characterStats.computeMaxMana());
        c.mana.setRegenRate(c.characterStats.computeManaRegen());
    }

    public void processMouseLook(float xOffset, float yOffset) {
        camera.processMouseMovement(xOffset, yOffset);
    }

    public void processFlightAscent(boolean shift) { c.flight.processAscent(shift); }
    public void processFlightDescent(boolean shift) { c.flight.processDescent(shift); }

    // Position / state
    public Vector3f getPosition() { return state.getPosition(); }
    public Vector3f getVelocity() { return state.getVelocity(); }
    public void setVelocity(Vector3f velocity) { state.getVelocity().set(velocity); }
    public boolean isOnGround() { return state.isOnGround(); }
    public void setOnGround(boolean onGround) { state.setOnGround(onGround); }

    public void setPosition(float x, float y, float z) {
        state.getPosition().set(x, y, z);
        camera.setPosition(x, y + CAMERA_EYE_OFFSET, z);
    }

    public void setPosition(Vector3f position) {
        setPosition(position.x, position.y, position.z);
    }

    // Camera / view / inventory
    public Camera getCamera() { return camera; }
    public Inventory getInventory() { return inventory; }
    public Matrix4f getViewMatrix() { return camera.getViewMatrix(); }

    // Fishing
    public com.stonebreak.mobs.entities.FishingBobber getActiveBobber() { return activeBobber; }
    public void setActiveBobber(com.stonebreak.mobs.entities.FishingBobber b) { activeBobber = b; }

    // RPG
    public CharacterStats getCharacterStats() { return c.characterStats; }

    // Statistics
    public PlayerStats getStats() { return stats; }

    // Entity glossary
    public EntityDiscoveries getEntityDiscoveries() { return discoveries; }
    public EntitySightingTracker getEntitySightingTracker() { return sightingTracker; }

    // Stamina / mana
    public boolean isSprinting() { return c.stamina.isSprinting(); }
    public float getStamina()    { return c.stamina.getStamina(); }
    public float getMaxStamina() { return c.stamina.getMaxStamina(); }
    public boolean canAffordStamina(float amount) { return c.stamina.canAfford(amount); }
    public boolean consumeStamina(float amount)   { return c.stamina.consume(amount); }
    public float getMana()       { return c.mana.getMana(); }
    public float getMaxMana()    { return c.mana.getMaxMana(); }
    public ManaController getManaController() { return c.mana; }

    // Health / death
    public float getHealth() { return c.health.getHealth(); }
    public float getMaxHealth() { return c.health.getMaxHealth(); }
    public boolean isDead() { return c.health.isDead(); }
    public int getHearts() { return c.health.getHearts(); }
    public void setHealth(float h) { c.health.setHealth(h); }
    public void damage(float amount) {
        if (c.dodge.isInvincible()) return;   // dodge i-frames negate combat damage
        c.berserkerAbilities.getRage().onHitReceived();
        c.health.damage(amount);
        // Stealth break on damage is handled centrally in update() by watching health decrease,
        // so environmental sources (fall, drowning) that call health.damage() directly count too.
    }
    public void heal(float amount) { c.health.heal(amount); }
    public void respawn() { c.deathHandler.respawn(); }

    // Attack animation
    public boolean isAttacking() { return c.attack.isAttacking(); }
    public void startAttackAnimation() { c.attack.startAttackAnimation(); }
    public float getAttackAnimationProgress() { return c.attack.getAnimationProgress(); }
    public float getRawAttackAnimationProgress() { return c.attack.getRawAnimationProgress(); }

    // Bow draw
    public BowController getBowController() { return c.bow; }
    public boolean isDrawingBow() { return c.bow.isDrawing(); }
    public float getBowDrawProgress() { return c.bow.getDrawProgress(); }
    public String getBowSboState() { return c.bow.getBowSboState(); }

    // Flight
    public boolean isFlying() { return c.flight.isFlying(); }
    public void setFlying(boolean flying) { c.flight.setFlying(flying); }
    public boolean isFlightEnabled() { return c.flight.isFlightEnabled(); }
    public void setFlightEnabled(boolean enabled) { c.flight.setFlightEnabled(enabled); }

    // Spectator
    public boolean isSpectator() { return c.spectator.isActive(); }
    public void setSpectator(boolean active) { c.spectator.setActive(active); }
    public boolean isPlayerInsideSolidBlock() { return c.collisionHandler.isPlayerInsideSolidBlock(); }

    // Water
    public boolean isInWater() { return c.swimming.isInWater(); }
    /** Body touching water, even if eyes aren't submerged — replicated for remote splash/ripple triggering. */
    public boolean isPhysicallyInWater() { return state.isPhysicallyInWater(); }
    public boolean justEnteredWaterThisFrame() { return state.justEnteredWaterThisFrame(); }

    public RaycastEngine getRaycastEngine() { return c.raycastEngine; }

    // Third-person / body animation
    public Perspective getPerspective() { return perspective; }

    public void togglePerspective() {
        perspective = (perspective == Perspective.FIRST_PERSON)
                ? Perspective.THIRD_PERSON
                : Perspective.FIRST_PERSON;
    }

    public boolean isThirdPerson() { return perspective == Perspective.THIRD_PERSON; }

    /**
     * Lower-body facing in the player model's facing space (degrees); see
     * {@link PlayerBodyOrientation#modelYawFromDirection}. Tracks movement / look
     * direction, smoothed; the base yaw for the third-person body model.
     */
    public float getBodyYaw() { return bodyAnimation.getBodyOrientation().getBodyYaw(); }

    /** Third-person head yaw relative to the body, clamped to its swivel range. */
    public float getThirdPersonHeadYaw() {
        Vector3f front = camera.getFront();
        return bodyAnimation.getBodyOrientation()
                .getHeadYaw(PlayerBodyOrientation.modelYawFromDirection(front.x, front.z));
    }

    /**
     * Third-person head pitch, clamped so the head never over-rotates. The
     * camera pitch is passed through directly: the third-person body's 180°
     * facing flip (see {@link PlayerBodyOrientation#modelYawFromDirection}) puts
     * a {@code rotateY(180°)} in the model's base transform, which inverts the
     * head bone's local X axis — so {@code rotateX(cameraPitch)} already tilts
     * the head the same way the camera looks (up→up, down→down).
     */
    public float getThirdPersonHeadPitch() { return bodyAnimation.getBodyOrientation().getHeadPitch(camera.getPitch()); }

    /** Continuously advancing animation clock for the body model (Walking). */
    public float getBodyAnimationTime() { return bodyAnimation.getBodyAnimationTime(); }

    /**
     * Animation time to feed for one-shot BASE clips (Jumping). Attack is no
     * longer part of the base state — it plays as an overlay with its own
     * clock (see {@link #getAttackOverlay()}).
     */
    public float getBodyEventTime() {
        if (!state.isOnGround()) return bodyAnimation.getJumpEventTime();
        return bodyAnimation.getBodyAnimationTime();
    }

    /**
     * The base locomotion state: JUMPING &gt; WALKING &gt; IDLE. Attacking is
     * NOT considered — it renders as an overlay on top of this, so the legs
     * keep walking mid-swing.
     */
    public com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState getBaseMovementState() {
        Vector3f vel = state.getVelocity();
        float horizSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // Sprint-swimming: no dedicated clip is authored yet, so this reuses WALKING as a
        // placeholder pose — state selection is correct now, the visual will follow once a
        // real swim animation clip exists in the SB_Player.sbe asset.
        if (state.isPhysicallyInWater() && c.stamina.isSprinting() && horizSpeed > WALK_SPEED_THRESHOLD) {
            return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.WALKING;
        }
        if (!state.isOnGround()) return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.JUMPING;
        if (horizSpeed > WALK_SPEED_THRESHOLD) return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.WALKING;
        return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.IDLE;
    }

    /**
     * Single collapsed state, kept for callers that predate animation mixing
     * (attack still wins here).
     */
    public com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState getMovementState() {
        if (c.attack.isAttacking()) return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.ATTACKING;
        return getBaseMovementState();
    }

    /** Envelope tracker for the attack overlay animation (time + fade weight). */
    public com.stonebreak.mobs.sbe.OverlayAnimState getAttackOverlay() { return bodyAnimation.getAttackOverlay(); }

    /** Returns the melee damage for the player's currently held item (1.0 for bare fist). */
    public float getAttackDamage() {
        ItemStack held = inventory.getSelectedHotbarSlot();
        if (!held.isEmpty() && held.getItem() instanceof ItemType itemType) {
            return itemType.getDamage();
        }
        return 1.0f;
    }

    /** Multiplier applied to melee damage from the Berserker's Rage tier (T1+ grants increased damage). */
    public float getMeleeDamageMultiplier() {
        RageTier tier = c.berserkerAbilities.getRage().getTier();
        return tier.atLeast(RageTier.T1)
            ? 1f + RAGE_T1_DAMAGE_BONUS
            : 1f;
    }

    /**
     * Resolves a melee hit on {@code target}: applies Rage-scaled damage, grants Rage for
     * the hit dealt, and — at Rage T3 — heals the player via lifesteal. Centralizes melee
     * combat resolution so Berserker bonuses apply uniformly regardless of caller.
     */
    public void attackEntity(LivingEntity target) {
        float damageDealt = getAttackDamage() * getMeleeDamageMultiplier();

        // Stealth opener: striking an unaware enemy leaves it flat-footed (so the crit roll below,
        // and any follow-up hit within the window, gains bonus crit chance).
        AwarenessController awareness = target.getAwareness();
        if (awareness != null
                && awareness.getState() == AwarenessController.AwarenessState.UNAWARE) {
            target.applyStatusEffect(StatusEffectType.FLAT_FOOTED,
                    c.stealth.getFlatFootedDuration(this), 0f);
        }

        // Crit-chance roll. Base chance is 0 today; a flat-footed target adds the class crit bonus
        // (Rogue = 1.0 → guaranteed). On a crit, scale by the generic crit multiplier, then let the
        // Rogue's Momentum (if any) amplify it further and apply its tier debuff.
        float critChance = target.hasStatusEffect(StatusEffectType.FLAT_FOOTED)
                ? c.stealth.getFlatFootedCritBonus(this) : 0f;
        if (critChance > 0f && critRandom.nextFloat() < critChance) {
            damageDealt *= PlayerConstants.PLAYER_CRIT_MULTIPLIER;
            damageDealt *= c.rogueAbilities.onCritLanded(this, target);
        }

        target.damage(damageDealt, LivingEntity.DamageSource.PLAYER);
        c.berserkerAbilities.getRage().onMeleeHitDealt();
        c.rangerAbilities.onPlayerMeleeHit(this, target);
        c.stealth.onAttack(this); // attacking breaks stealth instantly

        if (c.berserkerAbilities.getRage().getTier().atLeast(RageTier.T3)) {
            heal(damageDealt * RAGE_T3_LIFESTEAL_PCT);
        }
    }

    // Berserker
    public BerserkerAbilityController getBerserkerAbilities() { return c.berserkerAbilities; }

    // Ranger
    public RangerAbilityController getRangerAbilities() { return c.rangerAbilities; }

    // Arcanist
    public ArcanistAbilityController getArcanistAbilities() { return c.arcanistAbilities; }

    // Illusionist
    public IllusionistAbilityController getIllusionistAbilities() { return c.illusionistAbilities; }

    // Water splash particles
    public WaterSplashParticles getSplashParticles() { return waterEffects.getSplashParticles(); }

    // Water surface ripples
    public WaterRippleParticles getRippleParticles() { return waterEffects.getRippleParticles(); }

    // Rogue
    public RogueAbilityController getRogueAbilities() { return c.rogueAbilities; }
    public boolean tryCastMirroredDeceit() { return c.illusionistAbilities.tryCastMirroredDeceit(this); }
    public boolean tryCastFracture() { return c.illusionistAbilities.tryCastFracture(this); }

    /** True while any class ability is driving the player and movement input should be suppressed. */
    public boolean isAbilityMovementLocked() {
        return c.berserkerAbilities.isMovementLocked() || c.rangerAbilities.isMovementLocked()
            || c.dodge.isMovementLocked();
    }

    // Dodge (universal)
    public DodgeController getDodge() { return c.dodge; }

    /**
     * Triggers a dodge dash in the direction the WASD input is currently asking for (or backward
     * when no movement key is held). Resolves the intended direction from the same camera-relative
     * math as movement so the dash follows live input, not residual momentum.
     */
    public boolean tryDodge(boolean forward, boolean backward, boolean left, boolean right) {
        return c.dodge.tryDodge(this, computeIntendedMoveDirection(forward, backward, left, right));
    }

    /** Noise radius (blocks) spiked by a recent dodge; 0 when not spiked. Read by the stealth system. */
    public float getDodgeNoiseRadius() { return c.dodge.getCurrentNoiseRadius(); }

    // Stealth (universal)
    public StealthController getStealth() { return c.stealth; }

    /** Current player noise radius (blocks) for enemy sound detection (movement state + dodge spike). */
    public float getCurrentNoiseRadius() { return c.stealth.getNoiseRadius(this); }

    // Block interaction
    public Vector3i raycast() { return c.raycastEngine.raycast(); }
    public void placeBlock() { c.blockPlacer.placeBlock(); }
    public void startBreakingBlock() { c.blockBreaker.startBreaking(); }
    public void stopBreakingBlock() { c.blockBreaker.stopBreaking(); }
    public Vector3i getBreakingBlock() { return c.blockBreaker.getBreakingBlock(); }
    public float getBreakingProgress() { return c.blockBreaker.getBreakingProgress(); }
    public boolean attemptDropItemInFront(ItemStack itemToDrop) {
        return c.itemDropInteraction.attemptDropItemInFront(itemToDrop);
    }

    // Lifecycle
    public void giveStartingItems() { c.spawnService.giveStartingItems(); }
    public void setLoadedFromSave(boolean loaded) { c.spawnService.setLoadedFromSave(loaded); }

    public void setWorld(World world) {
        c.setWorld(world);
    }
}
