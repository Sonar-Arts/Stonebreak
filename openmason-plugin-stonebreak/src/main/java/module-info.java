/**
 * Stonebreak's Open Mason plugin (#382, epic #390): everything the editor knows about Stonebreak.
 * Depends on the tool's plugin API and on the game; the tool never depends on this module.
 */
module openmason.plugin.stonebreak {
    requires com.openmason;
    requires openmason.engine.core;
    requires openmason.engine.ui;
    requires stonebreak.game;

    requires org.lwjgl;
    requires org.lwjgl.opengl;
    requires org.joml;
    requires io.github.humbleui.skija.shared;
    requires io.github.humbleui.types;
    requires imgui.binding;
    requires com.fasterxml.jackson.databind;
    requires java.desktop;
    requires org.slf4j;

    provides com.openmason.api.OpenMasonPlugin with com.stonebreak.openmason.StonebreakPlugin;
}
