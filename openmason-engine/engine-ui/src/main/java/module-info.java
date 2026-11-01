/**
 * Open Mason engine UI (#387): the OMUI/SBUI formats and runtime (element tree, cascade, Yoga layout, input,
 * bindings, Lua scripting, behavior graphs, animation, sprites, fidelity gates, budgets), theme-neutral Masonry
 * and its Skia render targets, and the Cenda Lua/Flex host bindings ({@code com.openmason.engine.ui.cenda}).
 * Builds on {@code openmason.engine.core} only.
 */
module openmason.engine.ui {

    requires transitive openmason.engine.core;

    // Skija (Skia bindings) for Masonry (#286). Transitive: Canvas, Font, Image and Typeface appear in
    // the exported Masonry API.
    requires transitive io.github.humbleui.skija.shared;
    requires transitive io.github.humbleui.types;

    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires java.logging;
    requires java.management;

    exports com.openmason.engine.format.omui;
    exports com.openmason.engine.format.omui.io;
    exports com.openmason.engine.format.sbui;
    exports com.openmason.engine.format.uiarchive;
    exports com.openmason.engine.ui.assets;
    exports com.openmason.engine.ui.assets.edit;
    exports com.openmason.engine.ui.assets.export;
    exports com.openmason.engine.ui.assets.live;
    exports com.openmason.engine.ui.cenda;
    exports com.openmason.engine.ui.data;
    exports com.openmason.engine.ui.diag;
    exports com.openmason.engine.ui.fidelity;
    exports com.openmason.engine.ui.graph;
    exports com.openmason.engine.ui.graph.edit;
    exports com.openmason.engine.ui.hosting;
    exports com.openmason.engine.ui.l10n;
    exports com.openmason.engine.ui.masonry;
    exports com.openmason.engine.ui.masonry.textures;
    exports com.openmason.engine.ui.rendering;
    exports com.openmason.engine.ui.runtime;
    exports com.openmason.engine.ui.runtime.access;
    exports com.openmason.engine.ui.runtime.anim;
    exports com.openmason.engine.ui.runtime.binding;
    exports com.openmason.engine.ui.runtime.input;
    exports com.openmason.engine.ui.runtime.layout;
    exports com.openmason.engine.ui.runtime.paint;
    exports com.openmason.engine.ui.runtime.style;
    exports com.openmason.engine.ui.runtime.widget;
    exports com.openmason.engine.ui.script;
    exports com.openmason.engine.ui.text;
    exports com.openmason.engine.ui.theme;

    // UI theme registry (#384): neutral surfaces/symbols; games contribute theirs as services
    uses com.openmason.engine.ui.theme.UiThemeProvider;
    uses com.openmason.engine.format.omui.UiVocabularyProvider;
}
