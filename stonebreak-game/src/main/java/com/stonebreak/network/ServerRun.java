package com.stonebreak.network;

import java.util.function.Consumer;

/**
 * One integrated-server session's run token (issue #368). Each session gets its own: a boot
 * still running after its session ended sees its OWN token stopped — never the next session's
 * — so it can never publish itself over a newer server. Publishing and stopping are atomic
 * against each other, so a server is either published (the session tears it down) or torn
 * down by the boot thread itself, never both and never neither.
 */
final class ServerRun {

    /** Starts a server instance (blocking boot: world load, spawn search, bind). */
    @FunctionalInterface
    interface Starter<S> {
        void start(S server) throws Exception;
    }

    private boolean running = true;
    private boolean published;
    private Thread thread;

    synchronized void setThread(Thread thread) { this.thread = thread; }
    synchronized Thread thread() { return thread; }

    /** Still the session's server: the tick loop keeps going while this is true. */
    synchronized boolean isRunning() { return running; }

    /**
     * Ends this run. Returns true when its server was published — the caller then owns its
     * teardown; false means the boot thread tears its own server down when it notices.
     */
    synchronized boolean stop() {
        running = false;
        return published;
    }

    /** Runs {@code publish} unless this run was stopped first; true when it ran. */
    synchronized boolean publish(Runnable publish) {
        if (!running) {
            return false;
        }
        publish.run();
        published = true;
        return true;
    }

    /**
     * Boots {@code server} and publishes it unless this run was stopped meanwhile. A server
     * that is not published is torn down here on every exit path — stopped, interrupted
     * (a blocking world load or the socket bind), or failed — so only a published one ever
     * outlives this call. The thread's interrupt flag is cleared around the teardown, or the
     * save flush and executor shutdowns in it would give up at once.
     *
     * @return true when published
     */
    <S> boolean startAndPublish(S server, Starter<S> starter, Consumer<S> publish,
                                Consumer<S> teardown) throws Exception {
        boolean done = false;
        try {
            starter.start(server);
            done = publish(() -> publish.accept(server));
            return done;
        } finally {
            if (!done) {
                boolean interrupted = Thread.interrupted();
                try {
                    teardown.accept(server);
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }
}
