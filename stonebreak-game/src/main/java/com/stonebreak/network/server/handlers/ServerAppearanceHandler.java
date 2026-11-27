package com.stonebreak.network.server.handlers;

import com.stonebreak.network.packet.player.PlayerAppearanceC2S;
import com.stonebreak.network.packet.player.PlayerAppearanceS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.player.appearance.CosmeticCatalog;
import com.stonebreak.player.appearance.PlayerAppearance;

/**
 * Server-owned player looks (#459), modelled on server-owned health (#343): the server keeps every player's look
 * ({@link ServerPlayer#appearance()}), saves it with the player, and is the only one that changes it. A client asks
 * ({@link PlayerAppearanceC2S}); the look is checked against the {@link CosmeticCatalog} (an unknown item, an item in
 * the wrong slot or a slot the catalog lacks refuses the whole change, the sender gets its current look back) and an
 * accepted one goes to every player in the world ({@link PlayerAppearanceS2C}). An OFFER - the client's preferred look,
 * sent after joining - is taken only for a player whose look was never set (a new player, a save from before #459).
 */
public final class ServerAppearanceHandler {

    private final CosmeticCatalog catalog;

    public ServerAppearanceHandler(CosmeticCatalog catalog) {
        this.catalog = catalog;
    }

    public ServerAppearanceHandler() {
        this(CosmeticCatalog.get());
    }

    /** A joining player learns its own look (the others' ride {@code PlayerJoinS2C}). */
    public void onPeerJoined(ServerPlayer joined, ServerWorldContext ctx) {
        joined.send(packet(joined), false);
    }

    /** A look request; {@code nowNs} for the rate limit. */
    public void handle(ServerPlayer sp, PlayerAppearanceC2S request, ServerWorldContext ctx, long nowNs) {
        if (!sp.handshakeDone()) {
            return;
        }
        if (request.offer() && sp.appearanceStored()) {
            return; // the player already has a look; its owner learned it on joining
        }
        if (!sp.allowLookChange(nowNs)) {
            sp.send(packet(sp), false);
            return;
        }
        PlayerAppearance look = checked(request.appearance());
        if (look == null) {
            sp.send(packet(sp), false); // refused: back to what the server keeps
            return;
        }
        sp.setAppearance(look, true);
        ctx.broadcast(packet(sp), false);
    }

    /** {@code bytes} as a look the catalog accepts (missing slots at their defaults), or null. */
    PlayerAppearance checked(byte[] bytes) {
        PlayerAppearance look;
        try {
            look = PlayerAppearance.read(bytes);
        } catch (RuntimeException e) {
            return null;
        }
        if (look.problem(catalog) != null) return null;
        return look.withDefaults(catalog);
    }

    /** The look of a character-creation blob ({@code "appearance"} object); the defaults when absent or refused. */
    public PlayerAppearance fromCreation(byte[] creationJson, ServerPlayer sp) {
        String json = com.stonebreak.world.save.serialization.JsonPlayerSerializer.appearanceOf(creationJson);
        if (json != null) {
            PlayerAppearance look = checked(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (look != null) return look;
            System.err.println("[SERVER] " + sp.username() + ": character creation look refused; using the defaults");
        }
        return PlayerAppearance.defaults(catalog);
    }

    /** A saved look (omlook JSON) the catalog accepts, or null (none saved, unreadable or refused). */
    public static PlayerAppearance fromSave(String lookJson) {
        if (lookJson == null) return null;
        CosmeticCatalog catalog = CosmeticCatalog.get();
        try {
            PlayerAppearance look = PlayerAppearance.readJson(lookJson);
            if (look.problem(catalog) == null) return look.withDefaults(catalog);
            System.err.println("[SERVER] Ignoring a saved look the catalog refuses: " + look.problem(catalog));
        } catch (RuntimeException e) {
            System.err.println("[SERVER] Ignoring an unreadable saved look: " + e.getMessage());
        }
        return null;
    }

    /** {@code sp}'s look as a packet. */
    public static PlayerAppearanceS2C packet(ServerPlayer sp) {
        return new PlayerAppearanceS2C(sp.playerId(), sp.appearance().toBinary());
    }
}
