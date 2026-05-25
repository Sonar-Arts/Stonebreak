package com.openmason.engine.net.protocol;

/**
 * Direction of travel for a packet, relative to the server.
 *
 * <p>{@link #SERVERBOUND} packets are sent by clients and read by the server;
 * {@link #CLIENTBOUND} packets are sent by the server and read by clients. The pair
 * ({@link ProtocolPhase}, {@code PacketDirection}) partitions the {@link PacketRegistry}.
 */
public enum PacketDirection {
    SERVERBOUND,
    CLIENTBOUND
}
