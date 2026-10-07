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

    // Skija (Skia bindings) for the shared Masonry UI renderer (#286). Transitive: Canvas, Font,
    // Image and Typeface appear in the exported Masonry API.
    requires transitive io.github.humbleui.skija.shared;
    requires transitive io.github.humbleui.types;

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
    exports com.openmason.engine.cenda;
    exports com.openmason.engine.rendering;
    exports com.openmason.engine.rendering.api;
    exports com.openmason.engine.rendering.shaders;
    exports com.openmason.engine.rendering.sky;
    exports com.openmason.engine.rendering.sky.clouds;
    exports com.openmason.engine.rendering.shadow;
    exports com.openmason.engine.rendering.lighting;
    exports com.openmason.engine.rendering.postfx;
    exports com.openmason.engine.rendering.postfx.effects;
    exports com.openmason.engine.rendering.viewer;
    exports com.openmason.engine.rendering.viewer.camera;
    exports com.openmason.engine.rendering.viewer.gizmo;
    exports com.openmason.engine.rendering.viewer.passes;
    exports com.openmason.engine.rendering.viewer.scene;
    exports com.openmason.engine.rendering.viewer.picking;
    exports com.openmason.engine.rendering.viewer.gizmo.geometry;
    exports com.openmason.engine.rendering.viewer.gizmo.interaction;
    exports com.openmason.engine.rendering.viewer.gizmo.modes;
    exports com.openmason.engine.rendering.viewer.gizmo.rendering;
    exports com.openmason.engine.rendering.viewer.math;
    exports com.openmason.engine.rendering.viewer.transform;
    exports com.openmason.engine.rendering.model;
    exports com.openmason.engine.rendering.model.gmr;
    exports com.openmason.engine.rendering.model.gmr.core;
    exports com.openmason.engine.rendering.model.gmr.mapping;
    exports com.openmason.engine.rendering.model.gmr.geometry;
    exports com.openmason.engine.rendering.model.gmr.uv;
    exports com.openmason.engine.rendering.model.gmr.topology;
    exports com.openmason.engine.rendering.model.gmr.analysis;
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
    exports com.openmason.engine.voxel.mms.mmsRegion;
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

    // CEARL language + VRAM plan runtime
    exports com.openmason.engine.cearl;
    exports com.openmason.engine.vram;

    // Export audio subsystem (game-agnostic OpenAL sound system)
    exports com.openmason.engine.audio;

    // Export shared utilities (pure value/math types)
    exports com.openmason.engine.util;
    exports com.openmason.engine.util.easing;

    // Export Wayfind: the generic A* core and the voxel navigation rules built on it
    exports com.openmason.engine.wayfind;
    exports com.openmason.engine.wayfind.voxel;

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
    exports com.openmason.engine.format.omsc;
    exports com.openmason.engine.format.omt;
    exports com.openmason.engine.format.oma;
    exports com.openmason.engine.format.mesh;
    exports com.openmason.engine.format.sound;
    exports com.openmason.engine.format.omui;
    exports com.openmason.engine.format.omui.io;
    exports com.openmason.engine.format.sbui;
    exports com.openmason.engine.format.uiarchive;

    // UI asset resolution, embedding, export planning and live invalidation (#285)
    exports com.openmason.engine.ui.assets;
    exports com.openmason.engine.ui.assets.edit;
    exports com.openmason.engine.ui.assets.export;
    exports com.openmason.engine.ui.assets.live;

    // Masonry UI: widgets, painter, fonts, textures (#286) and render targets / GL state
    exports com.openmason.engine.ui.masonry;
    exports com.openmason.engine.ui.masonry.textures;
    exports com.openmason.engine.ui.rendering;
    // UI runtime (#287): element tree, cascade, widget descriptors, retained Yoga layout
    exports com.openmason.engine.ui.l10n;
    exports com.openmason.engine.ui.text;
    exports com.openmason.engine.ui.runtime;
    exports com.openmason.engine.ui.runtime.access;
    exports com.openmason.engine.ui.runtime.input;
    exports com.openmason.engine.ui.runtime.layout;
    exports com.openmason.engine.ui.runtime.paint;
    exports com.openmason.engine.ui.runtime.style;
    exports com.openmason.engine.ui.runtime.widget;
    // Typed host contracts (data sources, actions, scopes, edits) and bindings (#289)
    exports com.openmason.engine.ui.data;
    exports com.openmason.engine.ui.runtime.binding;
    // Lua code-behind and the animation sampler (#292)
    exports com.openmason.engine.ui.script;
    // UI fidelity baselines + migration gate, runtime budget diagnostics (#296)
    exports com.openmason.engine.ui.fidelity;
    exports com.openmason.engine.ui.diag;
    // Behavior graphs compiled to Lua, and their editing model (#291)
    exports com.openmason.engine.ui.graph;
    exports com.openmason.engine.ui.graph.edit;
    exports com.openmason.engine.ui.runtime.anim;

    // Open format packages for Jackson JSON processing
    opens com.openmason.engine.format.sbo to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sbe to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sound to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.sbt to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.omo to com.fasterxml.jackson.databind;
    opens com.openmason.engine.format.omt to com.fasterxml.jackson.databind;
}
