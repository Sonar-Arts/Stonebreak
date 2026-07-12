/**
 * OpenMason Engine Module
 * Shared rendering engine and file format library for Stonebreak and Open Mason
 */
module openmason.engine {

    // LWJGL for OpenGL rendering
    requires org.lwjgl;
    requires org.lwjgl.opengl;
    requires org.lwjgl.stb;
    requires org.lwjgl.openal; // OpenAL for the engine audio subsystem

    // Math library
    requires org.joml;

    // JSON processing
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;

    // Logging
    requires org.slf4j;

    // Netty (automatic modules) for the networking framework (com.openmason.engine.net).
    // buffer is transitive: ByteBuf appears in the exported PacketCodec API, so consumers
    // (the game module) read it through us without their own explicit requires.
    requires transitive io.netty.buffer;
    requires io.netty.common;
    requires io.netty.transport;
    requires io.netty.codec;
    requires io.netty.handler;

    // Java base modules
    requires java.desktop;
    requires java.logging;
    requires java.management; // JVM memory/GC beans for MemoryProfiler (diagnostics)

    // Export rendering API
    exports com.openmason.engine.rendering.api;
    exports com.openmason.engine.rendering.shaders;
    exports com.openmason.engine.rendering.sky;
    exports com.openmason.engine.rendering.sky.clouds;
    exports com.openmason.engine.rendering.shadow;
    exports com.openmason.engine.rendering.postfx;
    exports com.openmason.engine.rendering.postfx.effects;
    exports com.openmason.engine.rendering.model;
    exports com.openmason.engine.rendering.model.gmr;
    exports com.openmason.engine.rendering.model.gmr.core;
    exports com.openmason.engine.rendering.model.gmr.mapping;
    exports com.openmason.engine.rendering.model.gmr.geometry;
    exports com.openmason.engine.rendering.model.gmr.uv;
    exports com.openmason.engine.rendering.model.gmr.topology;
    exports com.openmason.engine.rendering.model.gmr.editable;
    exports com.openmason.engine.rendering.model.gmr.editable.ops;
    exports com.openmason.engine.rendering.model.gmr.mesh.edgeOperations;
    exports com.openmason.engine.rendering.model.gmr.extraction;
    exports com.openmason.engine.rendering.model.gmr.notification;
    exports com.openmason.engine.rendering.model.gmr.parts;
    exports com.openmason.engine.rendering.model.gmr.parts.shapes;

    // Export voxel abstractions
    exports com.openmason.engine.voxel;

    // Export networking framework (com.openmason.engine.net)
    exports com.openmason.engine.net.protocol;
    exports com.openmason.engine.net.protocol.codec;
    exports com.openmason.engine.net.replication;
    exports com.openmason.engine.net.pipeline;
    exports com.openmason.engine.net.transport;
    exports com.openmason.engine.net.server;
    exports com.openmason.engine.net.client;

    // Export CCO (Common Chunk Operations)
    exports com.openmason.engine.voxel.cco.core;
    exports com.openmason.engine.voxel.cco.data;
    exports com.openmason.engine.voxel.cco.data.palette;
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

    // Export audio subsystem (game-agnostic OpenAL sound system)
    exports com.openmason.engine.audio;

    // Export shared utilities (pure value/math types)
    exports com.openmason.engine.util;

    // Export low-level GL helpers (error handling, state save/restore, projection config)
    exports com.openmason.engine.rendering.gl;

    // Export CBR clean core (block definitions, registry/resource interfaces, mesh manager)
    exports com.openmason.engine.rendering.cbr.models;
    exports com.openmason.engine.rendering.cbr.resources;
    exports com.openmason.engine.rendering.cbr.meshing;

    // Export format classes
    exports com.openmason.engine.format.sbo;
    exports com.openmason.engine.format.sbe;
    exports com.openmason.engine.format.sbt;
    exports com.openmason.engine.format.omo;
    exports com.openmason.engine.format.omt;
    exports com.openmason.engine.format.oma;
    exports com.openmason.engine.format.mesh;
    exports com.openmason.engine.format.sound;

    // Open format packages for Jackson JSON processing
    opens com.openmason.engine.format.sbo to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sbe to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sound to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sbt to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.omo to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.omt to com.fasterxml.jackson.databind;
}
