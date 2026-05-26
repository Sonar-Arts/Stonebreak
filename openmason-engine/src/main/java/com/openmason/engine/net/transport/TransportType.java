package com.openmason.engine.net.transport;

/**
 * Underlying channel transport.
 *
 * <ul>
 *   <li>{@link #LOCAL} — in-JVM Netty Local channels; packet objects pass through with no
 *       serialization (singleplayer / co-located host's own player).</li>
 *   <li>{@link #TCP} — NIO sockets with length-framed, encoded packets (remote clients).</li>
 * </ul>
 */
public enum TransportType {
    LOCAL,
    TCP
}
