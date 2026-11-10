package com.stonebreak.fx.bindings;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.rendering.sbo.SBOTextureExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block FX from the block's own SBO ({@code fx[]}, SBO 1.12+) — the FX twin of {@code BlockSounds}
 * (Stonebreak#437). There is no block → effect table in code: a block whose SBO binds {@code break} to debris
 * throws debris, every other block throws nothing.
 *
 * <p>Event positions follow the sound calls they sit beside: {@code break}/{@code hit}/{@code place} at the
 * cell centre, {@code step} at the feet. Bindings with {@code tintFromTexture} get the block's texture colour
 * ({@link BlockTint}) as their {@code tint} parameter.
 *
 * <p>Callable from any thread: off the main thread the play is handed over. Resolution is lazy and cached per
 * block type; {@link #invalidate()} when the SBO bridge reloads.
 */
public final class BlockFx {

    private static final Logger logger = LoggerFactory.getLogger(BlockFx.class);

    /** Where a block's bindings and tint come from: the SBO bridge in the game, a stub in tests. */
    public interface Source {
        /** The block's FX bindings, or null while its definition is not available yet (not cached). */
        FxData fx(BlockType block);

        /** {@code 0xRRGGBBAA} sampled from the block's texture. */
        int tint(BlockType block);
    }

    private static final FxData NONE = new FxData(java.util.List.of());
    private static final Map<BlockType, FxData> DATA = new ConcurrentHashMap<>();
    private static final Map<BlockType, Integer> TINT = new ConcurrentHashMap<>();
    private static volatile Source source = new SboSource();

    private BlockFx() {
    }

    /** Destroyed at block coordinates. True when the block binds FX to the event (whether or not it played). */
    public static boolean breakAt(BlockType block, int x, int y, int z) {
        return play(block, FxData.EVENT_BREAK, x + 0.5, y + 0.5, z + 0.5, AssetFx.NORMAL);
    }

    /** Being mined, at block coordinates. */
    public static boolean hitAt(BlockType block, int x, int y, int z) {
        return play(block, FxData.EVENT_HIT, x + 0.5, y + 0.5, z + 0.5, AssetFx.NORMAL);
    }

    /** Placed at block coordinates. */
    public static boolean placeAt(BlockType block, int x, int y, int z) {
        return play(block, FxData.EVENT_PLACE, x + 0.5, y + 0.5, z + 0.5, AssetFx.NORMAL);
    }

    /** Stepped on, at the walker's feet (world position). */
    public static boolean stepAt(BlockType block, double feetX, double feetY, double feetZ) {
        return play(block, FxData.EVENT_STEP, feetX, feetY, feetZ, AssetFx.NORMAL);
    }

    /**
     * Any (possibly custom) event at a world position, {@code intensity} scaling the FX's {@code intensity}
     * parameter. True when the block binds FX to {@code event}, even if FX are switched off right now — callers
     * use it to retire a legacy effect the asset now owns.
     */
    public static boolean play(BlockType block, String event, double x, double y, double z, float intensity) {
        FxData data = data(block);
        if (!AssetFx.binds(data, event)) {
            return false;
        }
        if (!Fx.onMainThread()) {
            Fx.runOnMainThread(() -> play(block, event, x, y, z, intensity));
            return true;
        }
        FxRuntime runtime = Fx.runtime();
        if (runtime != null) {
            AssetFx.fire(runtime, data, event, x, y, z, tint(block), intensity);
        }
        return true;
    }

    /** The block's bindings ({@link FxData} with no entries when it has none). */
    public static FxData data(BlockType block) {
        if (block == null || block == BlockType.AIR) {
            return NONE;
        }
        FxData cached = DATA.get(block);
        if (cached != null) {
            return cached;
        }
        FxData loaded = source.fx(block);
        if (loaded == null) {
            return NONE; // the bridge is not ready yet: don't cache the miss
        }
        DATA.put(block, loaded);
        return loaded;
    }

    /** The block's texture colour for {@code tintFromTexture} bindings. */
    public static int tint(BlockType block) {
        Integer cached = TINT.get(block);
        if (cached != null) {
            return cached;
        }
        int tint;
        try {
            tint = source.tint(block);
        } catch (RuntimeException e) {
            logger.debug("No FX tint for {}: {}", block, e.getMessage());
            tint = BlockTint.FALLBACK;
        }
        TINT.put(block, tint);
        return tint;
    }

    /** Drops cached bindings and tints (the SBO bridge re-initialized). */
    public static void invalidate() {
        DATA.clear();
        TINT.clear();
        AssetFx.clearCache();
    }

    /** Replaces where bindings come from (tests); null restores the SBO bridge. */
    public static void setSource(Source next) {
        source = next == null ? new SboSource() : next;
        invalidate();
    }

    /** The game's source: the block's SBO through the renderer's bridge. */
    private static final class SboSource implements Source {
        private final SBOTextureExtractor textures = new SBOTextureExtractor();

        @Override
        public FxData fx(BlockType block) {
            SBOParseResult sbo = sbo(block);
            if (sbo == null) {
                return bridge() == null ? null : NONE;
            }
            FxData fx = sbo.fx();
            return fx != null ? fx : NONE;
        }

        @Override
        public int tint(BlockType block) {
            SBOParseResult sbo = sbo(block);
            if (sbo == null) {
                return BlockTint.FALLBACK;
            }
            // The material textures are what the block draws with (the default texture is often absent).
            java.util.List<java.awt.image.BufferedImage> materials = textures.extractMaterialTextures(sbo);
            return materials.isEmpty() ? BlockTint.of(textures.extractDefaultTexture(sbo)) : BlockTint.ofAll(materials);
        }

        private static SBOParseResult sbo(BlockType block) {
            SBOBlockBridge bridge = bridge();
            return bridge != null ? bridge.getSBODefinition(block) : null;
        }

        private static SBOBlockBridge bridge() {
            Renderer renderer = Game.getRenderer();
            return renderer != null ? renderer.getSBOBlockBridge() : null;
        }
    }
}
