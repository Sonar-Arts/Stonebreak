package com.stonebreak.mobs.entities;

import com.stonebreak.core.Game;
import com.stonebreak.items.weapon.DamageType;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.player.combat.BowController;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Arrow projectile, fired by the player releasing the bow or by an archer mob.
 * Unlike FireBolt, arrows are subject to standard gravity physics applied by EntityManager.
 * Speed (and thus range) scales with how long the bow was drawn.
 */
public class Arrow extends Entity {

    private static final float MAX_AGE    = 10.0f;
    private static final float BASE_DAMAGE = 5.0f;
    private static final float MAX_DAMAGE  = 15.0f;
    private static final float HIT_RADIUS  = 0.4f;
    /** Body boxes are grown by this much when a player's arrow sweeps through them. */
    private static final float ENTITY_HIT_MARGIN = 0.1f;
    /** Movement shorter than this (between network updates, say) keeps the previous heading. */
    private static final float MIN_HEADING_STEP = 1.0e-3f;

    private final float launchSpeed;
    /**
     * Where the arrow was at its previous update. External physics moves it between updates,
     * so {@code lastPosition → position} is the path it just flew.
     */
    private final Vector3d lastPosition;
    private final com.openmason.engine.physics.RayHit rayHit = new com.openmason.engine.physics.RayHit();

    /** The mob that loosed this arrow, or null for a player's arrow. */
    private LivingEntity shooter;
    /** Damage a mob's arrow does to the player it hits. */
    private float mobArrowDamage;
    /** Unit flight direction for drawing; see {@link #heading()}. */
    private final Vector3f heading = new Vector3f();
    /** Where {@link #heading()} last saw the arrow, or null before its first call. */
    private Vector3d headingSample;

    public Arrow(World world, Vector3dc position, Vector3f velocity) {
        super(world, position);
        this.launchSpeed = velocity.length();
        this.lastPosition = new Vector3d(position);
        this.velocity.set(velocity);
        this.width  = 0.05f;
        this.height = 0.05f;
        this.length = 0.5f;
        // Face the travel direction (yaw from xz; the drawn pitch comes from heading())
        this.rotation.y = (float) Math.toDegrees(Math.atan2(-velocity.x, -velocity.z));
        this.scale.set(0.15f, 0.15f, 0.5f);
    }

    /**
     * Marks this as a mob's arrow: it flies through its shooter and other mobs (no friendly
     * fire), and hurts the player it strikes for {@code damage}.
     */
    public Arrow firedBy(LivingEntity shooter, float damage) {
        this.shooter = shooter;
        this.mobArrowDamage = damage;
        return this;
    }

    @Override
    public void update(float deltaTime) {
        age += deltaTime;
        if (age > MAX_AGE) {
            alive = false;
            return;
        }

        // Block collision — the tip's path since the last update (OpenMason #117): any block surface,
        // door or model, or water, between where the arrow was and where its tip is now. A
        // full-draw arrow covers more than two blocks per server tick; a point test at the tip
        // let it pass through thin ones.
        if (world != null && velocity.lengthSquared() > 0.0f) {
            Vector3f front = new Vector3f(velocity).normalize().mul(length * 0.5f);
            Vector3d tip = new Vector3d(position).add(front);
            Vector3d path = new Vector3d(tip).sub(lastPosition);
            double distance = path.length();
            if (distance > 0.0 && com.stonebreak.world.physics.WorldPhysics.of(world).raycast(lastPosition.x, lastPosition.y, lastPosition.z,
                    path.x, path.y, path.z, distance, com.stonebreak.world.physics.WorldPhysics.PROJECTILE_MASK,
                    com.stonebreak.world.physics.WorldPhysics.WATER, rayHit)) {
                alive = false;
                return;
            }
        }

        if (shooter != null ? strikePlayer() || strikeMob() : strikeEntity()) {
            alive = false;
            return;
        }
        lastPosition.set(position);
        // Counter-act most of the engine gravity so arrows arc gently rather than
        // dropping steeply. Entity.GRAVITY = -40; net effective gravity ≈ -10.
        velocity.y += 30.0f * deltaTime;
    }

    /**
     * A mob's arrow: hurts the player it is inside. On the server world that is any player in the
     * roster, through their server-owned health (#343) — never the host's client player read
     * from the tick thread. A world with no roster (one the client simulates itself) has only
     * the local player.
     */
    private boolean strikePlayer() {
        PlayerLocator players = roster();
        if (players != null) {
            return players.hitPlayerAt(position, HIT_RADIUS, mobArrowDamage, -1);
        }
        if (!hitsLocalPlayer()) {
            return false;
        }
        Game.getPlayer().damage(mobArrowDamage);
        return true;
    }

    /**
     * A mob's arrow that missed every player: hits the first other mob on its path — a goblin's
     * quarry, a rival goblin, or a cow that wandered into the line of fire. Never the shooter or
     * one of its allies.
     */
    private boolean strikeMob() {
        EntityManager em = OwningEntityManager.of(this);
        if (em == null) {
            return false;
        }
        LivingEntity le = ProjectileSweep.firstHit(em, lastPosition, position, ENTITY_HIT_MARGIN,
                e -> e != shooter && !shooter.isAllyOf(e));
        if (le == null) {
            return false;
        }
        le.damage(mobArrowDamage, LivingEntity.DamageSource.MOB, DamageType.PIERCING,
                shooter.getPosition(), false);
        return true;
    }

    /** A player's arrow: damages the first living entity on the path it just flew. */
    private boolean strikeEntity() {
        // Use the OWNING world's manager: server-spawned arrows must scan the server's
        // entities (the Game singleton resolves to the client render manager in the
        // two-world model).
        EntityManager em = OwningEntityManager.of(this);
        if (em == null) {
            return false;
        }
        float speedRatio = launchSpeed > 0 ? Math.min(launchSpeed / BowController.MAX_ARROW_SPEED, 1.0f) : 0.0f;
        float damage = BASE_DAMAGE + (MAX_DAMAGE - BASE_DAMAGE) * speedRatio;
        LivingEntity le = ProjectileSweep.firstHit(em, lastPosition, position, ENTITY_HIT_MARGIN);
        if (le == null) {
            // Another player, where the world allows PvP (#343); never the shooter.
            PlayerLocator players = roster();
            return players != null && getOwnerPlayerId() >= 0
                    && players.hitPlayerAt(position, ENTITY_HIT_MARGIN, damage, getOwnerPlayerId());
        }
        // The hit result goes to the shooter (KillCreditS2C with the target's network id
        // and ARROW source); their client builds Quarry/Study from it.
        ProjectileDamage.deal(this, le, damage, LivingEntity.DamageSource.ARROW);
        return true;
    }

    /** The connected players of this arrow's world, or null when it has none (not the server's). */
    private PlayerLocator roster() {
        PlayerLocator players =
                world != null ? world.getPlayerLocator() : null;
        return players == PlayerLocator.NONE ? null : players;
    }

    /** Whether the arrow is inside the local player's body box (feet position, standing height). */
    private boolean hitsLocalPlayer() {
        com.stonebreak.player.Player player = Game.getPlayer();
        if (player == null || player.isDead()) {
            return false;
        }
        Vector3d feet = player.getPosition();
        float halfWidth = com.stonebreak.player.PlayerConstants.PLAYER_WIDTH * 0.5f + HIT_RADIUS;
        return Math.abs(position.x - feet.x) <= halfWidth
                && Math.abs(position.z - feet.z) <= halfWidth
                && position.y >= feet.y - HIT_RADIUS
                && position.y <= feet.y + com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT + HIT_RADIUS;
    }

    /**
     * The direction the arrow is flying, for drawing it nose-first. Taken from how far it moved
     * since the previous call (once per rendered frame), because a client's copy is replicated
     * as position and yaw only: it has no velocity, and it would draw level without this. Before
     * the arrow has moved, it faces its yaw.
     */
    public Vector3f heading() {
        if (headingSample == null) {
            float yaw = (float) Math.toRadians(rotation.y);
            heading.set((float) -Math.sin(yaw), 0f, (float) -Math.cos(yaw));
            headingSample = new Vector3d(position);
            return heading;
        }
        float dx = (float) (position.x - headingSample.x);
        float dy = (float) (position.y - headingSample.y);
        float dz = (float) (position.z - headingSample.z);
        if (dx * dx + dy * dy + dz * dz > MIN_HEADING_STEP * MIN_HEADING_STEP) {
            heading.set(dx, dy, dz).normalize();
            headingSample.set(position);
        }
        return heading;
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering is handled externally by EntityRenderer
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public EntityType getType() {
        return EntityType.ARROW;
    }
}
