package com.openmason.engine.voxel.mms.mmsIntegration;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes block types to the appropriate geometry provider.
 *
 * <p>Providers are checked in registration order (first match wins).
 * Register SBO providers first, then legacy providers as fallback.
 * If no provider matches, the block is silently skipped.
 */
public class MmsBlockGeometryDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(MmsBlockGeometryDispatcher.class);

    private final List<MmsBlockGeometryProvider> providers = new ArrayList<>();

    /**
     * Register a geometry provider. Providers are checked in registration order.
     *
     * @param provider the provider to register
     */
    public void registerProvider(MmsBlockGeometryProvider provider) {
        providers.add(provider);
        logger.debug("Registered geometry provider: {}", provider.getClass().getSimpleName());
    }

    /**
     * Add geometry for a block by dispatching to the first matching provider.
     *
     * @param builder    mesh builder
     * @param blockType  the block type
     * @param lx         local X
     * @param ly         local Y
     * @param lz         local Z
     * @param chunkX     chunk X coordinate
     * @param chunkZ     chunk Z coordinate
     * @param chunkData  chunk data for neighbor lookups
     */
    public void addBlockGeometry(MmsMeshBuilder builder, IBlockType blockType,
                                 int lx, int ly, int lz, int chunkX, int chunkZ,
                                 CcoChunkData chunkData) {
        for (MmsBlockGeometryProvider provider : providers) {
            if (provider.handles(blockType)) {
                provider.addBlockGeometry(builder, blockType, lx, ly, lz, chunkX, chunkZ, chunkData);
                return;
            }
        }
        // No provider matched -- block is silently skipped (e.g., unknown block type)
    }

    /**
     * Get the number of registered providers.
     */
    public int getProviderCount() {
        return providers.size();
    }
}
