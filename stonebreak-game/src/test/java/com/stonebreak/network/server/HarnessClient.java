package com.stonebreak.network.server;

import com.openmason.engine.net.client.ClientInboundQueue;
import com.openmason.engine.net.client.NetworkClient;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolVersion;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.openmason.engine.net.transport.NetAddress;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.NeedsCharacterCreationS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.network.packet.player.CharacterCreationC2S;
import com.stonebreak.player.CharacterStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A raw protocol client for {@link ServerHarness} tests (issue #347): a real {@link NetworkClient}
 * over loopback TCP whose inbound packets are recorded as they arrive, with helpers for the join
 * flow. It plays no game — what it sends is exactly what a test sends.
 */
public final class HarnessClient implements AutoCloseable {

    /** How long a test waits for something the server should do promptly. */
    public static final Duration PROMPT = Duration.ofSeconds(15);

    private final NetworkClient net = new NetworkClient(StonebreakProtocol.registry());
    private final List<Packet> received = new ArrayList<>();
    private final Thread drainer;
    private volatile boolean disconnected;
    private volatile boolean closing;

    private HarnessClient(int port) throws InterruptedException {
        net.connect(NetAddress.tcp("127.0.0.1", port));
        drainer = new Thread(this::drainLoop, "Harness-Client");
        drainer.setDaemon(true);
        drainer.start();
    }

    /** A connected client that has not handshaken. */
    public static HarnessClient connect(ServerHarness server) throws InterruptedException {
        return new HarnessClient(server.port());
    }

    /** Connects, handshakes as {@code username} and creates a character if asked; waits for the welcome. */
    public static HarnessClient join(ServerHarness server, String username) throws InterruptedException {
        return join(server, username, "");
    }

    public static HarnessClient join(ServerHarness server, String username, String secret)
            throws InterruptedException {
        HarnessClient c = connect(server);
        c.handshake(username, secret);
        Packet first = c.awaitAny(p -> p instanceof NeedsCharacterCreationS2C || p instanceof WelcomeS2C
                || p instanceof KickS2C, PROMPT).orElseThrow(() -> new AssertionError(username + " got no answer"));
        if (first instanceof KickS2C k) {
            throw new AssertionError(username + " was kicked: " + k.reason());
        }
        if (first instanceof NeedsCharacterCreationS2C) {
            c.send(new CharacterCreationC2S(creationJson()));
        }
        c.await(WelcomeS2C.class, PROMPT).orElseThrow(() -> new AssertionError(username + " was never welcomed"));
        return c;
    }

    /** What the character-creation screen sends: all 27 AP spent, one CP spent, a class. */
    public static byte[] creationJson() {
        CharacterStats c = new CharacterStats(null);
        c.setSelectedBackground("adventurer");
        for (int i = 0; i < 27; i++) {
            c.incrementAbilityScore(i % 6);
        }
        c.spendCpOnAbility("berserker:p0:0");
        return c.toCreationJson();
    }

    public void handshake(String username, String secret) {
        send(new HandshakeC2S(ProtocolVersion.CURRENT, username, secret, WireCompression.localDecodeMask()));
    }

    /**
     * Reports standing at the spawn the welcome named — the first position report, which starts
     * chunk streaming and unlocks everything measured from the player's position.
     */
    public void reportAtSpawn() throws InterruptedException {
        WelcomeS2C w = await(WelcomeS2C.class, PROMPT).orElseThrow(() -> new AssertionError("not welcomed"));
        send(new com.stonebreak.network.packet.player.PlayerStateC2S(w.spawnX(), w.spawnY(), w.spawnZ(),
                0f, 0f, (short) com.stonebreak.network.packet.player.PlayerStateFlags.ON_GROUND));
    }

    public boolean send(Packet packet) {
        return net.connection().send(packet, false);
    }

    /** The underlying Netty client, for tests that need the raw channel. */
    public NetworkClient net() { return net; }

    private void drainLoop() {
        ClientInboundQueue queue = net.inboundQueue();
        while (!closing) {
            queue.drain(e -> {
                if (e.kind() == ClientInboundQueue.Kind.PACKET) {
                    synchronized (received) {
                        received.add(e.packet());
                    }
                } else if (e.kind() == ClientInboundQueue.Kind.DISCONNECT) {
                    disconnected = true;
                }
            });
            try {
                Thread.sleep(2);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    /** Everything received so far, in order. */
    public List<Packet> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    public <T extends Packet> List<T> received(Class<T> type) {
        return received().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Waits for the first received packet matching {@code match}. */
    public Optional<Packet> awaitAny(Predicate<Packet> match, Duration timeout) throws InterruptedException {
        Packet[] found = new Packet[1];
        ServerHarness.await(() -> {
            for (Packet p : received()) {
                if (match.test(p)) {
                    found[0] = p;
                    return true;
                }
            }
            return false;
        }, timeout);
        return Optional.ofNullable(found[0]);
    }

    public <T extends Packet> Optional<T> await(Class<T> type, Duration timeout) throws InterruptedException {
        return awaitAny(type::isInstance, timeout).map(type::cast);
    }

    /** Waits for a received packet of {@code type} matching {@code match}. */
    public <T extends Packet> Optional<T> await(Class<T> type, Predicate<T> match, Duration timeout)
            throws InterruptedException {
        return awaitAny(p -> type.isInstance(p) && match.test(type.cast(p)), timeout).map(type::cast);
    }

    /** Waits until the server closes this connection; returns whether it did. */
    public boolean awaitDisconnect(Duration timeout) throws InterruptedException {
        return ServerHarness.await(() -> disconnected || !net.connection().isActive(), timeout);
    }

    public boolean isConnected() {
        return !disconnected && net.connection().isActive();
    }

    @Override
    public void close() {
        net.shutdown();
        closing = true;
    }
}
