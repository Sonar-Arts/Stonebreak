package com.stonebreak.blocks.anim;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.operations.WorldConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-{@link com.stonebreak.world.World} index of block positions whose SBO
 * embeds animation clips (1.6+, e.g. the oak door). These blocks are excluded
 * from the baked chunk mesh and drawn per-frame by the AnimatedBlockRenderer,
 * which needs a cheap way to find them — this registry is that index.
 *
 * <p>Follows the {@code FurnaceStateRegistry} lifecycle pattern: positions are
 * discovered from each chunk's per-block state map on chunk load (every
 * animated block writes a state string at placement), maintained live through
 * {@code World.setBlockAt}'s change hook, and dropped on chunk unload.
 *
 * <p>Purely an index — animation timing (clip start times) is renderer-local.
 * Thread-safe: chunk hooks and block changes may run off the render thread.
 */
public final class AnimatedBlockRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AnimatedBlockRegistry.class);
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    /** BlockTypes whose SBO embeds at least one animation clip. Computed once
     *  from the SBO manifests in {@link BlockRegistry} — no renderer needed. */
    private static volatile Set<BlockType> animatedTypes;

    private final Set<BlockPos> positions = ConcurrentHashMap.newKeySet();

    /** True when this block type's SBO declares per-state animation clips. */
    public static boolean isAnimatedType(BlockType type) {
        if (type == null) return false;
        Set<BlockType> types = animatedTypes;
        if (types == null) {
            types = computeAnimatedTypes();
            animatedTypes = types;
        }
        return types.contains(type);
    }

    private static synchronized Set<BlockType> computeAnimatedTypes() {
        if (animatedTypes != null) return animatedTypes;
        Set<BlockType> types = new HashSet<>();
        for (BlockRegistry.BlockEntry entry : BlockRegistry.getInstance().all()) {
            if (entry.sboData() != null && entry.sboData().hasAnimations()) {
                BlockType type = BlockType.getByObjectId(entry.objectId());
                if (type != null) {
                    types.add(type);
                }
            }
        }
        logger.info("Animated block types: {}", types);
        return Collections.unmodifiableSet(types);
    }

    /** All tracked animated-block positions (live concurrent view). */
    public Set<BlockPos> positions() {
        return positions;
    }

    /** Hook from {@code World.setBlockAt} — covers placement, breaking and
     *  network-applied changes on every world. */
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType now) {
        if (isAnimatedType(previous) && !isAnimatedType(now)) {
            positions.remove(new BlockPos(x, y, z));
        } else if (isAnimatedType(now)) {
            positions.add(new BlockPos(x, y, z));
        }
    }

    /**
     * Discover animated blocks in a freshly loaded/streamed chunk from its
     * per-block state map (cheap — the map only holds stateful positions, and
     * every animated block writes its state at placement).
     */
    public void onChunkLoaded(Chunk chunk) {
        int cx = chunk.getX();
        int cz = chunk.getZ();
        for (Map.Entry<Integer, String> e : chunk.getBlockStates().entrySet()) {
            int key = e.getKey();
            int lx = LocalBlockKey.x(key);
            int ly = LocalBlockKey.y(key);
            int lz = LocalBlockKey.z(key);
            if (isAnimatedType(chunk.getBlock(lx, ly, lz))) {
                positions.add(new BlockPos(cx * CHUNK_SIZE + lx, ly, cz * CHUNK_SIZE + lz));
            }
        }
    }

    public void onChunkUnloaded(Chunk chunk) {
        int xMin = chunk.getX() * CHUNK_SIZE;
        int zMin = chunk.getZ() * CHUNK_SIZE;
        int xMax = xMin + CHUNK_SIZE;
        int zMax = zMin + CHUNK_SIZE;
        Iterator<BlockPos> it = positions.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (p.x() >= xMin && p.x() < xMax && p.z() >= zMin && p.z() < zMax) {
                it.remove();
            }
        }
    }
}
