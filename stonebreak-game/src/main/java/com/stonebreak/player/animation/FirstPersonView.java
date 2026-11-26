package com.stonebreak.player.animation;

import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.rig.BoneMask;
import com.openmason.engine.rig.FirstPersonProjection;
import com.openmason.engine.rig.HeadBobStabilizer;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * The player character seen by its owner in first person (#458 plumbing for #462; OpenMason #161, Unreal 5.5's
 * first-person rendering): ONE character, three views. Per frame, after the body was posed:
 * <ol>
 *   <li>{@link #camera}: the eye point (the {@code eyes} socket, else the eye / head roles) through the head-bob
 *       stabilizer - the camera position; the look rotation stays the player's own.</li>
 *   <li>{@link #compose}: the first-person projection (own FOV, depth slab: never clips into a wall) and the
 *       first-person palette (the body plus the graph's {@code "view": "firstPerson"} layer, everything but the arms
 *       hidden), placed with the projection's pull.</li>
 *   <li>{@link #bodyAwareness}: optionally the body minus head and arms, for the owner looking down.</li>
 * </ol>
 * The world-space body ({@link PlayerBodyAnimator#palette()}) still draws every shadow. Nothing here draws: the
 * renderer switches to it once the player character carries its first-person clips ({@link #ready}), #462.
 * Allocation-free per frame.
 */
public final class FirstPersonView implements AutoCloseable {

    /** The first-person FOV (vertical degrees): the arms keep their size whatever the world FOV. */
    public static final float FOV_DEGREES = 70f;

    private final PlayerAnimationSet set;
    private final Rig rig;
    private final CharacterDefinition character;
    private final BoneMask firstPersonMask;
    private final BoneMask awarenessMask;
    private final HeadBobStabilizer stabilizer = new HeadBobStabilizer();
    private final FirstPersonProjection projection = new FirstPersonProjection().fovY(FOV_DEGREES);

    private final JointPalette firstPerson = new JointPalette();
    private final JointPalette awareness = new JointPalette();
    private final Matrix4f firstPersonProjection = new Matrix4f();
    private final Matrix4f pull = new Matrix4f();
    private final Matrix4f firstPersonBase = new Matrix4f();
    private final Matrix4f baseScratch = new Matrix4f();
    private final Vector3f eyes = new Vector3f();
    private final Vector3f camera = new Vector3f();

    /** A view over {@code set}'s character (it must carry a character definition). */
    public FirstPersonView(PlayerAnimationSet set) {
        if (set.character() == null) {
            throw new IllegalArgumentException(set.asset().objectId() + ": no character definition, no first person");
        }
        this.set = set;
        this.rig = set.rig();
        this.character = set.character();
        this.firstPersonMask = BoneMask.create(rig, 0f);
        this.awarenessMask = BoneMask.create(rig, 1f);
        character.viewMask(rig, CharacterDefinition.VIEW_FIRST_PERSON, firstPersonMask);
        character.viewMask(rig, CharacterDefinition.VIEW_BODY_AWARENESS, awarenessMask);
    }

    /**
     * Whether first person can be drawn from {@code set}'s character: its graph has a first-person view layer and
     * every first-person slot ({@code fp_*}) has a clip. False for the current SB_Player (#462 brings the clips).
     */
    public static boolean ready(PlayerAnimationSet set) {
        if (set.character() == null || set.graph().firstPersonLayerCount() == 0) return false;
        boolean any = false;
        for (String slot : set.graph().slots()) {
            if (!slot.startsWith("fp_")) continue;
            any = true;
            if (set.asset().clipBytesFor(slot) == null) return false;
        }
        return any;
    }

    /**
     * The camera position this frame: the posed body's eye point (body base {@code base}) through the stabilizer,
     * {@code dt} seconds after the last call. Valid until the next call.
     */
    public Vector3f camera(PlayerBodyAnimator body, Matrix4fc base, float dt) {
        // The body's palette is model space: the eye point comes out in model space, then the base places it.
        character.eyesPoint(rig, body.palette(), eyes);
        base.transformPosition(eyes);
        return stabilizer.update(eyes, baseScratch.set(base), dt, camera);
    }

    /**
     * The first-person pose for this frame: the projection for the camera {@code view} and the world projection
     * {@code worldProjection} ({@link #projection()}), and the first-person palette ({@link #palette()}: placed with
     * the projection's pull about the eye, everything but the arms hidden; its sockets mount held items in
     * first-person space).
     */
    public void compose(PlayerBodyAnimator body, Matrix4fc base, Matrix4f view, Matrix4f worldProjection) {
        projection.compute(view, worldProjection, firstPersonProjection, pull);
        pull.mul(base, firstPersonBase);
        body.instance().composeView(AnimGraphInstance.VIEW_FIRST_PERSON, firstPersonBase, firstPerson);
        firstPerson.hide(rig, firstPersonMask);
    }

    /** The body minus head and arms (world space, model base {@code base}), for the owner looking down. */
    public JointPalette bodyAwareness(PlayerBodyAnimator body, Matrix4fc base) {
        body.instance().composeView(AnimGraphInstance.VIEW_BODY, baseScratch.set(base), awareness);
        return awareness.hide(rig, awarenessMask);
    }

    /** The first-person projection {@link #compose} wrote. */
    public Matrix4f projection() {
        return firstPersonProjection;
    }

    /** The first-person palette {@link #compose} wrote (world space, base and pull included). */
    public JointPalette palette() {
        return firstPerson;
    }

    /** The first-person mask (1 = drawn): the arm branches. */
    public BoneMask firstPersonMask() {
        return firstPersonMask;
    }

    /** Forget the stabilizer's history (teleport, respawn). */
    public void reset() {
        stabilizer.reset();
    }

    public PlayerAnimationSet set() {
        return set;
    }

    @Override
    public void close() {
        stabilizer.close();
        firstPersonMask.close();
        awarenessMask.close();
    }
}
