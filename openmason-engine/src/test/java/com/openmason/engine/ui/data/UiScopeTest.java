package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Action contracts, pending/success/error states, reentrancy, cancellation and lifetime of a scope (#289). */
class UiScopeTest {

    private static final HostContract NET = HostContract.of("t:network", 1);
    private static final String DOC = "t:ui/pause";

    private final UiHost host = new UiHost();
    private final List<UiProblem> problems = new ArrayList<>();
    private final List<CompletableFuture<UiValue>> held = new ArrayList<>();
    private final AtomicInteger runs = new AtomicInteger();

    UiScopeTest() {
        host.actions().register(ActionSpec.of("t:network.resync", NET, DataType.object("full", DataType.bool()),
            DataType.object("chunks", DataType.integer())), (args, ctx) -> {
                runs.incrementAndGet();
                CompletableFuture<UiValue> f = new CompletableFuture<>();
                held.add(f);
                return f;
            });
        host.actions().register(ActionSpec.of("t:network.ping", NET, null, DataType.number())
                .withReentrancy(ActionSpec.Reentrancy.CANCEL_PREVIOUS).withCancellable(true),
            (args, ctx) -> {
                CompletableFuture<UiValue> f = new CompletableFuture<>();
                ctx.onCancel(() -> f.cancel(false));
                held.add(f);
                return f;
            });
        host.actions().register(ActionSpec.of("t:network.now", NET, null, DataType.number()),
            (args, ctx) -> CompletableFuture.completedFuture(UiValue.of(7)));
    }

    private UiScope open() {
        return host.openScope(DOC, Set.of("t:network"), problems::add);
    }

    private static UiValue.Obj args(Object... kv) {
        java.util.Map<String, UiValue> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1] instanceof Boolean b ? UiValue.of(b) : UiValue.of(String.valueOf(kv[i + 1])));
        }
        return new UiValue.Obj(m);
    }

    @Test
    void parameterMismatchNamesDocumentAndNode() {
        UiScope s = open();
        CallSite site = s.site("panel/resync", CallSite.Origin.SCRIPT);
        UiActionException e = assertThrows(UiActionException.class,
            () -> s.invoke("t:network.resync", args("full", "yes"), site));
        assertEquals(UiActionException.Code.PARAM_MISMATCH, e.code());
        assertTrue(e.getMessage().contains("document t:ui/pause, node panel/resync (script)"), e.getMessage());
        assertTrue(e.getMessage().contains("full: expects bool, got string"), e.getMessage());
        assertEquals(UiActionException.Code.UNKNOWN_ACTION,
            assertThrows(UiActionException.class, () -> s.invoke("t:network.nope", null, site)).code());
        assertEquals(0, runs.get(), "a mismatched call never reaches the handler");
    }

    @Test
    void undeclaredCapabilityIsRefused() {
        UiScope s = host.openScope(DOC, Set.of("t:other"), problems::add);
        UiActionException e = assertThrows(UiActionException.class,
            () -> s.invoke("t:network.now", null, s.site("b", CallSite.Origin.GRAPH)));
        assertEquals(UiActionException.Code.CAPABILITY_MISSING, e.code());
        assertTrue(e.getMessage().contains("(graph)"));
    }

    @Test
    void asyncResultsArriveOnTheUiThreadAndAreTypeChecked() throws Exception {
        UiScope s = open();
        ActionCall call = s.invoke("t:network.resync", args("full", true), s.site("resync", CallSite.Origin.BINDING));
        assertTrue(call.isPending());
        assertTrue(s.isPending("t:network.resync"));
        Thread worker = new Thread(() -> held.getFirst().complete(new UiValue.Obj(Map.of("chunks", UiValue.of(12)))));
        worker.start();
        worker.join();
        assertTrue(call.isPending(), "completion waits for the UI thread");
        host.drain();
        assertEquals(ActionCall.State.SUCCEEDED, call.state());
        assertEquals(new UiValue.Obj(Map.of("chunks", UiValue.of(12))), call.result());
        assertFalse(s.isPending("t:network.resync"));

        ActionCall bad = s.invoke("t:network.resync", args("full", false), s.site("resync", CallSite.Origin.SCRIPT));
        held.get(1).complete(UiValue.of("twelve"));
        host.drain();
        assertEquals(ActionCall.State.FAILED, bad.state());
        assertEquals(UiActionException.Code.RESULT_MISMATCH, ((UiActionException) bad.error()).code());
        assertTrue(bad.error().getMessage().contains("node resync (script)"));
        assertTrue(problems.stream().anyMatch(p -> p.kind() == UiProblem.Kind.RESULT_MISMATCH));
    }

    @Test
    void duplicateClicksFollowTheReentrancyPolicy() {
        UiScope s = open();
        CallSite site = s.site("resync", CallSite.Origin.BINDING);
        ActionCall first = s.invoke("t:network.resync", args("full", true), site);
        ActionCall dup = s.invoke("t:network.resync", args("full", true), site);
        assertEquals(ActionCall.State.REJECTED, dup.state());
        assertEquals(1, runs.get(), "the duplicate never ran");
        assertTrue(first.isPending());

        ActionCall ping1 = s.invoke("t:network.ping", null, site);
        ActionCall ping2 = s.invoke("t:network.ping", null, site);
        assertEquals(ActionCall.State.CANCELLED, ping1.state());
        assertTrue(held.get(1).isCancelled(), "a cancellable handler is told");
        assertTrue(ping2.isPending());
    }

    @Test
    void completionAfterCloseIsStaleAndCallbacksNeverSeeIt() {
        UiScope s = open();
        List<ActionCall.State> seen = new ArrayList<>();
        ActionCall call = s.invoke("t:network.resync", args("full", true), s.site("resync", CallSite.Origin.SCRIPT))
            .whenSettled(c -> seen.add(c.state()));
        s.close();
        assertEquals(List.of(ActionCall.State.CANCELLED), seen);
        held.getFirst().complete(new UiValue.Obj(Map.of("chunks", UiValue.of(1))));
        host.drain();
        assertEquals(ActionCall.State.CANCELLED, call.state());
        assertNull(call.result());
        assertTrue(problems.stream().anyMatch(p -> p.kind() == UiProblem.Kind.STALE_COMPLETION
            && p.message().contains("screen closed")));
        assertEquals(ActionCall.State.REJECTED,
            s.invoke("t:network.now", null, s.site("x", CallSite.Origin.SCRIPT)).state());
    }

    @Test
    void reloadAndWorldChangeRejectOldCompletions() {
        UiScope s = open();
        ActionCall before = s.invoke("t:network.resync", args("full", true), s.site("a", CallSite.Origin.SCRIPT));
        long gen = s.renew("reload");
        assertEquals(2, gen);
        assertEquals(ActionCall.State.CANCELLED, before.state());
        held.getFirst().complete(new UiValue.Obj(Map.of("chunks", UiValue.of(1))));
        host.drain();
        assertTrue(problems.stream().anyMatch(p -> p.message().contains("document reloaded")));

        ActionCall inWorld = s.invoke("t:network.resync", args("full", true), s.site("a", CallSite.Origin.SCRIPT));
        host.advanceEpoch("left world");
        assertEquals(ActionCall.State.CANCELLED, inWorld.state());
        held.get(1).complete(new UiValue.Obj(Map.of("chunks", UiValue.of(1))));
        host.drain();
        assertEquals(ActionCall.State.CANCELLED, inWorld.state());
        assertEquals(ActionCall.State.SUCCEEDED, s.invoke("t:network.now", null, s.site("a", CallSite.Origin.SCRIPT)).state(),
            "the scope keeps working in the new world");
    }

    @Test
    void failingCallbacksAndHooksNeverBreakCloseOrOtherCallbacks() {
        UiScope s = open();
        host.actions().register(ActionSpec.of("t:network.hang", NET, null, DataType.ANY).withCancellable(true),
            (args, ctx) -> {
                ctx.onCancel(() -> {
                    throw new IllegalStateException("hook boom");
                });
                return new CompletableFuture<>();
            });
        List<String> ran = new ArrayList<>();
        s.invoke("t:network.hang", null, s.site("x", CallSite.Origin.SCRIPT))
            .whenSettled(c -> {
                throw new IllegalStateException("callback boom");
            })
            .whenSettled(c -> ran.add("second"));
        s.close();
        assertTrue(s.isClosed());
        assertEquals(List.of("second"), ran);
        assertTrue(problems.stream().anyMatch(p -> p.kind() == UiProblem.Kind.CALLBACK_FAILED));
        assertTrue(problems.stream().anyMatch(p -> p.kind() == UiProblem.Kind.CANCEL_HOOK_FAILED));
        assertTrue(host.openScopes().isEmpty());
    }

    @Test
    void handlerExceptionsFailTheCall() {
        host.actions().register(ActionSpec.of("t:network.broken", NET, null, DataType.ANY), (a, c) -> {
            throw new IllegalStateException("server said no");
        });
        UiScope s = open();
        ActionCall c = s.invoke("t:network.broken", null, s.site("x", CallSite.Origin.HOST));
        assertEquals(ActionCall.State.FAILED, c.state());
        assertEquals("server said no", c.error().getMessage());
    }

    @Test
    void watchesFollowTheSourceAndCloseReleasesThem() {
        DataCell session = host.data().register("session",
            new DataCell(DataType.object("online", DataType.bool()), new UiValue.Obj(Map.of("online", UiValue.FALSE))),
            HostContract.of("t:session", 1));
        UiScope s = open();
        List<DataState> seen = new ArrayList<>();
        s.watch(DataPath.parse("session.online"), seen::add);
        Subscription other = s.watch(DataPath.parse("session"), x -> { });
        assertEquals(1, session.subscriberCount(), "one source subscription per root, fanned out");
        assertEquals(2, s.subscriptionCount());
        session.set(new UiValue.Obj(Map.of("online", UiValue.TRUE)));
        assertEquals(List.of(DataState.ready(UiValue.TRUE)), seen);
        assertEquals(DataState.MISSING, s.read(DataPath.parse("nothing.here")));
        other.close();
        s.close();
        assertEquals(0, s.subscriptionCount());
        assertEquals(0, session.subscriberCount());
        session.set(new UiValue.Obj(Map.of("online", UiValue.FALSE)));
        assertEquals(1, seen.size(), "nothing is delivered after close");
    }
}
