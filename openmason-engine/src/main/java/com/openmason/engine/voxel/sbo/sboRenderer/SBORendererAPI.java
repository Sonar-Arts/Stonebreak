package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Facade for the SBO block rendering pipeline.
 *
 * <p>Provides a single entry point for initializing and accessing the complete
 * SBO rendering system: stamp computation, caching, and chunk mesh emission.
 *
 * <h3>Initialization</h3>
 * <pre>{@code
 * SBORendererAPI sboRenderer = new SBORendererAPI();
 * sboRenderer.initialize(sboBlockMap, uvProvider);
 * }</pre>
 *
 * <h3>Chunk Mesh Generation</h3>
 * <pre>{@code
 * SBOStampEmitter emitter = sboRenderer.createEmitter(cullingPolicy);
 * // During chunk iteration:
 * if (emitter.hasBlock(blockType)) {
 *     emitter.emitBlock(builder, blockType, lx, ly, lz, worldX, worldY, worldZ, chunkData);
 * }
 * }</pre>
 */
public class SBORendererAPI {

    private static final Logger logger = LoggerFactory.getLogger(SBORendererAPI.class);

    private final SBOMeshProcessor meshProcessor;
    private final SBOStampCache stampCache;
    private boolean initialized = false;

    public SBORendererAPI() {
        this.meshProcessor = new SBOMeshProcessor();
        this.stampCache = new SBOStampCache();
    }

    /**
     * Initialize the SBO renderer by processing all SBO block definitions into stamps.
     *
     * <p>For each SBO block, computes flat normals, de-indexes geometry, remaps UVs
     * to atlas coordinates, and caches the result as a {@link com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp}.
     *
     * @param sboBlocks  map of block types to their parsed SBO definitions
     * @param uvProvider texture coordinate provider for atlas UV lookups
     * @return number of block types successfully processed
     */
    public int initialize(Map<? extends IBlockType, SBOParseResult> sboBlocks,
                          ITextureCoordProvider uvProvider) {
        int processed = 0;

        for (var entry : sboBlocks.entrySet()) {
            IBlockType blockType = entry.getKey();
            SBOParseResult sbo = entry.getValue();

            if (meshProcessor.process(blockType, sbo, uvProvider, stampCache)) {
                processed++;
            }
        }

        initialized = true;
        logger.info("SBO Renderer initialized: {} block types processed into stamps", processed);
        return processed;
    }

    /**
     * Create a stamp emitter for chunk mesh generation.
     *
     * @param cullingPolicy face culling strategy to use
     * @return a new emitter backed by this API's stamp cache
     */
    public SBOStampEmitter createEmitter(SBOCullingPolicy cullingPolicy) {
        return new SBOStampEmitter(stampCache, cullingPolicy);
    }

    /**
     * Check if a block type has SBO geometry.
     *
     * @param blockType the block type to check
     * @return true if an SBO stamp exists for this type
     */
    public boolean hasBlock(IBlockType blockType) {
        return stampCache.has(blockType);
    }

    /**
     * Get the stamp cache for direct access (e.g., by legacy providers).
     */
    public SBOStampCache getStampCache() {
        return stampCache;
    }

    /**
     * Get the underlying mesh processor.
     */
    public SBOMeshProcessor getMeshProcessor() {
        return meshProcessor;
    }

    /**
     * Whether the API has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the number of processed SBO block types.
     */
    public int size() {
        return stampCache.size();
    }
}
