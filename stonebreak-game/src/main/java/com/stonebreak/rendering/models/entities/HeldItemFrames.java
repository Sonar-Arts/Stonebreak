package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.sprite.VoxelData;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.held.HeldGrip;
import com.stonebreak.items.held.HeldGrips;
import com.stonebreak.items.held.HeldKind;
import com.stonebreak.mobs.sbe.BodyMounts;
import com.stonebreak.mobs.sbe.HostSockets;
import com.openmason.engine.rig.SocketSet;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a player-shaped body's held object is (#458): its grip ({@link HeldGrips}) on the character socket the grip
 * names, on the body's posed palette - the frame the held-item pass draws it in and the held torch light, the fishing
 * line and the staff's bolts leave from. Every frame is the object's CANONICAL held frame in render space (a sprite
 * centred, one unit across, handle-to-tip along +Y; a block a unit cube on the origin; a model at its origin).
 */
public final class HeldItemFrames {

    /**
     * Sprite voxel-mesh space to the canonical held frame: recentre the mesh (it is built raised for the old hand
     * pose), scale a 16-pixel sprite to one unit, and turn the item sprites' bottom-left-to-top-right diagonal
     * (handle to tip) onto +Y. Also what mob socket items (a goblin's dagger) are drawn with.
     */
    public static final Matrix4fc SPRITE = new Matrix4f()
            .rotateZ((float) Math.toRadians(45.0))
            .scale(1.0f / (16 * SpriteVoxelizer.getSpriteScale()))
            .translate(0.0f, -SpriteVoxelizer.getVerticalOffset(), 0.0f);

    /** Per-sprite tip (the voxel farthest along +Y in the canonical frame); empty = not voxelizable. */
    private static final Map<ItemType, Optional<Vector3f>> TIPS = new ConcurrentHashMap<>();

    private HeldItemFrames() {
    }

    /** The mount the held object hangs on (#459): drawn by the held-item pass, not the attachment pass. */
    public static final String HELD_MOUNT = "held";
    /** The item point names the game reads (#459): a torch's flame, a rod's tip, a staff's head. */
    public static final String FLAME = "flame";
    public static final String TIP = "tip";
    public static final String HEAD = "head";

    /** Scratch mounts for bodies that carry none of their own (a preview, a test's local body). */
    private static final ThreadLocal<BodyMounts> SCRATCH = ThreadLocal.withInitial(BodyMounts::new);

    /** The local player's held object frame (render space), or null (empty hand, body not posed, no socket). */
    public static Matrix4f of(Player player, Matrix4f dest) {
        return player == null ? null : of(PosedPlayerBody.of(player), player.mounts(), player.getHeldItem(), dest);
    }

    /** A remote player's or decoy's held object frame (render space), or null. */
    public static Matrix4f of(RemotePlayer rp, Matrix4f dest) {
        return rp == null ? null : of(PosedPlayerBody.of(rp), rp.mounts(), rp.getHeldItem(), dest);
    }

    /** {@code base · K(socket joint) · socket · grip} on {@code body}, or null. */
    static Matrix4f of(PosedPlayerBody body, Item item, Matrix4f dest) {
        return of(body, SCRATCH.get(), item, dest);
    }

    /** The held object's frame on {@code body} through its {@code mounts} (the held mount kept in step), or null. */
    static Matrix4f of(PosedPlayerBody body, BodyMounts mounts, Item item, Matrix4f dest) {
        int index = resolve(body, mounts, item);
        return index < 0 ? null : mounts.frame(index, dest);
    }

    /**
     * The world position of the held object's point {@code name} ({@link #FLAME}, {@link #TIP}, {@link #HEAD}) on the
     * local player's body, or null (nothing held, body not posed, no such point).
     */
    public static Vector3d point(Player player, String name, Vector3d dest) {
        return player == null ? null : point(PosedPlayerBody.of(player), player.mounts(), player.getHeldItem(), name, dest);
    }

    /** {@link #point(Player, String, Vector3d)} for a remote player or decoy. */
    public static Vector3d point(RemotePlayer rp, String name, Vector3d dest) {
        return rp == null ? null : point(PosedPlayerBody.of(rp), rp.mounts(), rp.getHeldItem(), name, dest);
    }

    static Vector3d point(PosedPlayerBody body, BodyMounts mounts, Item item, String name, Vector3d dest) {
        int index = resolve(body, mounts, item);
        if (index < 0) return null;
        Vector3f render = mounts.pointPosition(index, name, new Vector3f());
        return render == null ? null : RenderOrigin.toWorld(render, dest);
    }

    /** Keeps the held mount on {@code item} and resolves the body's mounts; the held mount's index, or -1. */
    private static int resolve(PosedPlayerBody body, BodyMounts mounts, Item item) {
        sync(mounts, item);
        if (body == null || item == null || mounts.get(HELD_MOUNT) == null) return -1;
        SocketSet sockets = HostSockets.of(body.asset(), com.openmason.engine.entity.model.EntityAsset.DEFAULT_VARIANT);
        if (sockets == null) return -1;
        mounts.resolve(sockets, body.palette(), body.base(), body.base());
        return mounts.indexOf(HELD_MOUNT);
    }

    /** Puts {@code item} on the held mount (its grip, its points), or takes the mount off for an empty hand. */
    static void sync(BodyMounts mounts, Item item) {
        if (mounts.heldKey() == item && (item == null || mounts.get(HELD_MOUNT) != null)) return;
        HeldGrip grip = item == null ? null : HeldGrips.of(item);
        if (grip == null) {
            mounts.remove(HELD_MOUNT);
        } else {
            mounts.put(new BodyMounts.Mount(HELD_MOUNT, grip.socket(), null, grip.local(), null, points(item, grip)));
        }
        mounts.setHeldKey(item);
    }

    /**
     * The held points of {@code item}: its grip's authored points, plus an automatic {@link #TIP} for a sprite that
     * authors none (its voxel farthest along the handle-to-tip axis).
     */
    public static Map<String, Matrix4fc> points(Item item, HeldGrip grip) {
        Map<String, Matrix4fc> points = new java.util.LinkedHashMap<>(grip.points());
        boolean hasTip = points.keySet().stream().anyMatch(TIP::equalsIgnoreCase);
        if (!hasTip && item instanceof ItemType type && HeldKind.of(item) != HeldKind.BLOCK
                && HeldKind.of(item) != HeldKind.MODEL) {
            Vector3f tip = spriteTip(type);
            if (tip != null) points.put(TIP, new Matrix4f().translation(tip));
        }
        return points;
    }

    /** A canonical-frame point of a held object under {@code frame} (render space), as a world position. */
    public static Vector3d worldPoint(Matrix4fc frame, Vector3fc canonicalPoint, Vector3d dest) {
        Vector3f render = frame.transformPosition(canonicalPoint, new Vector3f());
        return RenderOrigin.toWorld(render, dest);
    }

    /** A point of a sprite's voxel mesh in the canonical held frame. */
    public static Vector3f spritePoint(Vector3fc meshPoint, Vector3f dest) {
        return SPRITE.transformPosition(meshPoint, dest);
    }

    /**
     * The tip of {@code type}'s sprite in the canonical held frame (its voxel farthest along +Y, i.e. along the
     * handle-to-tip axis): the rod's line anchor, a torch's flame. Null when the sprite cannot be voxelized.
     */
    public static Vector3f spriteTip(ItemType type) {
        if (type == null) return null;
        return TIPS.computeIfAbsent(type, HeldItemFrames::locateTip).map(Vector3f::new).orElse(null);
    }

    private static Optional<Vector3f> locateTip(ItemType type) {
        SpriteVoxelizer.VoxelizationResult result = SpriteVoxelizer.voxelizeSpriteWithPalette(type, null);
        if (result == null || !result.isValid() || result.getVoxels().isEmpty()) return Optional.empty();
        Vector3f best = null;
        Vector3f p = new Vector3f();
        for (VoxelData voxel : result.getVoxels()) {
            SPRITE.transformPosition(voxel.getPosition(), p);
            if (best == null || p.y > best.y) best = new Vector3f(p);
        }
        return Optional.ofNullable(best);
    }
}
