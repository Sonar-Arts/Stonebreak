/**
 * OpenMason Tool Module
 * Professional 3D Model Development Tool for Stonebreak with 1:1 rendering parity
 */
module com.openmason {
    
    // JavaFX requirements
    requires javafx.controls;
    requires javafx.fxml;
    
    // DriftFX for hardware-accelerated 3D viewport (TODO: integrate in future)
    // requires de.saxsys.driftfx;
    
    // LWJGL for 3D rendering (identical to Stonebreak)
    requires org.lwjgl;
    requires org.lwjgl.opengl;
    requires org.lwjgl.glfw;
    
    // JOML for 3D mathematics (shared with Stonebreak)
    requires org.joml;
    
    // Jackson for JSON processing (shared with Stonebreak)
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    
    // Logging
    requires org.slf4j;
    requires ch.qos.logback.classic;
    
    // Java base modules
    requires java.desktop;
    requires java.prefs;
    
    // Open packages for JavaFX FXML access
    opens com.openmason.app to javafx.fxml;
    opens com.openmason.ui to javafx.fxml;
    
    // Open packages for Jackson JSON processing
    opens com.openmason.model to javafx.fxml, com.fasterxml.jackson.databind;
    opens com.openmason.texture to javafx.fxml, com.fasterxml.jackson.databind;
    opens com.openmason.model.stonebreak to com.fasterxml.jackson.databind;
    opens com.openmason.texture.stonebreak to com.fasterxml.jackson.databind;
    
    // Export packages for potential future extensions
    exports com.openmason.app;
    exports com.openmason.ui;
    exports com.openmason.model;
    exports com.openmason.texture;
    exports com.openmason.model.stonebreak;
    exports com.openmason.texture.stonebreak;
}