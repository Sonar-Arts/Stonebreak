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
    
    // Math library
    requires org.joml;
    
    // JSON processing
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.datatype.jsr310;
    
    // Java base modules
    requires java.desktop;
    requires java.management;
    
    // Export packages for OpenMason tool access
    exports com.stonebreak.model;
    exports com.stonebreak.textures;
    exports com.stonebreak.blocks;
    exports com.stonebreak.items;
    
    // Open packages for Jackson JSON processing
    opens com.stonebreak.model to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures to com.fasterxml.jackson.databind;
    opens com.stonebreak.blocks to com.fasterxml.jackson.databind;
    opens com.stonebreak.items to com.fasterxml.jackson.databind;
    opens com.stonebreak.mobs.cow to com.fasterxml.jackson.databind;
    opens com.stonebreak.world.save to com.fasterxml.jackson.databind;
}