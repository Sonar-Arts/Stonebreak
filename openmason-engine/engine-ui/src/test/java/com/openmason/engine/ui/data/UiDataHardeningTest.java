package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review findings of #289 fixed before the screen migrations: cross-thread posts, confinement,
 * bounded drains, actions whose handler closes their own screen, cancellability, contract
 * versions and epoch hooks.
 */
class UiDataHardeningTest {

    private static final HostContract FURNACE = HostContract.of("t:furnace", 1);
    private static final DataType.Obj FURNACE_TYPE = DataType.object("open", DataType.bool());
    private static final DataType.ListOf SLOTS = DataType.list(
        DataType.object("id", DataType.string(), "count", DataType.integer()), "id");

    private static UiValue.Obj open(boolean open) {
        return new UiValue.Obj(Map.of("open", UiValue.of(open)));
    }

    private static UiValue.Obj slot(String id, int count) {
        return new UiValue.Obj(Map.of("id", UiValue.of(id), "count", UiValue.of(count)));
    }

    // ── posts and confinement ───────────────────────────────────────────────

    @Test
    void aUiThreadSetSupersedesAnOlderPendingPost() {
        UiHost host = new UiHost();
        DataCell cell = host.data().register("furnace", new DataCell(FURNACE_TYPE, open(false)), FURNACE);
        cell.post(open(false)); // the old furnace closing, from another thread
        cell.set(open(true));   // a new furnace opening on the UI thread, same frame
        host.drain();
        assertEquals(open(true), cell.value(), "the stale post must not land on top of the newer value");
    }

    @Test
    void anInvalidPostFailsTheCellOnTheUiThreadInsteadOfThrowingIntoTheProducer() {
        UiHost host = new UiHost();
        DataCell cell = host.data().register("furnace", new DataCell(FURNACE_TYPE, open(false)), FURNACE);
        assertDoesNotThrow(() -> cell.post(UiValue.of("not an object")));
        host.drain();
        assertInstanceOf(DataState.Failed.class, cell.state());
        cell.post(open(true));
        host.drain();
        assertEquals(open(true), cell.value(), "the next valid post recovers");
    }

    @Test
    void collectionPostsCoalesceAndAreDiffedByIdentity() throws Exception {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), FURNACE);
        inv.setAll(List.of(slot("a", 1), slot("b", 2)));
        List<List<ListChange>> seen = new ArrayList<>();
        inv.subscribe((state, changes) -> seen.add(changes));
        Thread producer = new Thread(() -> {
            inv.post(List.of(slot("a", 1), slot("b", 3)));
            inv.post(List.of(slot("b", 5), slot("a", 1), slot("c", 1)));
        });
        producer.start();
        producer.join();
        host.drain();
        assertEquals(1, seen.size(), "two posts, one notification");
        assertEquals(List.of(slot("b", 5), slot("a", 1), slot("c", 1)), inv.items());
        assertTrue(seen.getFirst().stream().noneMatch(c -> c instanceof ListChange.Reset), "incremental, not a reset");

        inv.post(List.of(slot("z", 1)));
        inv.setAll(List.of(slot("y", 1)));
        host.drain();
        assertEquals(List.of(slot("y", 1)), inv.items(), "a UI-thread edit supersedes the pending post");
    }

    @Test
    void anInvalidCollectionPostFailsInsteadOfThrowing() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), FURNACE);
        inv.post(List.of(UiValue.of(3)));
        host.drain();
        assertInstanceOf(DataState.Failed.class, inv.state());
    }

    @Test
    void postNeedsARegisteredSource() {
        assertThrows(IllegalStateException.class, () -> new DataCollection(SLOTS).post(List.of()));
    }

    @Test
    void writesOffTheUiThreadFailLoudlyOnceTheUiThreadIsKnown() throws Exception {
        UiHost host = new UiHost();
        DataCell cell = host.data().register("furnace", new DataCell(FURNACE_TYPE, open(false)), FURNACE);
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), FURNACE);
        AtomicReference<Throwable> beforeDrain = new AtomicReference<>();
        Thread early = new Thread(() -> {
            try {
                cell.set(open(true)); // setup before the first frame is not checked
            } catch (Throwable t) {
                beforeDrain.set(t);
            }
        });
        early.start();
        early.join();
        assertNull(beforeDrain.get());

        host.drain(); // this thread is now the UI thread
        AtomicReference<Throwable> cellError = new AtomicReference<>();
        AtomicReference<Throwable> listError = new AtomicReference<>();
        AtomicBoolean posted = new AtomicBoolean();
        Thread server = new Thread(() -> {
            try {
                cell.set(open(false));
            } catch (Throwable t) {
                cellError.set(t);
            }
            try {
                inv.setAll(List.of(slot("a", 1)));
            } catch (Throwable t) {
                listError.set(t);
            }
            cell.post(open(false));
            posted.set(true);
        });
        server.start();
        server.join();
        assertInstanceOf(IllegalStateException.class, cellError.get());
        assertTrue(cellError.get().getMessage().contains("post()"), cellError.get().getMessage());
        assertInstanceOf(IllegalStateException.class, listError.get());
        assertTrue(posted.get());
        host.drain();
        assertEquals(open(false), cell.value());
    }

    @Test
    void aTaskThatKeepsRepostingItselfCannotLivelockTheFrame() {
        UiThreadQueue q = new UiThreadQueue();
        AtomicInteger runs = new AtomicInteger();
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            runs.incrementAndGet();
            q.post(task[0]);
        };
        q.post(task[0]);
        assertEquals(UiThreadQueue.MAX_ROUNDS, q.drain());
        assertFalse(q.isEmpty(), "the rest waits for the next frame");
        q.drain();
        assertEquals(2 * UiThreadQueue.MAX_ROUNDS, runs.get());
    }

    @Test
    void followUpWorkPostedDuringADrainStillRunsInThatDrain() {
        UiThreadQueue q = new UiThreadQueue();
        List<String> order = new ArrayList<>();
        q.post(() -> {
            order.add("first");
            q.post(() -> order.add("follow-up"));
        });
        q.drain();
        assertEquals(List.of("first", "follow-up"), order);
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private static final HostContract SCREEN = HostContract.of("t:screen", 2);

    @Test
    void anActionWhoseHandlerClosesItsOwnScreenSucceeds() {
        UiHost host = new UiHost();
        AtomicReference<UiScope> self = new AtomicReference<>();
        host.actions().register(ActionSpec.of("t:screen.resume", SCREEN, null, DataType.ANY), (args, ctx) -> {
            self.get().close(); // pause "resume" closes the pause screen synchronously
            return CompletableFuture.completedFuture(UiValue.of("resumed"));
        });
        List<UiProblem> problems = new ArrayList<>();
        UiScope s = host.openScope("t:ui/pause", Set.of("t:screen"), problems::add);
        self.set(s);
        List<ActionCall.State> settled = new ArrayList<>();
        ActionCall call = s.invoke("t:screen.resume", null, s.site("resume", CallSite.Origin.SCRIPT));
        call.whenSettled(c -> settled.add(c.state()));
        assertEquals(ActionCall.State.SUCCEEDED, call.state());
        assertEquals(UiValue.of("resumed"), call.result());
        assertEquals(List.of(ActionCall.State.SUCCEEDED), settled);
        assertTrue(s.isClosed());
        assertTrue(problems.stream().noneMatch(p -> p.kind() == UiProblem.Kind.STALE_COMPLETION), problems.toString());
    }

    @Test
    void anActionWhoseHandlerLeavesTheWorldSucceedsButOtherPendingCallsAreCancelled() {
        UiHost host = new UiHost();
        CompletableFuture<UiValue> slow = new CompletableFuture<>();
        host.actions().register(ActionSpec.of("t:screen.load", SCREEN, null, DataType.ANY), (a, c) -> slow);
        host.actions().register(ActionSpec.of("t:screen.quit", SCREEN, null, DataType.ANY), (args, ctx) -> {
            host.advanceEpoch("quit to menu");
            return null;
        });
        UiScope s = host.openScope("t:ui/pause", Set.of("t:screen"), p -> { });
        ActionCall load = s.invoke("t:screen.load", null, s.site("load", CallSite.Origin.SCRIPT));
        ActionCall quit = s.invoke("t:screen.quit", null, s.site("quit", CallSite.Origin.SCRIPT));
        assertEquals(ActionCall.State.SUCCEEDED, quit.state());
        assertEquals(ActionCall.State.CANCELLED, load.state());
    }

    @Test
    void anAsyncHandlerThatClosedItsScreenIsCancelledAndItsLateResultDropped() {
        UiHost host = new UiHost();
        CompletableFuture<UiValue> later = new CompletableFuture<>();
        AtomicReference<UiScope> self = new AtomicReference<>();
        host.actions().register(ActionSpec.of("t:screen.save", SCREEN, null, DataType.ANY), (args, ctx) -> {
            self.get().close();
            return later;
        });
        List<UiProblem> problems = new ArrayList<>();
        UiScope s = host.openScope("t:ui/pause", Set.of("t:screen"), problems::add);
        self.set(s);
        ActionCall call = s.invoke("t:screen.save", null, s.site("save", CallSite.Origin.SCRIPT));
        assertEquals(ActionCall.State.CANCELLED, call.state());
        later.complete(UiValue.of(1));
        host.drain();
        assertEquals(ActionCall.State.CANCELLED, call.state());
        assertTrue(problems.stream().anyMatch(p -> p.kind() == UiProblem.Kind.STALE_COMPLETION));
    }

    @Test
    void onlyCancellableHandlersAreToldAboutCancellation() {
        UiHost host = new UiHost();
        AtomicInteger told = new AtomicInteger();
        AtomicReference<ActionContext> plainCtx = new AtomicReference<>();
        host.actions().register(ActionSpec.of("t:screen.plain", SCREEN, null, DataType.ANY), (args, ctx) -> {
            plainCtx.set(ctx);
            ctx.onCancel(told::incrementAndGet);
            return new CompletableFuture<>();
        });
        host.actions().register(ActionSpec.of("t:screen.polite", SCREEN, null, DataType.ANY).withCancellable(true),
            (args, ctx) -> {
                ctx.onCancel(() -> told.addAndGet(10));
                return new CompletableFuture<>();
            });
        UiScope s = host.openScope("t:ui/x", Set.of("t:screen"), p -> { });
        ActionCall plain = s.invoke("t:screen.plain", null, s.site("a", CallSite.Origin.SCRIPT));
        ActionCall polite = s.invoke("t:screen.polite", null, s.site("b", CallSite.Origin.SCRIPT));
        s.close();
        assertEquals(ActionCall.State.CANCELLED, plain.state());
        assertEquals(ActionCall.State.CANCELLED, polite.state());
        assertEquals(10, told.get(), "only the cancellable handler's hook ran");
        assertFalse(plainCtx.get().isCancelled(), "a non-cancellable handler keeps running; its result is dropped");
    }

    @Test
    void anActionNewerThanTheDeclaredContractVersionIsACapabilityError() {
        UiHost host = new UiHost();
        host.actions().register(new ActionSpec("t:screen.toast", SCREEN, 2, null, null, null, false),
            (args, ctx) -> null);
        host.actions().register(ActionSpec.of("t:screen.close", SCREEN, null, null), (args, ctx) -> null);
        UiScope v1 = host.openScopeAt("t:ui/x", Map.of("t:screen", 1), p -> { });
        UiActionException e = assertThrows(UiActionException.class,
            () -> v1.invoke("t:screen.toast", null, v1.site("toast", CallSite.Origin.GRAPH)));
        assertEquals(UiActionException.Code.CAPABILITY_MISSING, e.code());
        assertTrue(e.getMessage().contains("version 2"), e.getMessage());
        assertEquals(ActionCall.State.SUCCEEDED,
            v1.invoke("t:screen.close", null, v1.site("close", CallSite.Origin.GRAPH)).state());
        UiScope v2 = host.openScopeAt("t:ui/x", Map.of("t:screen", 2), p -> { });
        assertEquals(ActionCall.State.SUCCEEDED,
            v2.invoke("t:screen.toast", null, v2.site("toast", CallSite.Origin.GRAPH)).state());
    }

    @Test
    void epochListenersResetPerWorldDataAfterPendingCallsAreCancelled() {
        UiHost host = new UiHost();
        DataCell cell = host.data().register("furnace", new DataCell(FURNACE_TYPE, open(true)), FURNACE);
        List<String> reasons = new ArrayList<>();
        Subscription sub = host.onEpoch(reason -> {
            reasons.add(reason);
            cell.set(open(false));
        });
        host.advanceEpoch("left world");
        assertEquals(List.of("left world"), reasons);
        assertEquals(open(false), cell.value());
        sub.close();
        host.advanceEpoch("again");
        assertEquals(1, reasons.size());
    }
}
