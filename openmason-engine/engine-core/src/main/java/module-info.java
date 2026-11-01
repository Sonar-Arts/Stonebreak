/**
 * Open Mason engine core (#387): file formats, rendering (GL, shaders, shadows, sky, postfx, GMR, viewer), audio,
 * the networking core, CEARL/VRAM, diagnostics, utilities and the generic Cenda native kernels (noise, zstd, the
 * library locator). Depends on no other engine module: the UI runtime ({@code openmason.engine.ui}) and the voxel
 * stack ({@code openmason.engine.voxel}) build on it.
 */
module openmason.engine.core {

    // LWJGL: OpenGL rendering, STB image loading, OpenAL audio. Transitive: GL handles, buffers and
    // the audio API appear in exported signatures.
    requires transitive org.lwjgl;
    requires transitive org.lwjgl.opengl;
    requires org.lwjgl.stb;
    requires org.lwjgl.openal;

    // Math (Vector3f/Matrix4f in exported rendering and viewer APIs)
    requires transitive org.joml;

    // JSON processing (format manifests)
    requires transitive com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;

    // Logging
    requires transitive org.slf4j;

    // Netty (automatic modules) for the networking framework (com.openmason.engine.net). buffer is
    // transitive: ByteBuf appears in the exported PacketCodec API.
    requires transitive io.netty.buffer;
    requires io.netty.common;
    requires io.netty.transport;
    requires io.netty.codec;
    requires io.netty.handler;

    // Java base modules
    requires transitive java.desktop; // BufferedImage in exported texture/format APIs
    requires java.logging;
    requires java.management; // JVM memory/GC beans for MemoryProfiler (diagnostics)

    exports com.openmason.engine.audio;
    exports com.openmason.engine.cearl;
    exports com.openmason.engine.cenda;
    exports com.openmason.engine.diagnostics;
    exports com.openmason.engine.entity.sbe;
    exports com.openmason.engine.format.ext;
    exports com.openmason.engine.format.mesh;
    exports com.openmason.engine.format.oma;
    exports com.openmason.engine.format.omo;
    exports com.openmason.engine.format.omsc;
    exports com.openmason.engine.format.omt;
    exports com.openmason.engine.format.sbe;
    exports com.openmason.engine.format.sbo;
    exports com.openmason.engine.format.sbt;
    exports com.openmason.engine.format.sound;
    exports com.openmason.engine.net.client;
    exports com.openmason.engine.net.pipeline;
    exports com.openmason.engine.net.protocol;
    exports com.openmason.engine.net.protocol.codec;
    exports com.openmason.engine.net.server;
    exports com.openmason.engine.net.transport;
    exports com.openmason.engine.rendering;
    exports com.openmason.engine.rendering.api;
    exports com.openmason.engine.rendering.blockdisplay;
    exports com.openmason.engine.rendering.gl;
    exports com.openmason.engine.rendering.lighting;
    exports com.openmason.engine.rendering.model;
    exports com.openmason.engine.rendering.model.gmr;
    exports com.openmason.engine.rendering.model.gmr.analysis;
    exports com.openmason.engine.rendering.model.gmr.core;
    exports com.openmason.engine.rendering.model.gmr.editable;
    exports com.openmason.engine.rendering.model.gmr.editable.ops;
    exports com.openmason.engine.rendering.model.gmr.extraction;
    exports com.openmason.engine.rendering.model.gmr.geometry;
    exports com.openmason.engine.rendering.model.gmr.mapping;
    exports com.openmason.engine.rendering.model.gmr.mesh.edgeOperations;
    exports com.openmason.engine.rendering.model.gmr.notification;
    exports com.openmason.engine.rendering.model.gmr.parts;
    exports com.openmason.engine.rendering.model.gmr.parts.shapes;
    exports com.openmason.engine.rendering.model.gmr.topology;
    exports com.openmason.engine.rendering.model.gmr.uv;
    exports com.openmason.engine.rendering.postfx;
    exports com.openmason.engine.rendering.postfx.effects;
    exports com.openmason.engine.rendering.shaders;
    exports com.openmason.engine.rendering.shadow;
    exports com.openmason.engine.rendering.sky;
    exports com.openmason.engine.rendering.sprite;
    exports com.openmason.engine.rendering.viewer;
    exports com.openmason.engine.rendering.viewer.camera;
    exports com.openmason.engine.rendering.viewer.gizmo;
    exports com.openmason.engine.rendering.viewer.gizmo.geometry;
    exports com.openmason.engine.rendering.viewer.gizmo.interaction;
    exports com.openmason.engine.rendering.viewer.gizmo.modes;
    exports com.openmason.engine.rendering.viewer.gizmo.rendering;
    exports com.openmason.engine.rendering.viewer.math;
    exports com.openmason.engine.rendering.viewer.passes;
    exports com.openmason.engine.rendering.viewer.picking;
    exports com.openmason.engine.rendering.viewer.scene;
    exports com.openmason.engine.rendering.viewer.transform;
    exports com.openmason.engine.util;
    exports com.openmason.engine.util.easing;
    exports com.openmason.engine.vram;
    exports com.openmason.engine.wayfind;

    // Open format packages for Jackson JSON processing
    opens com.openmason.engine.format.sbo to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sbe to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sound to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sbt to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.omo to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.omt to com.fasterxml.jackson.databind;
}
