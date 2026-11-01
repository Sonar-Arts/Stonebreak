/**
 * Open Mason engine voxel stack (#387): the voxel abstractions, CCO chunk data, the MMS mesher and region
 * renderer, voxel lighting, the SBO stamp renderer, Wayfind's voxel navigation domains, and voxel replication
 * on the wire ({@code net.replication}, {@code voxel.net}: VoxelChunkCodec, ChunkBlockPayload).
 * Builds on {@code openmason.engine.core} only.
 */
module openmason.engine.voxel {

    requires transitive openmason.engine.core;

    requires java.logging;

    exports com.openmason.engine.net.replication;
    exports com.openmason.engine.voxel;
    exports com.openmason.engine.voxel.cco.buffers;
    exports com.openmason.engine.voxel.cco.coordinates;
    exports com.openmason.engine.voxel.cco.core;
    exports com.openmason.engine.voxel.cco.data;
    exports com.openmason.engine.voxel.cco.data.palette;
    exports com.openmason.engine.voxel.cco.operations;
    exports com.openmason.engine.voxel.cco.performance;
    exports com.openmason.engine.voxel.cco.state;
    exports com.openmason.engine.voxel.lighting;
    exports com.openmason.engine.voxel.mms.mmsCore;
    exports com.openmason.engine.voxel.mms.mmsGeometry;
    exports com.openmason.engine.voxel.mms.mmsIntegration;
    exports com.openmason.engine.voxel.mms.mmsMetrics;
    exports com.openmason.engine.voxel.mms.mmsRegion;
    exports com.openmason.engine.voxel.mms.mmsTexturing;
    exports com.openmason.engine.voxel.net;
    exports com.openmason.engine.voxel.sbo;
    exports com.openmason.engine.voxel.sbo.sboRenderer;
    exports com.openmason.engine.wayfind.voxel;
}
