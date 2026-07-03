package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.openmason.engine.rendering.cbr.models.BlockDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges SBO object IDs to BlockType instances.
 * When an SBO block is registered, its texture data overrides
 * the legacy atlas coordinates for that BlockType.
 *
 * <p>Mapping is resolved dynamically via {@link BlockType#getByObjectId(String)} —
 * any SBO whose objectId resolves to a registered BlockType is bridged
 * automatically. Drop a new {@code .sbo} into {@code sbo/blocks/} and it
 * will be picked up here without code changes.
 */
public class SBOBlockBridge {

    private static final Logger logger = LoggerFactory.getLogger(SBOBlockBridge.class);

    private final Map<BlockType, SBOParseResult> sboByBlockType = new HashMap<>();

    /**
     * Lazily-parsed animation clips (1.6+), keyed "{blockTypeName}/{stateName}".
     * Raw {@code .omanim} bytes live in the parse result; decoding to a
     * {@link ParsedAnimClip} happens once on first request. A parse failure is
     * cached as absent so a corrupt clip doesn't re-log every frame.
     */
    private final Map<String, java.util.Optional<ParsedAnimClip>> clipCache = new ConcurrentHashMap<>();

    private final OMAReader omaReader = new OMAReader();

    /**
     * Initialize the bridge from a populated registry.
     * Maps each SBO's objectId to the corresponding BlockType via
     * {@link BlockType#getByObjectId(String)}.
     *
     * @param registry the loaded SBO block registry
     */
    public void initialize(SBOBlockRegistry registry) {
        sboByBlockType.clear();
        clipCache.clear();

        for (SBOParseResult result : registry.getAll()) {
            String objectId = result.getObjectId();
            BlockType blockType = BlockType.getByObjectId(objectId);
            if (blockType != null) {
                sboByBlockType.put(blockType, result);
                logger.info("Bridged SBO {} -> BlockType.{}", objectId, blockType.name());
            } else {
                logger.debug("No BlockType registered for SBO objectId: {}", objectId);
            }
        }

        logger.info("SBO bridge: {} blocks mapped", sboByBlockType.size());
    }

    /**
     * Check if a BlockType has an SBO override.
     *
     * @param blockType the block type to check
     * @return true if an SBO definition exists for this block type
     */
    public boolean isSBOBlock(BlockType blockType) {
        return sboByBlockType.containsKey(blockType);
    }

    /**
     * Get the SBO parse result for a BlockType.
     *
     * @param blockType the block type
     * @return the SBO data, or null if not an SBO block
     */
    public SBOParseResult getSBODefinition(BlockType blockType) {
        return sboByBlockType.get(blockType);
    }

    /**
     * Returns the OMO model bytes / parsed data for a specific state variant of
     * a BlockType — e.g. {@code getStateModel(FURNACE, "Lit")} resolves to the
     * lit furnace mesh embedded inside {@code SB_Furnace.sbo}.
     *
     * <p>Falls back to the SBO's {@code defaultStateName()} when {@code stateName}
     * is {@code null}, blank, or unknown. Returns {@code null} for SBOs that
     * declare no states at all — callers should then use the standard
     * {@link #getSBODefinition(BlockType)} path.
     *
     * <p>Engine-side note: the chunk mesher must consult {@link Chunk#getBlockState}
     * for each SBO block and pass the resulting state name here so the right
     * variant is bucketed during meshing. Until that wiring lands, this method
     * is callable but unused — the lit/unlit state is still persisted on the
     * chunk and survives save/load, but the rendered model won't actually swap.
     */
    public OMOReader.ReadResult getStateModel(BlockType blockType, String stateName) {
        SBOParseResult result = sboByBlockType.get(blockType);
        if (result == null || !result.hasStates()) return null;
        Map<String, OMOReader.ReadResult> byName = result.stateOmoData();
        if (byName == null || byName.isEmpty()) return null;

        OMOReader.ReadResult exact = (stateName != null && !stateName.isBlank())
                ? byName.get(stateName)
                : null;
        if (exact != null) return exact;

        String fallback = result.defaultStateName();
        return fallback != null ? byName.get(fallback) : null;
    }

    /**
     * Returns the name of the SBO state declared as default for a BlockType,
     * or {@code null} if the SBO has no state variants. Convenience for the
     * mesher when {@code Chunk.getBlockState(x,y,z)} returns {@code null}.
     */
    public String getDefaultStateName(BlockType blockType) {
        SBOParseResult result = sboByBlockType.get(blockType);
        return (result != null && result.hasStates()) ? result.defaultStateName() : null;
    }

    /**
     * True when the BlockType's SBO embeds at least one animation clip (1.6+).
     * Cheap manifest check — no clip decoding happens here. The renderer uses
     * this to decide whether a block needs the dynamic (entity-style) draw
     * path instead of being baked statically into the chunk mesh.
     */
    public boolean hasStateAnimations(BlockType blockType) {
        SBOParseResult result = sboByBlockType.get(blockType);
        return result != null && result.hasAnimations();
    }

    /**
     * The animation ref (metadata snapshot incl. the resolved loop flag) for a
     * BlockType's state, or {@code null} when that state has no clip (1.6+).
     * {@code loop() == false} means play-once-and-hold (door open/close);
     * {@code true} means wrap forever (fan spin, machine idle).
     */
    public SBOFormat.AnimationRef getStateAnimationRef(BlockType blockType, String stateName) {
        SBOParseResult result = sboByBlockType.get(blockType);
        return (result != null && stateName != null) ? result.animationFor(stateName) : null;
    }

    /**
     * The parsed animation clip for a BlockType's state, or {@code null} when
     * the state has no clip or it fails to decode (1.6+). Decoded lazily from
     * the embedded {@code .omanim} bytes and cached. The clip's tracks key by
     * partId with part-name hints, ready for {@code AnimSampler}/{@code
     * AnimLayering} exactly like SBE entity clips.
     *
     * <p>Note: the clip's own {@code loop()} flag is superseded by
     * {@link #getStateAnimationRef}'s loop flag, which carries the authored
     * loop/play-once choice from the export.
     */
    public ParsedAnimClip getStateClip(BlockType blockType, String stateName) {
        SBOParseResult result = sboByBlockType.get(blockType);
        if (result == null || stateName == null) return null;
        byte[] bytes = result.clipBytesFor(stateName);
        if (bytes == null) return null;

        String key = blockType.name() + "/" + stateName;
        return clipCache.computeIfAbsent(key, k -> {
            try {
                return java.util.Optional.of(omaReader.read(bytes));
            } catch (IOException e) {
                logger.error("Failed to decode animation clip for {} state '{}'",
                        blockType.name(), stateName, e);
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * Get the number of bridged SBO blocks.
     */
    public int size() {
        return sboByBlockType.size();
    }

    /**
     * Resolve the most permissive render layer explicitly declared by an
     * SBO's materials. TRANSLUCENT dominates CUTOUT.
     *
     * <p>Returns {@code null} when the SBO either isn't registered or its
     * materials only declare OPAQUE (or nothing at all). This lets the
     * caller fall back to legacy hardcoded rules for blocks whose SBO
     * doesn't opt into a non-opaque layer — important during the transition
     * period where older SBOs may be missing the renderLayer field.
     *
     * @param blockType the block type
     * @return the resolved render layer, or {@code null} if no non-opaque
     *         declaration was found
     */
    public BlockDefinition.RenderLayer getRenderLayer(BlockType blockType) {
        SBOParseResult result = sboByBlockType.get(blockType);
        if (result == null || result.materials() == null || result.materials().isEmpty()) {
            return null;
        }

        BlockDefinition.RenderLayer resolved = null;
        for (ParsedMaterialData material : result.materials()) {
            BlockDefinition.RenderLayer layer = parseRenderLayer(material.renderLayer());
            if (layer == BlockDefinition.RenderLayer.TRANSLUCENT) {
                return BlockDefinition.RenderLayer.TRANSLUCENT;
            }
            if (layer == BlockDefinition.RenderLayer.CUTOUT) {
                resolved = BlockDefinition.RenderLayer.CUTOUT;
            }
        }
        return resolved;
    }

    private static BlockDefinition.RenderLayer parseRenderLayer(String raw) {
        if (raw == null) return BlockDefinition.RenderLayer.OPAQUE;
        return switch (raw.trim().toUpperCase()) {
            case "TRANSLUCENT" -> BlockDefinition.RenderLayer.TRANSLUCENT;
            case "CUTOUT" -> BlockDefinition.RenderLayer.CUTOUT;
            default -> BlockDefinition.RenderLayer.OPAQUE;
        };
    }
}
