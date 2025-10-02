package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import org.joml.Vector3f;

/**
 * Command to adjust voxelized sprite transform
 */
public class VoxelAdjCommand implements ChatCommand {

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatsEnabled(messageManager)) {
            return;
        }

        if (args.length == 0) {
            showCurrentValues(messageManager);
            return;
        }

        if (args.length >= 3 && args.length <= 7) {
            try {
                applyTransform(args, messageManager);
            } catch (NumberFormatException e) {
                showUsage(messageManager);
            }
        } else {
            showUsage(messageManager);
        }
    }

    @Override
    public String getName() {
        return "voxeladj";
    }

    @Override
    public String getDescription() {
        return "Adjust voxelized sprite transform";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    private void applyTransform(String[] args, ChatMessageManager messageManager) {
        float x = Float.parseFloat(args[0]);
        float y = Float.parseFloat(args[1]);
        float z = Float.parseFloat(args[2]);

        if (args.length == 7) {
            // Full 7-parameter: x y z rotX rotY rotZ scale
            applyFullTransform(x, y, z, args, messageManager);
        } else if (args.length == 6) {
            // 6-parameter: x y z rotX rotY rotZ (no scale)
            applyRotationTransform(x, y, z, args, messageManager);
        } else if (args.length == 4) {
            // 4-parameter: x y z rotY (backward compatibility)
            applySingleRotationTransform(x, y, z, args, messageManager);
        } else {
            // 3-parameter: x y z only
            applyTranslationOnly(x, y, z, messageManager);
        }

        displayTransformInfo(messageManager);
        logDebugInfo(args);
    }

    private void applyFullTransform(float x, float y, float z, String[] args, ChatMessageManager messageManager) {
        float rotX = Float.parseFloat(args[3]);
        float rotY = Float.parseFloat(args[4]);
        float rotZ = Float.parseFloat(args[5]);
        float scale = Float.parseFloat(args[6]);

        VoxelizedSpriteRenderer.adjustTransform(x, y, z, rotX, rotY, rotZ, scale);
        messageManager.addMessage(
            String.format("Voxel transform adjusted: pos(%.3f, %.3f, %.3f) rot(%.1f°, %.1f°, %.1f°) scale(%.2f)",
                x, y, z, rotX, rotY, rotZ, scale),
            ChatColors.GREEN
        );
    }

    private void applyRotationTransform(float x, float y, float z, String[] args, ChatMessageManager messageManager) {
        float rotX = Float.parseFloat(args[3]);
        float rotY = Float.parseFloat(args[4]);
        float rotZ = Float.parseFloat(args[5]);

        VoxelizedSpriteRenderer.adjustTransformNoScale(x, y, z, rotX, rotY, rotZ);
        messageManager.addMessage(
            String.format("Voxel transform adjusted: pos(%.3f, %.3f, %.3f) rot(%.1f°, %.1f°, %.1f°)",
                x, y, z, rotX, rotY, rotZ),
            ChatColors.GREEN
        );
    }

    private void applySingleRotationTransform(float x, float y, float z, String[] args, ChatMessageManager messageManager) {
        float rotY = Float.parseFloat(args[3]);
        VoxelizedSpriteRenderer.adjustTransformSingleRotation(x, y, z, rotY);
        messageManager.addMessage(
            String.format("Voxel transform adjusted: pos(%.3f, %.3f, %.3f) rotY(%.1f°)",
                x, y, z, rotY),
            ChatColors.GREEN
        );
    }

    private void applyTranslationOnly(float x, float y, float z, ChatMessageManager messageManager) {
        VoxelizedSpriteRenderer.adjustTranslation(x, y, z);
        messageManager.addMessage(
            String.format("Voxel position adjusted: (%.3f, %.3f, %.3f)", x, y, z),
            ChatColors.GREEN
        );
    }

    private void displayTransformInfo(ChatMessageManager messageManager) {
        Vector3f baseTranslation = VoxelizedSpriteRenderer.getBaseTranslation();
        Vector3f adjustment = VoxelizedSpriteRenderer.getTranslationAdjustment();
        Vector3f finalTranslation = VoxelizedSpriteRenderer.getFinalTranslation();
        Vector3f baseRotation = VoxelizedSpriteRenderer.getBaseRotation();
        Vector3f rotationAdjustment = VoxelizedSpriteRenderer.getRotationAdjustment();
        Vector3f finalRotation = VoxelizedSpriteRenderer.getFinalRotation();
        float baseScale = VoxelizedSpriteRenderer.getBaseScale();
        float scaleAdjustment = VoxelizedSpriteRenderer.getScaleAdjustment();
        float finalScale = VoxelizedSpriteRenderer.getFinalScale();

        // Translation info
        messageManager.addMessage(
            String.format("Base translation: (%.3f, %.3f, %.3f)",
                baseTranslation.x, baseTranslation.y, baseTranslation.z),
            ChatColors.LIGHT_GRAY
        );
        messageManager.addMessage(
            String.format("Translation adjustment: (%.3f, %.3f, %.3f)",
                adjustment.x, adjustment.y, adjustment.z),
            ChatColors.YELLOW
        );
        messageManager.addMessage(
            String.format("Final translation: (%.3f, %.3f, %.3f)",
                finalTranslation.x, finalTranslation.y, finalTranslation.z),
            ChatColors.CYAN
        );

        // Rotation info
        messageManager.addMessage(
            String.format("Base rotation: (%.1f°, %.1f°, %.1f°)",
                baseRotation.x, baseRotation.y, baseRotation.z),
            ChatColors.LIGHT_GRAY
        );
        messageManager.addMessage(
            String.format("Rotation adjustment: (%.1f°, %.1f°, %.1f°)",
                rotationAdjustment.x, rotationAdjustment.y, rotationAdjustment.z),
            ChatColors.LIGHT_MAGENTA
        );
        messageManager.addMessage(
            String.format("Final rotation: (%.1f°, %.1f°, %.1f°)",
                finalRotation.x, finalRotation.y, finalRotation.z),
            ChatColors.LIGHT_GREEN
        );

        // Scale info
        messageManager.addMessage(
            String.format("Base scale: %.2f | Scale adjustment: %.2f | Final scale: %.2f",
                baseScale, scaleAdjustment, finalScale),
            ChatColors.ORANGE
        );
    }

    private void logDebugInfo(String[] args) {
        System.out.println("=== VOXELADJ DEBUG OUTPUT ===");
        System.out.printf("Command parameters: %d%n", args.length);
        for (int i = 0; i < args.length; i++) {
            System.out.printf("  [%d]: %s%n", i, args[i]);
        }

        Vector3f baseTranslation = VoxelizedSpriteRenderer.getBaseTranslation();
        Vector3f finalTranslation = VoxelizedSpriteRenderer.getFinalTranslation();
        Vector3f baseRotation = VoxelizedSpriteRenderer.getBaseRotation();
        Vector3f rotationAdjustment = VoxelizedSpriteRenderer.getRotationAdjustment();
        Vector3f finalRotation = VoxelizedSpriteRenderer.getFinalRotation();
        float baseScale = VoxelizedSpriteRenderer.getBaseScale();
        float scaleAdjustment = VoxelizedSpriteRenderer.getScaleAdjustment();
        float finalScale = VoxelizedSpriteRenderer.getFinalScale();

        System.out.printf("Translation - Base: (%.3f, %.3f, %.3f) | Final: (%.3f, %.3f, %.3f)%n",
            baseTranslation.x, baseTranslation.y, baseTranslation.z,
            finalTranslation.x, finalTranslation.y, finalTranslation.z);
        System.out.printf("Rotation - Base: (%.1f°, %.1f°, %.1f°) | Adjustment: (%.1f°, %.1f°, %.1f°) | Final: (%.1f°, %.1f°, %.1f°)%n",
            baseRotation.x, baseRotation.y, baseRotation.z,
            rotationAdjustment.x, rotationAdjustment.y, rotationAdjustment.z,
            finalRotation.x, finalRotation.y, finalRotation.z);
        System.out.printf("Scale - Base: %.3f | Adjustment: %.3f | Final: %.3f%n",
            baseScale, scaleAdjustment, finalScale);
        System.out.println("==============================");
    }

    private void showCurrentValues(ChatMessageManager messageManager) {
        messageManager.addMessage("Current voxel transform values:", ChatColors.WHITE);
        displayTransformInfo(messageManager);
        showUsage(messageManager);
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage options:", ChatColors.WHITE);
        messageManager.addMessage("  /voxeladj <x> <y> <z>                       - Position only", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /voxeladj <x> <y> <z> <rotY>                - Position + Y rotation", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /voxeladj <x> <y> <z> <rotX> <rotY> <rotZ>   - Position + XYZ rotation", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /voxeladj <x> <y> <z> <rotX> <rotY> <rotZ> <scale> - Full transform", ChatColors.LIGHT_GRAY);
    }
}
