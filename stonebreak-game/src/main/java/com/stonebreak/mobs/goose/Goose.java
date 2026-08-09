package com.stonebreak.mobs.goose;

import org.joml.Vector3f;

import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityCollision;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobAI;
import com.stonebreak.mobs.entities.ai.behavior.FleeBehavior;
import com.stonebreak.mobs.entities.ai.behavior.FloatOnWaterBehavior;
import com.stonebreak.mobs.entities.ai.behavior.StandStillBehavior;
import com.stonebreak.mobs.entities.ai.behavior.WanderBehavior;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;

/**
 * Goose mob — a flighted passive creature.
 *
 * <p>On the ground it waddles, idles, floats on water, and flees the player; periodically it
 * takes off and joins/leads a {@link GooseFlock} flying in a V formation (see
 * {@link FlightBehavior}).
 * Rendered from the {@code SB_Goose.sbe} asset; see {@code Chicken} for the reference
 * SBE-driven mob pattern.
 *
 * <p><b>Flight physics:</b> while airborne the goose is {@linkplain #isSelfPropelled()
 * self-propelled}, so {@code EntityManager} skips the external physics step (gravity,
 * ground-snap, terrain collision) and {@link #applyPhysics(float)} integrates motion with no
 * gravity. On the ground it falls through to the standard {@link LivingEntity} physics path,
 * identical to the other passive mobs.
 */
public class Goose extends LivingEntity {

    /** Flap-hop velocity; one value reaches ~2 blocks like the chicken. */
    private static final float JUMP_VELOCITY = 12.0f;
    /** Light per-frame damping applied to airborne velocity (no gravity in flight). */
    private static final float FLIGHT_AIR_DAMPING = 0.99f;

    /** Turn rate on the ground; no hop boost — a goose that meets a ledge takes off instead. */
    private static final float ROTATION_SPEED = 200.0f;

    /** The behaviour that owns the goose from takeoff to touchdown; also drives its physics mode. */
    private final FlightBehavior flight;
    /** Per-axis world-block collision used only while airborne (thin wrapper over the world). */
    private final EntityCollision flightCollision;
    /** Set each airborne tick: whether the goose was blocked by a solid this tick. */
    private boolean flightBlockedHoriz;
    private boolean flightBlockedVert;

    public Goose(World world, Vector3f position) {
        super(world, position, EntityType.GOOSE);

        Steering steering = new Steering(this, ROTATION_SPEED, 0.0f, 0.0f);
        this.flight = new FlightBehavior(this, new FlightSteering(this, steering));

        // Goose personality: flies off given the chance, bolts from an approaching player, floats
        // when it finds water, and otherwise waddles about.
        this.mobAI = new MobAI(this, steering,
                flight,
                new FleeBehavior(10.0f, 4.0f, 1.0f, 5.0f, 7.0f, 11.0f),
                new FloatOnWaterBehavior(),
                StandStillBehavior.idle(0.45f, 3.0f, 7.0f),
                new WanderBehavior(0.55f, 3.0f, 8.0f, 0.7f));

        this.flightCollision = new EntityCollision(world);

        this.jumpVelocity = JUMP_VELOCITY;
        this.interactionRange = 2.0f;
        this.turnSpeed = 200.0f;
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering handled by EntityRenderer.
    }

    @Override
    public EntityType getType() {
        return EntityType.GOOSE;
    }

    @Override
    public void onInteract(Player player) {
        // No interaction behavior yet.
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
        for (ItemStack drop : getDrops()) {
            DropUtil.createItemDrop(world, getPosition(), drop);
        }
    }

    @Override
    public ItemStack[] getDrops() {
        if (Math.random() < 0.55) {
            int count = 1 + (int) (Math.random() * 3); // 1–3 feathers
            return new ItemStack[] { new ItemStack(ItemType.FEATHER, count) };
        }
        return new ItemStack[0];
    }

    @Override
    public int getXpReward() {
        return 3;
    }

    /**
     * Self-propelled while airborne so the {@code EntityManager} skips the external
     * physics step and {@link FlightBehavior} owns the full 3D motion.
     */
    @Override
    public boolean isSelfPropelled() {
        return flight.isAirborne();
    }

    /** Waterfowl: a goose plans routes that put it in deep water quite happily. */
    @Override
    public boolean canSwim() {
        return true;
    }

    /**
     * While airborne, integrate motion from velocity with no gravity (geese cruise above the
     * terrain) but resolve world-block collision per axis so the goose can never phase through
     * solids — it slides along walls instead, and the AI's stuck-recovery steers it clear. On
     * the ground, defer to the standard mob physics so behavior matches the other passive mobs.
     */
    @Override
    protected void applyPhysics(float deltaTime) {
        if (flight.isAirborne()) {
            age += deltaTime;
            if (flight.isTakeoffNoClipActive()) {
                position.fma(deltaTime, velocity); // free flight: lift clear of launch terrain
                flightBlockedHoriz = false;
                flightBlockedVert = false;
            } else {
                boolean[] blocked = flightCollision.moveAirborneWithCollision(this, deltaTime);
                flightBlockedHoriz = blocked[0];
                flightBlockedVert = blocked[1];
            }
            velocity.mul(FLIGHT_AIR_DAMPING);
        } else {
            flightBlockedHoriz = false;
            flightBlockedVert = false;
            super.applyPhysics(deltaTime);
        }
    }

    /** True if the goose's horizontal flight was blocked by a solid block this tick. */
    public boolean wasFlightBlockedHorizontally() {
        return flightBlockedHoriz;
    }

    /** True if the goose's vertical flight was blocked by a solid block (or ceiling) this tick. */
    public boolean wasFlightBlockedVertically() {
        return flightBlockedVert;
    }

    /** This goose's flight behaviour — its flock, and whether it is off the ground. */
    public FlightBehavior flight() {
        return flight;
    }
}
