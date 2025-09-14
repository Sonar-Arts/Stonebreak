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
    
    // LZ4 compression
    requires org.lz4.java;
    
    // Java base modules
    requires java.desktop;
    requires java.management;
    requires java.logging;
    
    // Export packages for OpenMason tool access
    exports com.stonebreak.model;
    exports com.stonebreak.textures.atlas;
    exports com.stonebreak.textures.loaders;
    exports com.stonebreak.textures.mobs;
    exports com.stonebreak.textures.utils;
    exports com.stonebreak.textures.validation;
    exports com.stonebreak.blocks;
    exports com.stonebreak.items;
    
    // Open packages for Jackson JSON processing
    opens com.stonebreak.model to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures.atlas to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures.loaders to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures.mobs to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures.utils to com.fasterxml.jackson.databind;
    opens com.stonebreak.textures.validation to com.fasterxml.jackson.databind;
    opens com.stonebreak.blocks to com.fasterxml.jackson.databind;
    opens com.stonebreak.items to com.fasterxml.jackson.databind;
    opens com.stonebreak.mobs.cow to com.fasterxml.jackson.databind;
    exports com.stonebreak.rendering.models.blocks;
    opens com.stonebreak.rendering.models.blocks to com.fasterxml.jackson.databind;
    
    // Open save system packages for Jackson serialization
    opens com.stonebreak.world.save.core to com.fasterxml.jackson.databind;
    opens com.stonebreak.world.save.storage.binary to com.fasterxml.jackson.databind;
    opens com.stonebreak.world.save.storage.providers to com.fasterxml.jackson.databind;
    opens com.stonebreak.world.save.managers to com.fasterxml.jackson.databind;
}