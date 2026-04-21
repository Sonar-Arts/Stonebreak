/**
 * Stonebreak Game Module
 * 3D voxel-based sandbox game with model and texture systems
 */
module stonebreak.game {
    
    // LWJGL for graphics and input
    requires org.lwjgl;
    requires org.lwjgl.opengl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.openal;
    requires org.lwjgl.nanovg;
    requires org.lwjgl.stb;

    // Skija (Skia bindings). Maven coords: io.github.humbleui:skija-windows-x64,
    // which pulls skija-shared (Java classes) and types (Point/Rect/RRect) transitively.
    // These jars declare explicit module-info, so use the declared module names.
    requires io.github.humbleui.skija.shared;
    requires io.github.humbleui.types;
    
    // Math library
    requires org.joml;
    
    // OpenMason Engine for shared rendering and format support
    requires openmason.engine;

    // JSON processing
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.datatype.jsr310;

    // Logging
    requires org.slf4j;

    // Java base modules
    requires java.desktop;
    requires java.management;
    requires java.logging;
    requires java.sql;        // SQLite JDBC driver for FastLod persistent cache

    // JUnit 5 for testing (requires static = compile-time only)

    
    // Export packages for OpenMason tool access
    exports com.stonebreak.model;
    exports com.stonebreak.textures.atlas;
    exports com.stonebreak.textures.mobs;
    exports com.stonebreak.blocks;
    exports com.stonebreak.items;

    // Export rendering packages for OpenMason CBR API integration
    exports com.stonebreak.rendering.textures;
    exports com.stonebreak.rendering.core;
    exports com.stonebreak.rendering.core.API.commonBlockResources.models;
    exports com.stonebreak.rendering.core.API.commonBlockResources.resources;
    exports com.stonebreak.rendering.core.API.commonBlockResources.meshing;
    exports com.stonebreak.rendering.core.API.commonBlockResources.texturing;

    // Export item voxelization system for OpenMason
    exports com.stonebreak.rendering.player.items.voxelization;

    // Open packages for Jackson JSON processing
    opens com.stonebreak.model to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures.atlas to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures.mobs to com.fasterxml.jackson.databind;
    opens com.stonebreak.blocks to com.fasterxml.jackson.databind;
    opens com.stonebreak.items to com.fasterxml.jackson.databind;
    opens com.stonebreak.mobs.cow to com.fasterxml.jackson.databind;
    exports com.stonebreak.rendering.models.blocks;
    opens com.stonebreak.rendering.models.blocks to com.fasterxml.jackson.databind;

    // Open save system packages for Jackson serialization
    opens com.stonebreak.world.save.model to com.fasterxml.jackson.databind;
    opens com.stonebreak.world.save.repository to com.fasterxml.jackson.databind;
    opens com.stonebreak.world.save.serialization to com.fasterxml.jackson.databind;

    // Export and open packages for JUnit testing
    exports com.stonebreak.world.save to org.junit.platform.commons;
    exports com.stonebreak.world.chunk to org.junit.platform.commons;
    opens com.stonebreak.world.save to org.junit.platform.commons;
    opens com.stonebreak.world.chunk to org.junit.platform.commons;
    exports com.stonebreak.world.chunk.utils to org.junit.platform.commons;
    opens com.stonebreak.world.chunk.utils to org.junit.platform.commons;

    // Export and open remaining MMS packages (most moved to openmason-engine)
    exports com.stonebreak.world.chunk.api.mightyMesh.mmsCore;
    exports com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;
    exports com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;
    opens com.stonebreak.world.chunk.api.mightyMesh.mmsCore;
    opens com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;
    opens com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;
}
