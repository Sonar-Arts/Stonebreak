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
