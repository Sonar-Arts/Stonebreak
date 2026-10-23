package com.stonebreak.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #368: a server still booting when its session ends must never be published (over the
 * next session's) and must be torn down on every exit path — stopped, interrupted (the world
 * load or the socket bind), or failed.
 */
class ServerRunTest {

    /** Stand-in for an IntegratedServer: counts teardowns, records the interrupt flag then. */
    private static final class FakeServer {
        final AtomicInteger teardowns = new AtomicInteger();
        final AtomicBoolean interruptedDuringTeardown = new AtomicBoolean();

        void teardown() {
            teardowns.incrementAndGet();
            interruptedDuringTeardown.set(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    void aBootStoppedBeforeItFinishesTearsItselfDownAndNeverPublishes() throws Exception {
        ServerRun run = new ServerRun();
        FakeServer server = new FakeServer();
        AtomicReference<FakeServer> published = new AtomicReference<>();

        boolean result = run.startAndPublish(server,
                s -> assertFalse(run.stop(), "nothing published yet: the boot owns its teardown"),
                published::set, FakeServer::teardown);

        assertFalse(result);
        assertNull(published.get(), "a stopped run must not publish its server");
        assertEquals(1, server.teardowns.get());
    }

    @Test
    void aStaleRunCannotPublishOverTheNextSession() throws Exception {
        AtomicReference<FakeServer> current = new AtomicReference<>();
        ServerRun first = new ServerRun();
        CountDownLatch booting = new CountDownLatch(1);
        CountDownLatch nextSessionStarted = new CountDownLatch(1);
        FakeServer stale = new FakeServer();
        AtomicBoolean stalePublished = new AtomicBoolean(true);

        Thread slowBoot = new Thread(() -> {
            try {
                stalePublished.set(first.startAndPublish(stale, s -> {
                    booting.countDown();
                    nextSessionStarted.await(); // the boot outlives its session
                }, current::set, FakeServer::teardown));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        slowBoot.start();
        assertTrue(booting.await(5, TimeUnit.SECONDS));

        // Quit, then Play again while the first boot still loads: the old code shared ONE flag,
        // so the second session set it back to true and the stale boot published itself.
        assertFalse(first.stop());
        ServerRun second = new ServerRun();
        FakeServer fresh = new FakeServer();
        assertTrue(second.startAndPublish(fresh, s -> { }, current::set, FakeServer::teardown));
        nextSessionStarted.countDown();
        slowBoot.join(5000);

        assertFalse(stalePublished.get());
        assertSame(fresh, current.get(), "the stale boot must not overwrite the live server");
        assertEquals(1, stale.teardowns.get(), "the stale server is torn down by its own boot");
        assertEquals(0, fresh.teardowns.get());
        assertTrue(second.isRunning());
    }

    @Test
    @Timeout(10)
    void anInterruptedBootIsTornDown() {
        ServerRun run = new ServerRun();
        FakeServer server = new FakeServer();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch blocked = new CountDownLatch(1);

        Thread boot = new Thread(() -> {
            try {
                run.startAndPublish(server, s -> {
                    blocked.countDown();
                    new CountDownLatch(1).await(); // NetServer bind().sync() / a world-load wait
                }, s -> { }, FakeServer::teardown);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        boot.start();
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            assertFalse(run.stop());
            boot.interrupt();
            boot.join(5000);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }

        assertTrue(thrown.get() instanceof InterruptedException, "the interrupt propagates: " + thrown.get());
        assertEquals(1, server.teardowns.get(), "the InterruptedException path tears the server down too");
    }

    @Test
    void teardownRunsWithTheInterruptFlagClearedAndTheFlagSurvivesIt() {
        ServerRun run = new ServerRun();
        FakeServer server = new FakeServer();
        try {
            assertThrows(IllegalStateException.class, () -> run.startAndPublish(server, s -> {
                Thread.currentThread().interrupt(); // an interrupt that no wait consumed
                throw new IllegalStateException("boot failed");
            }, s -> { }, FakeServer::teardown));
            assertEquals(1, server.teardowns.get());
            assertFalse(server.interruptedDuringTeardown.get(),
                    "teardown runs with the flag cleared, or its save flush would give up at once");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is restored afterwards");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailedBootIsTornDown() {
        ServerRun run = new ServerRun();
        FakeServer server = new FakeServer();
        assertThrows(IllegalStateException.class, () -> run.startAndPublish(server,
                s -> { throw new IllegalStateException("port in use"); }, s -> { }, FakeServer::teardown));
        assertEquals(1, server.teardowns.get());
    }

    @Test
    void aPublishedServerBelongsToTheSession() throws Exception {
        ServerRun run = new ServerRun();
        FakeServer server = new FakeServer();
        AtomicReference<FakeServer> published = new AtomicReference<>();

        assertTrue(run.startAndPublish(server, s -> { }, published::set, FakeServer::teardown));
        assertSame(server, published.get());
        assertEquals(0, server.teardowns.get(), "the boot must not tear down a published server");
        assertTrue(run.stop(), "stop() reports that the session owns the teardown now");
        assertFalse(run.isRunning());
        assertFalse(run.publish(() -> { throw new AssertionError("published after stop"); }));
    }
}
