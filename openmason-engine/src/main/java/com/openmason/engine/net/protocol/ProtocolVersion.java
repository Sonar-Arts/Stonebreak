package com.openmason.engine.net.protocol;

/**
 * Wire protocol version. Bumped whenever the packet layout changes incompatibly; the
 * server rejects clients whose advertised version differs from {@link #CURRENT} during
 * the handshake.
 *
 * <p>Reset to {@code 1} for the Netty rewrite — the wire format is entirely new, so the
 * legacy version counter does not carry over.
 */
public final class ProtocolVersion {

    /** Current wire protocol version. 2 = multiplayer refinement batch (keepalive,
     *  time sync, chunk meta payload, player state flags, projectile replication). */
    public static final int CURRENT = 2;

    private ProtocolVersion() {}
}
