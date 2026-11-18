package com.stonebreak.network.server.bench;

import com.openmason.engine.net.client.NetworkClient;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.openmason.engine.net.server.NetworkServer;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.net.server.ServerLimits;
import com.openmason.engine.net.transport.NetAddress;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.network.StonebreakProtocol;
import com.stonebreak.network.packet.handshake.HandshakeC2S;
import com.stonebreak.network.packet.world.CubeDataS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.network.server.handlers.ServerEntityHandler;
import com.stonebreak.world.World;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Issue #346: server tick and network cost with several simulated TCP clients and a fixed mob
 * count. Gated on {@code -Dstonebreak.bench=true}:
 * <pre>
 *   mvn test -pl stonebreak-game -am -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dtest=ServerTickBenchTest -Dstonebreak.bench=true \
 *       [-Dbench.clients=8] [-Dbench.mobs=400] [-Dbench.ticks=600] [-Dbench.chunks=2]
 *       [-Dbench.noBatch=true] [-Dbench.noCompression=true]
 * </pre>
 * Real {@link NetworkServer} over loopback TCP, real {@link NetworkClient}s, the real
 * {@link ServerEntityHandler} replicating {@link Cow}s on a seeded random walk, and a chunk
 * stream of {@code bench.chunks} {@link CubeDataS2C} cube records per client per tick (synthetic layered
 * terrain, built once). The tick runs at 20 Hz wall time, so bytes per second are real rates.
 *
 * <p>Tick time is the replication work only (interest pass, entity deltas, chunk sends and
 * the end-of-tick flush), not the mob random walk that stands in for the simulation.
 */
class ServerTickBenchTest {

    private static final long TICK_NS = 50_000_000L;

    @Test
    void replicationTickAndBandwidth() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.bench"), "manual benchmark (-Dstonebreak.bench=true)");
        int clients = Integer.getInteger("bench.clients", 8);
        int mobs = Integer.getInteger("bench.mobs", 400);
        int ticks = Integer.getInteger("bench.ticks", 600);
        int chunksPerTick = Integer.getInteger("bench.chunks", 2);
        int warmup = Math.max(20, ticks / 6);
        // Issue #346 toggles, to measure each change alone: per-tick flush, chunk compression.
        boolean batched = !Boolean.getBoolean("bench.noBatch");
        boolean compressed = !Boolean.getBoolean("bench.noCompression");

        ServerLimits d = ServerLimits.DEFAULTS; // every bench client shares 127.0.0.1
        NetworkServer server = new NetworkServer(StonebreakProtocol.registry(), new ServerLimits(
            Math.max(d.maxConnections(), clients), Math.max(d.maxConnectionsPerAddress(), clients),
            d.admissionTimeoutMillis(), d.readIdleTimeoutSeconds(), d.maxOutboundBytes(),
            d.maxInboundFrameBytes(), d.packetsPerSecond(), d.packetBurst(), d.bytesPerSecond(),
            d.byteBurst(), d.readPauseDepth(), d.readResumeDepth()));
        server.start(null, NetAddress.tcp("127.0.0.1", 0));
        InetSocketAddress bound = (InetSocketAddress) server.tcpBoundAddress();

        List<NetworkClient> netClients = new ArrayList<>();
        AtomicLong[] received = new AtomicLong[clients];
        for (int i = 0; i < clients; i++) {
            NetworkClient c = new NetworkClient(StonebreakProtocol.registry());
            c.connect(NetAddress.tcp("127.0.0.1", bound.getPort()));
            AtomicLong counter = new AtomicLong();
            received[i] = counter;
            c.connection().channel().pipeline().addFirst("benchCount", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof ByteBuf b) {
                        counter.addAndGet(b.readableBytes());
                    }
                    ctx.fireChannelRead(msg);
                }
            });
            c.connection().send(new HandshakeC2S(1, "bench" + i)); // admits the connection
            netClients.add(c);
        }
        long waitUntil = System.nanoTime() + 5_000_000_000L;
        while (server.connections().size() < clients && System.nanoTime() < waitUntil) {
            Thread.sleep(10);
        }
        List<ServerConnection> conns = new ArrayList<>(server.connections().all());
        if (conns.size() != clients) {
            throw new IllegalStateException("only " + conns.size() + " of " + clients + " clients connected");
        }
        for (NetworkClient c : netClients) {
            c.connection().setPhase(ProtocolPhase.PLAY);
        }

        AtomicLong flushes = new AtomicLong();
        ServerWorldContext ctx = new ServerWorldContext(server.connections());
        Random rnd = new Random(346);
        List<ServerPlayer> players = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            ServerConnection conn = conns.get(i);
            conn.setPhase(ProtocolPhase.PLAY);
            if (compressed) {
                conn.setPeerCompression(WireCompression.localDecodeMask()); // as the handshake reports it
            }
            conn.channel().pipeline().addFirst("benchFlushCount", new ChannelOutboundHandlerAdapter() {
                @Override
                public void flush(ChannelHandlerContext c) {
                    flushes.incrementAndGet();
                    c.flush();
                }
            });
            ServerPlayer sp = new ServerPlayer(conn, i + 1);
            conn.setAttachment(sp);
            sp.markHandshakeDone();
            sp.setViewDistanceChunks(8);
            sp.updateState(rnd.nextFloat() * 64f, 70f, rnd.nextFloat() * 64f, 0f, 0f);
            ctx.addPlayer(sp);
            players.add(sp);
        }
        Thread.sleep(200); // phase attributes settle on both ends before PLAY traffic

        // Clients drain their decoded packets so the inbound queues stay bounded.
        Thread drainer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                for (NetworkClient c : netClients) {
                    c.inboundQueue().drain(e -> { });
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bench-client-drain");
        drainer.setDaemon(true);
        drainer.start();

        ServerEntityHandler handler = new ServerEntityHandler();
        World world = mock(World.class);
        List<Cow> cows = new ArrayList<>();
        float[] heading = new float[mobs];
        for (int i = 0; i < mobs; i++) {
            Cow cow = new Cow(world, new org.joml.Vector3d(rnd.nextFloat() * 256f - 64f, 70f, rnd.nextFloat() * 256f - 64f));
            handler.onEntitySpawned(cow, ctx);
            cows.add(cow);
            heading[i] = rnd.nextFloat() * (float) (Math.PI * 2);
        }
        float[] playerHeading = new float[clients];
        for (int i = 0; i < clients; i++) {
            playerHeading[i] = rnd.nextFloat() * (float) (Math.PI * 2);
        }
        CubeDataS2C.CubeRecord[] chunkPool = new CubeDataS2C.CubeRecord[32];
        try (CubeWorld scratch = CubeWorld.create(CubeWorld.UNBOUNDED_MIN, CubeWorld.UNBOUNDED_MAX, 0, null)) {
            com.stonebreak.world.voxel.VoxelLayers.define(scratch);
            for (int i = 0; i < chunkPool.length; i++) {
                scratch.writeIds(i, 2, 0, syntheticCube(new Random(1000 + i)));
                chunkPool[i] = new CubeDataS2C.CubeRecord(i, 2, 0, scratch.encode(i, 2, 0, CubeWorld.PARTS_NETWORK),
                    new int[0], new String[0]);
            }
        }
        MobBehaviorState[] animStates = {MobBehaviorState.IDLE, MobBehaviorState.WANDERING, MobBehaviorState.GRAZING};

        long[] tickNs = new long[ticks];
        long bytesAtStart = 0;
        long flushesAtStart = 0;
        long measureStartNs = 0;
        long next = System.nanoTime();
        int chunkCursor = 0;
        for (int t = -warmup; t < ticks; t++) {
            if (t == 0) {
                bytesAtStart = sum(received);
                flushesAtStart = flushes.get();
                measureStartNs = System.nanoTime();
            }
            // Simulation stand-in (not timed): half the herd walks, a few change animation state.
            for (int i = 0; i < mobs; i++) {
                Cow cow = cows.get(i);
                if (((i + t) & 1) == 0) {
                    heading[i] += (rnd.nextFloat() - 0.5f) * 0.3f;
                    org.joml.Vector3d p = cow.getPosition();
                    cow.setPosition(p.x + (float) Math.cos(heading[i]) * 0.08f, p.y, p.z + (float) Math.sin(heading[i]) * 0.08f);
                    cow.setYaw((float) Math.toDegrees(heading[i]));
                }
                if (rnd.nextInt(100) == 0 && cow.getAI() != null) {
                    cow.getAI().setState(animStates[rnd.nextInt(animStates.length)]);
                }
            }
            for (int i = 0; i < clients; i++) {
                ServerPlayer sp = players.get(i);
                playerHeading[i] += (rnd.nextFloat() - 0.5f) * 0.1f;
                sp.updateState(sp.x() + (float) Math.cos(playerHeading[i]) * 0.2f, 70f,
                    sp.z() + (float) Math.sin(playerHeading[i]) * 0.2f, 0f, 0f);
            }

            long start = System.nanoTime();
            if (batched) {
                server.outboundBatch().begin();
            }
            handler.tick(ctx);
            for (ServerPlayer sp : players) {
                java.util.List<CubeDataS2C.CubeRecord> batch = new ArrayList<>();
                for (int k = 0; k < chunksPerTick; k++) {
                    batch.add(chunkPool[chunkCursor++ % chunkPool.length]);
                }
                if (!batch.isEmpty()) {
                    sp.send(new CubeDataS2C(java.util.List.of(), batch), false);
                }
            }
            if (batched) {
                server.outboundBatch().end(); // IntegratedServer: one flush per connection per tick
            }
            long elapsed = System.nanoTime() - start;
            if (t >= 0) {
                tickNs[t] = elapsed;
            }

            next += TICK_NS;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
            } else {
                next = System.nanoTime();
            }
        }
        Thread.sleep(300); // let the last ticks reach the clients
        double seconds = (System.nanoTime() - measureStartNs) / 1e9;
        long bytes = sum(received) - bytesAtStart;
        long flushCount = flushes.get() - flushesAtStart;

        long[] sorted = tickNs.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2] / 1e6;
        double p99 = sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.99) - 1)] / 1e6;
        double mean = Arrays.stream(tickNs).average().orElse(0) / 1e6;
        System.out.printf("%n[bench #346] clients=%d mobs=%d ticks=%d chunks/client/tick=%d batched=%s compressed=%s (zstd %s)%n",
            clients, mobs, ticks, chunksPerTick, batched, compressed,
            com.openmason.engine.cenda.CendaKernels.isAvailable() ? "native" : "unavailable, deflate");
        System.out.printf("[bench #346] tick ms: median %.3f  p99 %.3f  mean %.3f  max %.3f%n",
            median, p99, mean, sorted[sorted.length - 1] / 1e6);
        System.out.printf("[bench #346] per client: %.1f KiB/s, %.1f flushes/s%n",
            bytes / seconds / clients / 1024.0, flushCount / seconds / clients);
        System.out.printf("[bench #346] cube record (raw PARTS_NETWORK) avg %d bytes%n",
            (int) Arrays.stream(chunkPool).mapToInt(p -> p.bytes().length).average().orElse(0));
        if (compressed) {
            int codec = WireCompression.choose(WireCompression.localDecodeMask(), Integer.MAX_VALUE);
            System.out.printf("[bench #346] cube record (%s) avg %d bytes%n",
                codec == WireCompression.ZSTD ? "zstd" : "deflate",
                (int) Arrays.stream(chunkPool).mapToInt(p -> {
                    byte[] c = WireCompression.compress(codec, p.bytes());
                    return c != null ? c.length : p.bytes().length;
                }).average().orElse(0));
        }

        drainer.interrupt();
        for (NetworkClient c : netClients) {
            c.shutdown();
        }
        server.shutdown();
    }

    private static long sum(AtomicLong[] counters) {
        long s = 0;
        for (AtomicLong c : counters) {
            s += c.get();
        }
        return s;
    }

    /** Layered terrain with ores, caves and a sea: roughly what a surface cube compresses like. */
    private static short[] syntheticCube(Random rnd) {
        short[] ids = new short[32 * 32 * 32];
        double phase = rnd.nextDouble() * 10;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                int h = 14 + (int) Math.round(6 * Math.sin(phase + x * 0.31) + 4 * Math.cos(phase + z * 0.23));
                for (int y = 0; y < 32; y++) {
                    BlockType b;
                    if (y < h - 3) {
                        int r = rnd.nextInt(1000);
                        b = r < 12 ? BlockType.COAL_ORE : r < 18 ? BlockType.IRON_ORE
                            : r < 45 ? BlockType.AIR : r < 60 ? BlockType.GRAVEL : BlockType.STONE;
                    } else if (y < h) {
                        b = BlockType.DIRT;
                    } else if (y == h) {
                        b = h < 16 ? BlockType.SAND : BlockType.GRASS;
                    } else if (y <= 16) {
                        b = BlockType.WATER;
                    } else {
                        b = y == h + 1 && rnd.nextInt(12) == 0 ? BlockType.ROSE : BlockType.AIR;
                    }
                    ids[CubeWorld.cellIndex(x, y, z)] = (short) b.getId();
                }
            }
        }
        return ids;
    }
}
