package com.stonebreak.ui.debug;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.joml.Vector3f;

/**
 * Stateless text-formatting helpers shared by the F3 debug overlay panels:
 * byte sizes, short category/pool labels, truncation and compass headings.
 */
public final class DebugFormat {

    private DebugFormat() {
    }

    public static String shortCategoryName(GpuMemoryTracker.Category c) {
        return switch (c) {
            case CHUNK_MESH      -> "Chunk Meshes";
            case BUFFER_POOL_IDLE-> "Idle Pool";
            case TEXTURE_ATLAS   -> "Tex Atlas";
            case ENTITY_MESH     -> "Entity Meshes";
            case PLAYER_GEOMETRY -> "Player Geom";
            case SHADOW_MAP      -> "Shadow Maps";
            case OTHER           -> "Other";
        };
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s != null ? s : "";
        if (maxLen <= 3) return "...";
        return s.substring(0, maxLen - 3) + "...";
    }

    /** Trims long pool names like "Compressed Class Space" → "Compressed Class". */
    public static String shortPoolName(String name) {
        if (name == null) return "?";
        // ZGC reports "ZGC Young Generation" / "ZGC Old Generation" — keep it tight.
        String n = name.replace("ZGC ", "")
                       .replace(" Generation", " Gen")
                       .replace(" Space", "")
                       .replace("CodeHeap '", "Code: ")
                       .replace("'", "");
        return n;
    }

    public static String getCardinalDirection(Vector3f front) {
        // Calculate yaw from front vector
        // In OpenGL, -Z is forward, so we need to use atan2(-front.z, front.x)
        float yaw = (float) Math.toDegrees(Math.atan2(-front.z, front.x));

        // Normalize yaw to 0-360 degrees
        float normalizedYaw = ((yaw % 360) + 360) % 360;

        // Adjust for Minecraft coordinate system where:
        // North = -Z, South = +Z, East = +X, West = -X
        // Using 8 directions with 45-degree segments
        if (normalizedYaw >= 337.5 || normalizedYaw < 22.5) {
            return "East";
        } else if (normalizedYaw >= 22.5 && normalizedYaw < 67.5) {
            return "Northeast";
        } else if (normalizedYaw >= 67.5 && normalizedYaw < 112.5) {
            return "North";
        } else if (normalizedYaw >= 112.5 && normalizedYaw < 157.5) {
            return "Northwest";
        } else if (normalizedYaw >= 157.5 && normalizedYaw < 202.5) {
            return "West";
        } else if (normalizedYaw >= 202.5 && normalizedYaw < 247.5) {
            return "Southwest";
        } else if (normalizedYaw >= 247.5 && normalizedYaw < 292.5) {
            return "South";
        } else {
            return "Southeast";
        }
    }
}
