package com.openmason.engine.net.transport;

import io.netty.channel.local.LocalAddress;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * A transport-tagged address. Wraps either a Netty {@link LocalAddress} (in-JVM) or an
 * {@link InetSocketAddress} (TCP), so {@code NetServer}/{@code NetClient} can pick the
 * channel type and pipeline from the address alone.
 */
public final class NetAddress {

    private final TransportType type;
    private final SocketAddress address;

    private NetAddress(TransportType type, SocketAddress address) {
        this.type = type;
        this.address = address;
    }

    /** In-JVM address with a process-unique id. */
    public static NetAddress local(String id) {
        return new NetAddress(TransportType.LOCAL, new LocalAddress(id));
    }

    /** Remote TCP endpoint to connect to. */
    public static NetAddress tcp(String host, int port) {
        return new NetAddress(TransportType.TCP, new InetSocketAddress(host, port));
    }

    /** TCP bind address (all interfaces). Pass {@code 0} for an ephemeral port. */
    public static NetAddress tcpBind(int port) {
        return new NetAddress(TransportType.TCP, new InetSocketAddress(port));
    }

    public TransportType type() {
        return type;
    }

    public SocketAddress socketAddress() {
        return address;
    }

    @Override
    public String toString() {
        return type + ":" + address;
    }
}
