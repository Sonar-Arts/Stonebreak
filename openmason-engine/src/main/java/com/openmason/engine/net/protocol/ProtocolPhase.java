package com.openmason.engine.net.protocol;

/**
 * Connection lifecycle phase. A connection starts in {@link #HANDSHAKE} and is flipped
 * to {@link #PLAY} once the handshake completes successfully.
 *
 * <p>The phase (held as a channel attribute) selects which slice of the
 * {@link PacketRegistry} decodes a given id, so the same numeric id can mean different
 * packets in different phases.
 */
public enum ProtocolPhase {
    HANDSHAKE,
    PLAY
}
