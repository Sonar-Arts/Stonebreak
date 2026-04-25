/**
 * OpenMason Engine Module
 * Shared rendering engine and file format library for Stonebreak and Open Mason
 */
module openmason.engine {

    // LWJGL for OpenGL rendering
    requires org.lwjgl;
    requires org.lwjgl.opengl;
    requires org.lwjgl.stb;

    // Math library
    requires org.joml;

    // JSON processing
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;

    // Logging
    requires org.slf4j;

    // Java base modules
    requires java.desktop;
    requires java.logging;

    // Export rendering API
    exports com.openmason.engine.rendering.api;
    exports com.openmason.engine.rendering.shaders;
    exports com.openmason.engine.rendering.model;
    exports com.openmason.engine.rendering.model.gmr;
    exports com.openmason.engine.rendering.model.gmr.core;
    exports com.openmason.engine.rendering.model.gmr.mapping;
    exports com.openmason.engine.rendering.model.gmr.geometry;
    exports com.openmason.engine.rendering.model.gmr.uv;
    exports com.openmason.engine.rendering.model.gmr.topology;
    exports com.openmason.engine.rendering.model.gmr.mesh;
    exports com.openmason.engine.rendering.model.gmr.mesh.edgeOperations;
    exports com.openmason.engine.rendering.model.gmr.mesh.faceOperations;
    exports com.openmason.engine.rendering.model.gmr.mesh.vertexOperations;
    exports com.openmason.engine.rendering.model.gmr.extraction;
    exports com.openmason.engine.rendering.model.gmr.notification;
    exports com.openmason.engine.rendering.model.gmr.parts;

    // Export voxel abstractions
    exports com.openmason.engine.voxel;

    // Export CCO (Common Chunk Operations)
    exports com.openmason.engine.voxel.cco.core;
    exports com.openmason.engine.voxel.cco.data;
    exports com.openmason.engine.voxel.cco.state;
    exports com.openmason.engine.voxel.cco.coordinates;
    exports com.openmason.engine.voxel.cco.operations;
    exports com.openmason.engine.voxel.cco.buffers;
    exports com.openmason.engine.voxel.cco.performance;

    // Export MMS (Mighty Mesh System) - being migrated
    exports com.openmason.engine.voxel.mms.mmsCore;
    exports com.openmason.engine.voxel.mms.mmsGeometry;
    exports com.openmason.engine.voxel.mms.mmsTexturing;
    exports com.openmason.engine.voxel.mms.mmsMetrics;
    exports com.openmason.engine.voxel.mms.mmsIntegration;
    exports com.openmason.engine.voxel.sbo;
    exports com.openmason.engine.voxel.sbo.sboRenderer;

    // Export lighting (heightmap + per-vertex sky/AO sampler, block-agnostic)
    exports com.openmason.engine.voxel.lighting;

    // Export diagnostics (GPU memory tracker, etc.)
    exports com.openmason.engine.diagnostics;

    // Export format classes
    exports com.openmason.engine.format.sbo;
    exports com.openmason.engine.format.sbe;
    exports com.openmason.engine.format.omo;
    exports com.openmason.engine.format.mesh;

    // Open format packages for Jackson JSON processing
    opens com.openmason.engine.format.sbo to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sbe to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.omo to com.fasterxml.jackson.databind;
}
