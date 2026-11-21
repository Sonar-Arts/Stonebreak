package com.stonebreak.config;

import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.world.World;

/**
 * Pushes the player's view settings that size the cube stream into the live worlds: the rendered world and,
 * in singleplayer/host, the integrated server's world (it decides which cubes stream to the player, using its
 * own world config). Every server also gets both radii per player through {@code ViewDistanceC2S}
 * ({@code ClientWorldView.sendViewDistance}), so the client's REMOTE stream and the server plan the same shape.
 */
public final class ViewDistanceSettings {

    private ViewDistanceSettings() {
    }

    /** Applies {@link Settings#getVerticalRenderDistance()} to {@code rendered} and the integrated server's world. */
    public static void applyVerticalRenderDistance(World rendered) {
        int blocks = Settings.getInstance().getVerticalRenderDistance();
        if (rendered != null && rendered.getConfig() != null) {
            rendered.getConfig().setVerticalRenderDistance(blocks);
        }
        World authoritative = integratedServerWorld();
        if (authoritative != null && authoritative != rendered && authoritative.getConfig() != null) {
            authoritative.getConfig().setVerticalRenderDistance(blocks);
        }
    }

    /** The integrated server's world (singleplayer/host/showcase), or null. */
    public static World integratedServerWorld() {
        if (!MultiplayerSession.hasIntegratedServer()) {
            return null;
        }
        IntegratedServer server = MultiplayerSession.getServer();
        return server != null && server.worldContext() != null ? server.worldContext().world() : null;
    }
}
