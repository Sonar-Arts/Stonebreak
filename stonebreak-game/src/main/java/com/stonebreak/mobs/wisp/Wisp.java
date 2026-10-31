package com.stonebreak.mobs.wisp;

import com.stonebreak.audio.EntitySounds;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.effects.IllusionSmokeParticles;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * The Shade's night stalker. A Wisp would rather frighten a player out of its forest than fight
 * them: it peeks out from behind trees and laughs, vanishes, and whooshes past their ear from
 * behind, every trick raising its fright meter ({@link WispMind}). Only when that meter is full
 * does it turn to fighting — blue fire from a distance, a claw for anyone who gets too close — and
 * then it fights until its victim is dead or out of the Shade.
 *
 * <p>It is bound to the Shade: it will not cross out of it, and if it ever finds itself outside
 * it comes apart into smoke. It leaves at dawn.
 */
public class Wisp extends LivingEntity {

    /** The authored model is ~2.36 blocks tall; a wisp looms a little over a player. */
    public static final float MODEL_SCALE = 0.85f;
    /** How high it floats, and how far it bobs about that. */
    static final float HOVER = 0.45f;
    static final float BOB = 0.08f;
    static final float BOB_RATE = 1.7f;

    /** The parts that glow, and stay faintly visible through the veil. */
    public static final java.util.Set<String> EYE_PARTS = java.util.Set.of("eye_l", "eye_r");

    static final float ROTATION_SPEED = 540f;
    /**
     * Base speed, blocks per second, that its behaviours scale. Well above its attributes' walking
     * pace: at its running multipliers it outruns a sprinting player, so it cannot be run down.
     */
    static final float RUN_SPEED = 3.5f;
    /** It glides straight up a whole block (with a little to spare for slabs) — no stopping to hop. */
    static final float STEP_UP = 1.1f;
    /** How fast the drawn body rises up a step it has glided onto, blocks per second. */
    static final float GLIDE_CLIMB_RATE = 5f;
    /** Off a ledge it floats down rather than falling, blocks per second. */
    static final float GLIDE_SINK_SPEED = 4f;
    /** The tallest drop — a cliff, a hilltop above the canopy — its routes will glide down. */
    static final float MAX_GLIDE_DROP = 16f;
    /** How long coming apart takes before it is gone. */
    static final float DISSOLVE_SECONDS = 1.2f;
    /** How long the smokescreen bit stays up: long enough to replicate, short enough to re-arm. */
    static final float SMOKESCREEN_SECONDS = 0.6f;

    private final WispMind mind;
    private final ShadeColumns shade;
    private final CoverFinder cover;
    private final WispSenses senses;

    private boolean dissolving;
    /** Removal is queued; asked once. */
    private boolean vanished;

    /** Client side: smoke thrown when the replicated smokescreen arrives. */
    private final IllusionSmokeParticles smoke = new IllusionSmokeParticles();
    private boolean smokeShown;
    /** Client side: the height it is drawn at, easing up after a step rather than popping. */
    private float drawnY = Float.NaN;

    public Wisp(World world, Vector3f position) {
        this(world, position, WispTuning.DEFAULT);
    }

    public Wisp(World world, Vector3f position, WispTuning tuning) {
        super(world, position, EntityType.WISP);
        this.scale.set(MODEL_SCALE);
        this.moveSpeed = RUN_SPEED;
        this.mind = new WispMind(tuning);
        this.shade = new ShadeColumns(world);
        this.cover = new CoverFinder(world, shade);
        this.senses = new WispSenses(this, PlayerLocator.ofWorld(this), shade);
        this.interactionRange = 2.5f;
        this.mobAI = new MobAI(this, new Steering(this, ROTATION_SPEED, 0f, 0f),
                new WispDissolveBehavior(this),
                new WispDawnRetreatBehavior(this),
                new WispCorneredBehavior(this),
                WispVanishBehavior.hitReaction(this),
                WispVanishBehavior.breakMelee(this),
                WispVanishBehavior.spotted(this),
                new WispWooshBehavior(this),
                new WispPeekABooBehavior(this),
                new WispKiteAndCastBehavior(this),
                new WispEvadeBehavior(this),
                new WispVeilBehavior(this),
                new WispLurkBehavior(this));
    }

    // ── Tick ────────────────────────────────────────────────────────────────

    @Override
    public void update(float deltaTime) {
        holdInsideShade(deltaTime);
        super.update(deltaTime);
        if (alive && !dissolving && !shade.isShade(position)) {
            dissolve(); // pushed, carried or spawned out of its forest: it cannot exist out there
        }
    }

    /**
     * The Shade's edge is a wall to a Wisp: horizontal motion that would carry it across is
     * cancelled before the physics step moves it. Behaviours keep their goals inside too; this is
     * what holds against knockback and routes that clip a corner.
     */
    private void holdInsideShade(float deltaTime) {
        float nextX = position.x + velocity.x * deltaTime;
        float nextZ = position.z + velocity.z * deltaTime;
        if (!shade.isShade((int) Math.floor(nextX), (int) Math.floor(nextZ))) {
            velocity.x = 0f;
            velocity.z = 0f;
        }
    }

    @Override
    protected void updateAI(float deltaTime) {
        if (senses.refresh(isVeiled(), eyesDark())) {
            mind.look().reset();
            mind.tracking().reset();
        }
        mind.tick(getAge(), deltaTime);
        if (senses.hasVictim() && senses.victimLook() != null) {
            mind.look().update(senses.victimLook(), senses.victimFeet(), position, deltaTime);
        }
        if (senses.hasVictim()) {
            mind.tracking().update(senses.inView(), senses.watched(), position, senses.victimEye(),
                    senses.victimLook(), deltaTime);
        }
        if (mind.mode() == WispMind.Mode.ATTACK && (!senses.hasVictim() || !senses.victimInShade())) {
            mind.calmDown(); // the victim is dead or got out: the hunt is over
        }
        super.updateAI(deltaTime);
    }

    /** Client shadow: puff smoke when a smokescreen arrives, and let the puff drift. */
    @Override
    public void updateClientVisuals(float deltaTime) {
        super.updateClientVisuals(deltaTime);
        boolean smoking = hasStatusEffect(StatusEffectType.SMOKESCREEN);
        if (smoking && !smokeShown) {
            smoke.screen(new Vector3f(position).add(0f, renderLift(), 0f));
        }
        smokeShown = smoking;
        smoke.update(deltaTime);
        easeDrawnHeight(deltaTime);
    }

    /**
     * Collision lifts it onto a step in one move; drawn, it glides up instead. Drops and
     * teleports are taken at once — only climbing is eased.
     */
    private void easeDrawnHeight(float deltaTime) {
        float y = position.y;
        if (Float.isNaN(drawnY) || y <= drawnY || y - drawnY > 2f * STEP_UP) {
            drawnY = y;
        } else {
            drawnY = Math.min(y, drawnY + GLIDE_CLIMB_RATE * deltaTime);
        }
    }

    // ── What behaviours do through it ───────────────────────────────────────

    WispMind mind() {
        return mind;
    }

    WispSenses senses() {
        return senses;
    }

    CoverFinder cover() {
        return cover;
    }

    ShadeColumns shade() {
        return shade;
    }

    /** Wraps it in the veil for {@code seconds}. */
    void veil(float seconds) {
        applyStatusEffect(StatusEffectType.VEILED, seconds, 0f);
    }

    public boolean isVeiled() {
        return hasStatusEffect(StatusEffectType.VEILED);
    }

    /** Drops the veil (and any dimming of its eyes): it stands there for all to see. */
    void unveil() {
        removeStatusEffect(StatusEffectType.VEILED);
        removeStatusEffect(StatusEffectType.EYES_DARK);
    }

    /** Dims its eyes for {@code seconds}, so through the veil nothing of it glows. */
    void darkenEyes(float seconds) {
        applyStatusEffect(StatusEffectType.EYES_DARK, seconds, 0f);
    }

    /** Lets its eyes glow again. */
    void brightenEyes() {
        removeStatusEffect(StatusEffectType.EYES_DARK);
    }

    /** Whether its eyes are dimmed; they only hide it while it is veiled too. */
    public boolean eyesDark() {
        return hasStatusEffect(StatusEffectType.EYES_DARK);
    }

    /** Throws down smoke: every client watching puffs it at the Wisp. */
    void smokescreen() {
        applyStatusEffect(StatusEffectType.SMOKESCREEN, SMOKESCREEN_SECONDS, 0f);
    }

    /** Plays one of its voices (laugh, woosh, shriek, cast, scratch) for everyone near. */
    void emit(String event) {
        EntitySounds.emit(this, event);
    }

    /** Starts coming apart into smoke; it is gone once the clip ends. No drops, no reward. */
    void dissolve() {
        if (dissolving) {
            return;
        }
        dissolving = true;
        smokescreen();
        emit("shriek");
    }

    boolean isDissolving() {
        return dissolving;
    }

    /** Takes it out of the world quietly (a despawn: no death, no drops). */
    void vanish() {
        if (vanished) {
            return;
        }
        vanished = true;
        EntityManager manager = world != null ? world.getEntityManager() : null;
        if (manager != null) {
            manager.removeEntity(this);
        } else {
            alive = false;
        }
    }

    // ── LivingEntity ────────────────────────────────────────────────────────

    @Override
    public int lockedTargetPlayerId() {
        return senses.victimId();
    }

    @Override
    public float renderLift() {
        float easing = Float.isNaN(drawnY) ? 0f : drawnY - position.y; // ≤ 0 while gliding up a step
        return HOVER + BOB * (float) Math.sin(getAge() * BOB_RATE) + easing;
    }

    @Override
    public float getMaxStepUp() {
        return STEP_UP;
    }

    @Override
    public float getGlideSinkSpeed() {
        return GLIDE_SINK_SPEED;
    }

    @Override
    public float getMaxGlideDrop() {
        return MAX_GLIDE_DROP;
    }

    /** It floats; it makes no footsteps. */
    @Override
    protected boolean hasFootsteps() {
        return false;
    }

    /** Smoke is past hurting. */
    @Override
    public boolean isPlayerAttackable() {
        return super.isPlayerAttackable() && !dissolving;
    }

    /** A night's encounter, not part of the world: never saved. */
    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public void onDamage(float damage, DamageSource source) {
        // Blows, arrows and spells; a burn or bleed ticking down is not a fresh attack.
        if (source == DamageSource.PLAYER || source == DamageSource.ARROW || source == DamageSource.ARCANE) {
            mind.onHitByPlayer();
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
    public int getXpReward() {
        return dissolving ? 0 : 15;
    }

    @Override
    public void onInteract(Player player) {
        // Nothing to say to a wisp.
    }

    /** Client side: the smoke it has thrown, for the effects renderer. */
    public IllusionSmokeParticles getSmoke() {
        return smoke;
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering handled by EntityRenderer
    }

    @Override
    public EntityType getType() {
        return EntityType.WISP;
    }
}
