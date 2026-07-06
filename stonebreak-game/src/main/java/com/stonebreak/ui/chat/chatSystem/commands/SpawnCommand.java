package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Command to spawn living mobs in front of the player.
 * Usage: /spawn <mob> [count]
 *
 * Examples:
 * - /spawn cow
 * - /spawn sheep 3
 * - /spawn goose
 */
public class SpawnCommand implements ChatCommand {

    private static final int DEFAULT_COUNT = 1;
    private static final int MAX_COUNT = 50;

    /** Distance ahead of the player (along the camera's look direction) to spawn at. */
    private static final float SPAWN_DISTANCE = 3.0f;

    /** Count presets offered when the count argument is being typed. */
    private static final int[] COUNT_SUGGESTIONS = {1, 5, 10, 25, MAX_COUNT};

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        if (args.length == 0) {
            showUsage(messageManager);
            return;
        }

        // Resolve the mob type (accepts enum name like COW or display name like cow).
        EntityType type = findMob(args[0]);
        if (type == null) {
            messageManager.addMessage("Unknown mob: " + args[0], ChatColors.RED);
            messageManager.addMessage("Valid mobs: " + validMobList(), ChatColors.ORANGE);
            return;
        }

        // Parse optional count (default 1).
        int count = DEFAULT_COUNT;
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                messageManager.addMessage("Invalid count: " + args[1], ChatColors.RED);
                return;
            }
            if (count <= 0) {
                messageManager.addMessage("Count must be greater than 0!", ChatColors.RED);
                return;
            }
            if (count > MAX_COUNT) {
                messageManager.addMessage("Count too large! Maximum is " + MAX_COUNT, ChatColors.RED);
                return;
            }
        }

        Player player = Game.getPlayer();
        Vector3f front = player.getCamera().getFront();
        Vector3f base = new Vector3f(player.getPosition())
            .add(front.x * SPAWN_DISTANCE, front.y * SPAWN_DISTANCE, front.z * SPAWN_DISTANCE);

        EntityManager entityManager = Game.getEntityManager();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            // Jitter x/z slightly so multiple spawns don't stack on a single point.
            float jitterX = count > 1 ? (float) (Math.random() - 0.5) * 2.0f : 0f;
            float jitterZ = count > 1 ? (float) (Math.random() - 0.5) * 2.0f : 0f;
            Vector3f pos = new Vector3f(base).add(jitterX, 0f, jitterZ);
            Entity entity = entityManager.spawnEntity(type, pos);
            if (entity != null) {
                entity.setCommandSpawned(true);
                spawned++;
            }
        }

        if (spawned == count) {
            messageManager.addMessage("Spawned " + spawned + "x " + type.getDisplayName(), ChatColors.GREEN);
        } else if (spawned > 0) {
            messageManager.addMessage("Spawned " + spawned + "/" + count + "x " + type.getDisplayName()
                + " (some failed)", ChatColors.ORANGE);
        } else {
            messageManager.addMessage("Failed to spawn " + type.getDisplayName() + "!", ChatColors.RED);
        }
    }

    @Override
    public String getName() {
        return "spawn";
    }

    @Override
    public String getDescription() {
        return "Spawn a mob in front of you (/spawn <mob> [count])";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    /**
     * Find a living mob {@link EntityType} by enum name (COW) or display name (cow),
     * case-insensitively. Returns null for unknown or non-living types.
     */
    private EntityType findMob(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (EntityType type : EntityType.GLOSSARY_TYPES) {
            if (type.name().equalsIgnoreCase(name) || type.getDisplayName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    /** Comma-separated list of spawnable mob names for usage/error messages. */
    private String validMobList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < EntityType.GLOSSARY_TYPES.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(EntityType.GLOSSARY_TYPES[i].getDisplayName().toLowerCase());
        }
        return sb.toString();
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /spawn <mob> [count]", ChatColors.RED);
        messageManager.addMessage("Valid mobs: " + validMobList(), ChatColors.ORANGE);
        messageManager.addMessage("Examples:", ChatColors.ORANGE);
        messageManager.addMessage("  /spawn cow", ChatColors.ORANGE);
        messageManager.addMessage("  /spawn sheep 3", ChatColors.ORANGE);
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        // First argument (mob name).
        if (args.length == 0) {
            String lower = currentArg.toLowerCase();
            List<String> suggestions = new ArrayList<>();
            for (EntityType type : EntityType.GLOSSARY_TYPES) {
                String displayLower = type.getDisplayName().toLowerCase();
                if (displayLower.startsWith(lower)) {
                    suggestions.add(displayLower);
                }
            }
            return suggestions;
        }

        // Second argument (count).
        if (args.length == 1) {
            return getCountSuggestions(currentArg);
        }

        return Collections.emptyList();
    }

    /**
     * Suggest common count presets, filtered by what has been typed so far.
     * Matching is prefix-based and only applies to numeric (or empty) input.
     */
    private List<String> getCountSuggestions(String currentArg) {
        if (!currentArg.isEmpty() && !currentArg.chars().allMatch(Character::isDigit)) {
            return Collections.emptyList();
        }
        List<String> suggestions = new ArrayList<>();
        for (int count : COUNT_SUGGESTIONS) {
            String value = Integer.toString(count);
            if (value.startsWith(currentArg)) {
                suggestions.add(value);
            }
        }
        return suggestions;
    }
}
