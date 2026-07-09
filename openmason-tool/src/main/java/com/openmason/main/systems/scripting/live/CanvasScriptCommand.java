package com.openmason.main.systems.scripting.live;

import com.openmason.main.systems.menus.textureCreator.commands.Command;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.layers.LayerStackSnapshot;

/**
 * One texture-editor undo entry for a whole script run's canvas edits:
 * before/after snapshots of the entire layer stack. The model side of the
 * same run is a separate {@link ScriptRunCommand} in the model history — the
 * two domains keep their own histories by design.
 */
public final class CanvasScriptCommand implements Command {

    private final LayerStackSnapshot before;
    private final LayerStackSnapshot after;
    private final LayerManager layerManager;
    private final Runnable notifyModified;

    public CanvasScriptCommand(LayerStackSnapshot before, LayerStackSnapshot after,
                               LayerManager layerManager, Runnable notifyModified) {
        this.before = before;
        this.after = after;
        this.layerManager = layerManager;
        this.notifyModified = notifyModified;
    }

    @Override
    public void execute() {
        after.restore(layerManager);
        notifyModified.run();
    }

    @Override
    public void undo() {
        before.restore(layerManager);
        notifyModified.run();
    }

    @Override
    public String getDescription() {
        return "Run Script (canvas)";
    }
}
