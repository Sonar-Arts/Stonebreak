package com.openmason.main.systems.menus.animationEditor.commands;

import com.openmason.main.systems.menus.textureCreator.commands.Command;

import java.util.List;

/**
 * Groups several commands into one undo/redo step. Executes in order, undoes
 * in reverse, so later commands may depend on earlier ones.
 */
public final class CompositeCommand implements Command {

    private final List<Command> commands;
    private final String description;

    public CompositeCommand(String description, List<Command> commands) {
        this.description = description != null ? description : "Composite";
        this.commands = List.copyOf(commands);
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    @Override
    public void execute() {
        for (Command c : commands) {
            c.execute();
        }
    }

    @Override
    public void undo() {
        for (int i = commands.size() - 1; i >= 0; i--) {
            commands.get(i).undo();
        }
    }

    @Override
    public String getDescription() {
        return description;
    }
}
