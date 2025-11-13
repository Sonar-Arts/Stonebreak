/**
 * OpenMason Tool Module
 * Professional Voxel Game Engine & Toolset for Stonebreak with Dear ImGui-based interface
 */
module com.openmason {
    
    // LWJGL for OpenGL rendering and GLFW window management
    requires org.lwjgl;
    requires org.lwjgl.opengl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.stb;
    requires org.lwjgl.nfd;
    requires org.joml;
    
    // Dear ImGui for modern UI
    requires imgui.binding;
    requires imgui.lwjgl3;
    
    // Jackson for JSON processing (shared with Stonebreak)
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;

    // Apache Batik for SVG icon processing (automatic modules)
    requires batik.transcoder;
    requires batik.codec;

    // Logging
    requires org.slf4j;
    requires ch.qos.logback.classic;
    
    // Java base modules
    requires java.desktop;
    requires java.prefs;
    requires java.xml;
    
    // Stonebreak Game Module for model system access
    requires stonebreak.game;
    
    // Open packages for Jackson JSON processing
    opens com.openmason.ui.themes.core to com.fasterxml.jackson.databind;
    opens com.openmason.ui.themes.registry to com.fasterxml.jackson.databind;
    opens com.openmason.ui.themes.persistence to com.fasterxml.jackson.databind;
    opens com.openmason.ui.components.textureCreator.io to com.fasterxml.jackson.databind;

    // Export packages for potential future extensions
    exports com.openmason.app;
    exports com.openmason.ui;
    exports com.openmason.ui.themes.core;
    exports com.openmason.ui.themes.registry;
    exports com.openmason.ui.themes.persistence;
    exports com.openmason.ui.themes.application;
    exports com.openmason.ui.themes.preview;
    exports com.openmason.ui.themes.utils;
    exports com.openmason.deprecated;
    opens com.openmason.deprecated to com.fasterxml.jackson.databind;
}