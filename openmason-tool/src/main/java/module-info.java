/**
 * Open Mason Toolset
 * Asset tools for Stonebreak with a Dear ImGui-based interface
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

    // Skija (Skia bindings) for high-quality 2D widget rendering composited into ImGui.
    // Maven coords: io.github.humbleui:skija-windows-x64, which pulls skija-shared
    // (Java classes) and types (Point/Rect/RRect) transitively. These jars declare
    // explicit module-info, so use the declared module names.
    requires io.github.humbleui.skija.shared;
    requires io.github.humbleui.types;
    
    // Jackson for JSON processing (shared with Stonebreak)
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;

    // Apache Batik for SVG icon processing (automatic modules)
    requires org.apache.xmlgraphics.batik.transcoder;
    requires org.apache.xmlgraphics.batik.codec;

    // GraalPy for the scripting system (sandboxed Python `om` API).
    // The language/runtime modules are named explicitly — Graal does not support
    // MIXED placement (polyglot on the module path, Truffle on the classpath;
    // fails with "cannot access AbstractPolyglotImpl"), and IDE run configs only
    // put jars on the module path when a module-info requires them. The requires
    // closure drags truffle-api and friends along.
    requires org.graalvm.polyglot;
    requires org.graalvm.py;             // python-language
    requires org.graalvm.py.resources;   // embedded Python stdlib resources
    requires org.graalvm.truffle.runtime;

    // Logging
    requires org.slf4j;
    requires ch.qos.logback.classic;
    
    // Java base modules
    requires java.net.http;
    requires java.desktop;
    requires java.prefs;
    requires java.xml;
    requires jdk.httpserver;
    
    // OpenMason Engine for shared rendering and format support
    requires openmason.engine;

    // Stonebreak Game Module for model system access (temporary - Milestone 2 removes this)
    requires stonebreak.game;
    
    // Open packages for Jackson JSON processing
    opens com.openmason.main.systems.themes.core to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.themes.registry to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.themes.persistence to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.menus.textureCreator.io to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.menus.animationEditor.io to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.rendering.model.io.omo to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.mcp to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.io to com.fasterxml.jackson.databind;

    // Scripting system: Jackson serializes the script result/summary records;
    // GraalPy host interop reflects on the @HostAccess.Export bridge methods.
    opens com.openmason.main.systems.scripting to com.fasterxml.jackson.databind;
    opens com.openmason.main.systems.scripting.commands to com.fasterxml.jackson.databind;
    // Unqualified: the reflecting module differs across Graal versions
    // (org.graalvm.polyglot vs the Truffle host runtime).
    opens com.openmason.main.systems.scripting.python;

    // Plugin host (#379): plugins implement com.openmason.api.OpenMasonPlugin in their own module
    // ("provides com.openmason.api.OpenMasonPlugin with ...") on the module path.
    uses com.openmason.api.OpenMasonPlugin;

    // The plugin API...
    exports com.openmason.api;
    exports com.openmason.api.files;
    exports com.openmason.api.knowledge;
    exports com.openmason.api.lifecycle;
    exports com.openmason.api.mcp;
    exports com.openmason.api.menu;
    exports com.openmason.api.prefs;
    exports com.openmason.api.target;
    exports com.openmason.api.viewport;
    exports com.openmason.api.workspace;
    // ...and the editor packages its signatures and typical plugins use (MCP tool records and
    // schema helpers, keybind commands, workspace state, project, scripting, file types, templates).
    exports com.openmason.main.plugins;
    exports com.openmason.main.systems.mcp;
    exports com.openmason.main.systems.mcp.approval;
    exports com.openmason.main.systems.layout;
    exports com.openmason.main.systems.keybinds;
    exports com.openmason.main.systems.project;
    exports com.openmason.main.systems.scripting;
    exports com.openmason.main.systems.io;
    exports com.openmason.main.systems.services;
    exports com.openmason.main.systems.menus.mainHub.model;
    exports com.openmason.main.systems.menus.textureCreator.keyboard;

    // Export packages for potential future extensions
    exports com.openmason.main;
    exports com.openmason.main.systems;
    exports com.openmason.main.systems.themes.core;
    exports com.openmason.main.systems.themes.registry;
    exports com.openmason.main.systems.themes.persistence;
    exports com.openmason.main.systems.themes.application;
    exports com.openmason.main.systems.themes.utils;
    exports com.openmason.main.systems.viewport;
}