package com.stonebreak.network.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Who may take a name at handshake (issue #335). Player ids are derived from the name, so a
 * newcomer whose name is online would replace that session; this decides when it may.
 * <ul>
 *   <li>The in-process host is never replaced, and always wins: a remote holding its name (or a
 *       name hashing to its id) is the one that leaves.</li>
 *   <li>A remote may take over a session only when that session's connection is already dead,
 *       or when it presents the session's <b>join secret</b>. Anything else is refused with
 *       {@link #ALREADY_ONLINE}: two friends who both kept the default name no longer kick each
 *       other.</li>
 *   <li>Different names that hash to the same id are refused as before.</li>
 * </ul>
 *
 * <p><b>Join secret.</b> The server issues a random secret on a player's first join (in the
 * {@code WelcomeS2C}), keeps it in that player's save ({@code clientSecret} in
 * {@code players/<name>.json}) and expects it back in the next {@code HandshakeC2S}; the client
 * stores it per host:port and name. It is NOT authentication — names are still unclaimed, and
 * a client joining an offline name without the secret is let in — it only proves "the same
 * client as the session it replaces", so a reconnect can take over its own half-dead session
 * while nobody else can. A join that does not present the stored secret is issued a new one,
 * so the secret always belongs to the client that joined last.
 */
final class NameClaims {

    static final String HOST_NAME = "That name is in use by the host.";
    static final String ALREADY_ONLINE = "A player with that name is already online.";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 18; // 24 base64url characters

    private NameClaims() {
    }

    /**
     * Why {@code newcomer} may not replace {@code existing}, the roster entry that already holds
     * its player id; null when it may (a takeover, or the host evicting a remote).
     */
    static String takeoverRefusal(ServerPlayer existing, ServerPlayer newcomer) {
        if (newcomer.isLocal()) {
            return null;
        }
        if (existing.isLocal()) {
            return HOST_NAME;
        }
        if (!existing.username().equals(newcomer.username())) {
            return "That name is unavailable on this server (id collision with '"
                    + existing.username() + "').";
        }
        if (!existing.connection().isActive()) {
            return null; // its channel already died; the DISCONNECT just has not been drained
        }
        return secretMatches(newcomer.presentedSecret(), existing.clientSecret()) ? null : ALREADY_ONLINE;
    }

    /** The secret a joining remote player keeps: the stored one when it presented it, else a new one. */
    static String secretFor(String presented, String stored) {
        return secretMatches(presented, stored) ? stored : issueSecret();
    }

    static boolean secretMatches(String presented, String stored) {
        if (presented == null || stored == null || presented.isEmpty() || stored.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
    }

    static String issueSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
