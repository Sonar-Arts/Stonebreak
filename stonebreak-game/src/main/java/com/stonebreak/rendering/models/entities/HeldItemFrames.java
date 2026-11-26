package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.sprite.VoxelData;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.held.HeldGrip;
import com.stonebreak.items.held.HeldGrips;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.player.Player;
import com.stonebreak.player.animation.PlayerAnimationSet;
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

    /** The local player's held object frame (render space), or null (empty hand, body not posed, no socket). */
    public static Matrix4f of(Player player, Matrix4f dest) {
        return player == null ? null : of(PosedPlayerBody.of(player), player.getHeldItem(), dest);
    }

    /** A remote player's or decoy's held object frame (render space), or null. */
    public static Matrix4f of(RemotePlayer rp, Matrix4f dest) {
        return rp == null ? null : of(PosedPlayerBody.of(rp), rp.getHeldItem(), dest);
    }

    /** {@code base · K(socket joint) · socket · grip} on {@code body}, or null. */
    static Matrix4f of(PosedPlayerBody body, Item item, Matrix4f dest) {
        if (body == null || item == null) return null;
        HeldGrip grip = HeldGrips.of(item);
        if (grip == null) return null;
        PlayerAnimationSet.Socket socket = PlayerAnimationSet.player().socket(grip.socket());
        if (socket == null) return null;
        socket.frame(body.palette(), body.base(), dest);
        return dest.mul(grip.local());
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
