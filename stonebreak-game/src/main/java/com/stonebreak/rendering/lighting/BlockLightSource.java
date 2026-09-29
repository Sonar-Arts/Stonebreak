package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.blocks.torch.TorchBlock;
import com.stonebreak.blocks.torch.TorchState;
import com.stonebreak.world.World;
import com.stonebreak.world.lighting.BlockOpacity;
import org.joml.Vector3f;

import java.util.Set;

/**
 * Every kind of placed block that emits a point light. All of them share one
 * pipeline — the nearest-{@link PointLightGlsl#MAX_LIGHTS} selection in
 * {@link DynamicLights}, per-source point shadows, bounce light, daylight
 * suppression and the shadow settings — and differ only in where their
 * candidates are indexed, when they are emitting, where the light sits and how
 * it glows. A new light block is a new constant here, nothing more.
 *
 * <p>Emission reads the world the renderer draws, so on a client it follows the
 * state replicated through {@code BlockStateS2C}.
 */
enum BlockLightSource {

    TORCH(TorchLight.PROFILE) {
        @Override Iterable<BlockPos> candidates(World world) {
            return world.getAnimatedBlockRegistry().positions();
        }

        @Override boolean isBlock(BlockType type) {
            return TorchBlock.isTorch(type);
        }

        @Override Vector3f emitterPosition(World world, BlockPos pos, Vector3f out) {
            return TorchState.parse(world.getBlockStateAt(pos.x(), pos.y(), pos.z()))
                    .emberPosition(pos.x(), pos.y(), pos.z(), out);
        }
    },

    /**
     * A lit furnace glows from its mouth. The furnace is a solid full block, so the
     * light sits just outside the mouth face rather than at the block centre, where
     * its own voxel would black it out in the shadow and bounce passes. A mouth
     * sealed by an opaque block emits nothing. Furnaces have no facing: the SBO
     * authors the mouth on the +Z (south) face in both states.
     */
    FURNACE(new LightProfile(1.00f, 0.62f, 0.28f, 1.15f, 2.3f, 0.7f)) {
        @Override Iterable<BlockPos> candidates(World world) {
            FurnaceStateRegistry registry = world.getFurnaceRegistry();
            return registry != null ? registry.positions() : Set.of();
        }

        @Override boolean isBlock(BlockType type) {
            return type == BlockType.FURNACE;
        }

        @Override boolean isEmitting(World world, BlockPos pos) {
            if (!super.isEmitting(world, pos)) return false;
            FurnaceState state = world.getFurnaceRegistry().get(pos);
            return state != null && state.isLit()
                    && !BlockOpacity.isOpaque(world.getBlockAt(pos.x(), pos.y(), pos.z() + 1));
        }

        @Override Vector3f emitterPosition(World world, BlockPos pos, Vector3f out) {
            return out.set(pos.x() + 0.5f, pos.y() + MOUTH_HEIGHT, pos.z() + 1f + MOUTH_STANDOFF);
        }
    };

    /** Height of the furnace mouth's centre above the block's base. */
    static final float MOUTH_HEIGHT = 0.5f;
    /** How far in front of the furnace's mouth face its light sits. */
    static final float MOUTH_STANDOFF = 0.15f;

    private final LightProfile profile;

    BlockLightSource(LightProfile profile) {
        this.profile = profile;
    }

    LightProfile profile() {
        return profile;
    }

    /** Positions that may hold this source; {@link DynamicLights} distance-filters them first. */
    abstract Iterable<BlockPos> candidates(World world);

    abstract boolean isBlock(BlockType type);

    /** World-space point the light is emitted from. */
    abstract Vector3f emitterPosition(World world, BlockPos pos, Vector3f out);

    /** True when {@code pos} holds this source and it is currently giving off light. */
    boolean isEmitting(World world, BlockPos pos) {
        return isBlock(world.getBlockAt(pos.x(), pos.y(), pos.z()));
    }

    /** The source a block type belongs to, or {@code null} when it emits no light. */
    static BlockLightSource of(BlockType type) {
        for (BlockLightSource source : VALUES) {
            if (source.isBlock(type)) return source;
        }
        return null;
    }

    static final BlockLightSource[] VALUES = values();
}
