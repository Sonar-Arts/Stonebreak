package com.openmason.engine.net.protocol;

/**
 * Marker interface for all network packets.
 *
 * <p>Packets are immutable carriers of wire data — typically records in the game module.
 * Each concrete packet has an associated {@link PacketCodec} registered in a
 * {@link PacketRegistry} under a ({@link ProtocolPhase}, {@link PacketDirection}) pair.
 *
 * <p>The engine framework is game-agnostic: it never references concrete packet types,
 * only this marker and the registry.
 */
public interface Packet {
}
