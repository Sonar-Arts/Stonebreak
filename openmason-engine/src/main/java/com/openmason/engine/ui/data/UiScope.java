package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Everything one open document holds on the host (#289): its data subscriptions, its pending
 * action calls and its edit draft. Bindings, Lua code-behind and compiled graphs of that
 * document all go through the same scope.
 *
 * <p><b>Lifetime.</b> {@link #close()} (screen close) releases every subscription, cancels every
 * pending call and drops the draft; {@link #renew} (document reload) cancels pending calls and
 * bumps the {@link #generation()}; a host {@link UiHost#advanceEpoch epoch} change (world leave,
 * disconnect) cancels pending calls too. A completion that arrives for any of those is rejected
 * as stale, so an old screen or world never receives a late result. Closing never throws, even
 * when cancel hooks or callbacks do.
 *
 * <p><b>Closing from inside a handler.</b> An action whose handler closes its own screen, reloads
 * it or leaves the world synchronously (pause "quit", "resume") is not cancelled by that: the
 * call it is running completes with the handler's synchronous result ({@code SUCCEEDED}), so
 * a script awaiting it sees success. A handler that returned an unfinished stage is cancelled
 * once it returns, and its late completion is dropped as stale.
 *
 * <p>UI-thread confined.
 */
public final class UiScope implements AutoCloseable {

    private final UiHost host;
    private final String documentId;
    /** Contract id → version the document declares; {@code null} = unchecked. */
    private final Map<String, Integer> declaredContracts;
    private final Consumer<UiProblem> problems;
    private final Map<String, Channel> channels = new HashMap<>();
    private final List<ActionCall> pending = new ArrayList<>();
    /** Calls whose handler is executing right now (synchronously, on this thread). */
    private final List<ActionCall> running = new ArrayList<>();
    private final EditSession edits;
    private long generation = 1;
    private boolean closed;

    UiScope(UiHost host, String documentId, Map<String, Integer> declaredContracts, Consumer<UiProblem> problems) {
        this.host = host;
        this.documentId = documentId == null ? "" : documentId;
        this.declaredContracts = declaredContracts == null ? null : Map.copyOf(declaredContracts);
        this.problems = problems == null ? p -> { } : problems;
        this.edits = new EditSession(this);
    }

    public UiHost host() {
        return host;
    }

    public String documentId() {
        return documentId;
    }

    /** Bumped by {@link #renew}; calls remember the generation they were made in. */
    public long generation() {
        return generation;
    }

    public boolean isClosed() {
        return closed;
    }

    /** A call site in this scope's document. */
    public CallSite site(String elementKey, CallSite.Origin origin) {
        return new CallSite(documentId, elementKey, origin);
    }

    // ── data ────────────────────────────────────────────────────────────────
    //
    // A document only observes the roots whose contract it declares in hostApis (#327): a root
    // of any other contract reads as FAILED, cannot be watched and cannot be edited, whoever
    // asks (bindings, scripts, graphs). Callers that can fail loudly check first with
    // requireDeclared; the binder reports an element diagnostic.

    /**
     * True when this scope may observe data root {@code rootName}: the root exists and its
     * contract is one the document declares (or the scope is unchecked).
     */
    public boolean declares(String rootName) {
        DataRoot root = host.data().root(rootName);
        return root != null && declares(root);
    }

    private boolean declares(DataRoot root) {
        return declaredContracts == null || declaredContracts.containsKey(root.contract().id());
    }

    /**
     * Throws {@code CAPABILITY_MISSING} when {@code path}'s root exists but belongs to a contract
     * the document does not declare. An unknown root passes: it reads as {@link DataState#MISSING}.
     */
    public void requireDeclared(DataPath path, CallSite site) {
        String problem = undeclared(path);
        if (problem != null) {
            throw new UiActionException(UiActionException.Code.CAPABILITY_MISSING, site, path.toString(), problem);
        }
    }

    /** Why {@code path} may not be observed here, or {@code null} when it may (or names no root). */
    public String undeclared(DataPath path) {
        DataRoot root = host.data().root(path.rootName());
        if (root == null || declares(root)) {
            return null;
        }
        return "data root '" + root.name() + "' belongs to host contract " + root.contract().id()
            + ", which the document does not declare in hostApis";
    }

    /**
     * Current state at an absolute path, including this scope's drafted edits. A root the
     * document does not declare reads as {@link DataState.Failed}.
     */
    public DataState read(DataPath path) {
        DataRoot root = host.data().root(path.rootName());
        if (root == null) {
            return DataState.MISSING;
        }
        if (!declares(root)) {
            return DataState.failed("CAPABILITY_MISSING: " + undeclared(path));
        }
        UiValue draft = edits.draft(root.name());
        DataState base = draft != null ? DataState.ready(draft) : root.source().state();
        return base.at(path.tail());
    }

    /** Schema at an absolute path, or {@code null} when no root or member declares it (or the root is undeclared). */
    public DataType typeOf(DataPath path) {
        DataRoot root = host.data().root(path.rootName());
        return root == null || !declares(root) ? null : path.typeFrom(root.source().type());
    }

    /**
     * Calls {@code listener} whenever the state at {@code path} changes (drafts included). The
     * current state is not delivered; read it with {@link #read}. A root the document does not
     * declare is never watched ({@link Subscription#NONE}).
     */
    public Subscription watch(DataPath path, Consumer<DataState> listener) {
        Objects.requireNonNull(listener, "listener");
        return addWatch(new Watch(path, listener, null));
    }

    /**
     * Incremental watch of a collection root: {@code listener} receives each change's
     * {@link ListChange}s. Any other path (a list nested in an object) is delivered as a
     * {@link ListChange.Reset} on every change.
     */
    public Subscription watchList(DataPath path, DataListener listener) {
        Objects.requireNonNull(listener, "listener");
        return addWatch(new Watch(path, null, listener));
    }

    /** Live watches, for leak tests: 0 after {@link #close()}. */
    public int subscriptionCount() {
        int n = 0;
        for (Channel c : channels.values()) {
            n += c.watches.size();
        }
        return n;
    }

    public EditSession edits() {
        return edits;
    }

    private Subscription addWatch(Watch w) {
        if (closed) {
            return Subscription.NONE;
        }
        DataRoot root = host.data().root(w.path.rootName());
        if (root == null || !declares(root)) {
            return Subscription.NONE; // nothing will ever change; activation reports the unknown/undeclared root
        }
        Channel ch = channels.computeIfAbsent(root.name(), n -> new Channel(root));
        w.last = read(w.path);
        ch.watches.add(w);
        return () -> {
            if (ch.watches.remove(w) && ch.watches.isEmpty()) {
                ch.close();
                channels.remove(root.name(), ch);
            }
        };
    }

    /** Re-delivers every watch of {@code root} whose value changed (draft staged, applied or dropped). */
    void refresh(String root) {
        Channel ch = channels.get(root);
        if (ch != null) {
            ch.fire(null, List.of());
        }
    }

    private final class Channel {
        final DataRoot root;
        final List<Watch> watches = new ArrayList<>();
        final Subscription sub;

        Channel(DataRoot root) {
            this.root = root;
            this.sub = root.source().subscribe((state, changes) -> fire(state, changes));
        }

        void fire(DataState sourceState, List<ListChange> changes) {
            for (Watch w : watches.toArray(Watch[]::new)) {
                if (!watches.contains(w) || closed) {
                    continue;
                }
                DataState now = read(w.path);
                if (w.list != null) {
                    boolean rootPath = w.path.tail().isSelf() && edits.draft(root.name()) == null;
                    List<ListChange> delivered = rootPath && sourceState != null ? changes : List.of(ListChange.RESET);
                    if (!now.equals(w.last) || !delivered.isEmpty() && sourceState != null) {
                        w.last = now;
                        w.list.changed(now, delivered);
                    }
                } else if (!now.equals(w.last)) {
                    w.last = now;
                    w.value.accept(now);
                }
            }
        }

        void close() {
            sub.close();
        }
    }

    private static final class Watch {
        final DataPath path;
        final Consumer<DataState> value;
        final DataListener list;
        DataState last;

        Watch(DataPath path, Consumer<DataState> value, DataListener list) {
            if (path.relative()) {
                throw new IllegalArgumentException("watch needs an absolute path, got " + path);
            }
            this.path = path;
            this.value = value;
            this.list = list;
        }
    }

    // ── actions ─────────────────────────────────────────────────────────────

    /**
     * Invokes host action {@code actionId}. Problems with the call itself throw
     * {@link UiActionException} naming {@code site}; run-time outcomes are states of the
     * returned call. A closed scope returns a {@code REJECTED} call.
     */
    public ActionCall invoke(String actionId, UiValue.Obj args, CallSite site) {
        Objects.requireNonNull(site, "site");
        ActionRegistry.Entry entry = host.actions().entry(actionId);
        if (entry == null) {
            throw new UiActionException(UiActionException.Code.UNKNOWN_ACTION, site, actionId,
                "the host has no such action");
        }
        ActionSpec spec = entry.spec();
        if (declaredContracts != null) {
            Integer declared = declaredContracts.get(spec.contract().id());
            if (declared == null) {
                throw new UiActionException(UiActionException.Code.CAPABILITY_MISSING, site, actionId,
                    "belongs to host contract " + spec.contract().id() + ", which the document does not declare in hostApis");
            }
            if (spec.since() > declared) {
                throw new UiActionException(UiActionException.Code.CAPABILITY_MISSING, site, actionId,
                    "was added in version " + spec.since() + " of host contract " + spec.contract().id()
                        + "; the document declares version " + declared + " in hostApis");
            }
        }
        UiValue.Obj a = args == null ? UiValue.Obj.EMPTY : args;
        String problem = spec.params().problem(a);
        if (problem != null) {
            throw new UiActionException(UiActionException.Code.PARAM_MISMATCH, site, actionId,
                "parameters " + spec.params().describe() + ": " + problem);
        }
        if (closed) {
            return ActionCall.rejected(spec, site, a, this, "scope closed");
        }
        for (ActionCall p : List.copyOf(pending)) {
            if (p.spec().id().equals(actionId) && spec.reentrancy() != ActionSpec.Reentrancy.PARALLEL) {
                if (spec.reentrancy() == ActionSpec.Reentrancy.REJECT_WHILE_PENDING) {
                    return ActionCall.rejected(spec, site, a, this, "already pending");
                }
                p.cancel("superseded");
            }
        }
        ActionCall call = new ActionCall(spec, site, a, this, generation, host.epoch());
        pending.add(call);
        running.add(call);
        CompletionStage<UiValue> stage;
        try {
            stage = entry.handler().handle(a, call.context());
        } catch (RuntimeException e) {
            running.remove(call);
            fail(call, e);
            return call;
        }
        running.remove(call);
        if (stage == null) {
            stage = CompletableFuture.completedFuture(UiValue.NULL);
        }
        if (stage instanceof CompletableFuture<UiValue> f && f.isDone()) {
            // Synchronous result: delivered even when the handler itself closed the screen,
            // reloaded it or left the world (the call was exempt from that cancellation).
            settleResult(call, now(f), cause(f));
        } else {
            if (call.isPending() && isStale(call)) {
                call.cancel(staleReason(call)); // the handler closed its screen and went async
            }
            // A late completion of a cancelled call is reported as stale and dropped.
            stage.whenComplete((v, t) -> host.queue().post(() -> complete(call, v, t)));
        }
        return call;
    }

    /** True while a call of {@code actionId} is pending in this scope (drives busy/disabled UI). */
    public boolean isPending(String actionId) {
        for (ActionCall c : pending) {
            if (c.spec().id().equals(actionId)) {
                return true;
            }
        }
        return false;
    }

    public List<ActionCall> pendingCalls() {
        return List.copyOf(pending);
    }

    private boolean isStale(ActionCall call) {
        return closed || call.generation() != generation || call.epoch() != host.epoch();
    }

    private void complete(ActionCall call, UiValue value, Throwable error) {
        if (!call.isPending() || isStale(call)) {
            call.cancel("stale");
            problem(new UiProblem(UiProblem.Kind.STALE_COMPLETION, call.site(),
                call.spec().id() + " completed after its " + staleReason(call) + "; result dropped"));
            return;
        }
        settleResult(call, value, error);
    }

    private void settleResult(ActionCall call, UiValue value, Throwable error) {
        if (!call.isPending()) {
            return;
        }
        if (error != null) {
            fail(call, unwrap(error));
            return;
        }
        UiValue v = value == null ? UiValue.NULL : value;
        String problem = call.spec().result().problem(v);
        if (problem != null) {
            UiActionException e = new UiActionException(UiActionException.Code.RESULT_MISMATCH, call.site(),
                call.spec().id(), "result " + call.spec().result().describe() + ": " + problem);
            problem(new UiProblem(UiProblem.Kind.RESULT_MISMATCH, call.site(), e.getMessage()));
            call.settle(ActionCall.State.FAILED, null, e);
            return;
        }
        call.settle(ActionCall.State.SUCCEEDED, v, null);
    }

    private String staleReason(ActionCall call) {
        if (closed) {
            return "screen closed";
        }
        if (call.generation() != generation) {
            return "document reloaded";
        }
        if (call.epoch() != host.epoch()) {
            return "world or session changed";
        }
        return "call was cancelled";
    }

    private void fail(ActionCall call, Throwable e) {
        problem(new UiProblem(UiProblem.Kind.ACTION_FAILED, call.site(), call.spec().id() + ": " + e));
        call.settle(ActionCall.State.FAILED, null, e);
    }

    private static UiValue now(CompletableFuture<UiValue> f) {
        try {
            return f.join();
        } catch (CompletionException | CancellationException e) {
            return null;
        }
    }

    private static Throwable cause(CompletableFuture<UiValue> f) {
        if (!f.isCompletedExceptionally()) {
            return null;
        }
        try {
            f.join();
            return null;
        } catch (CompletionException e) {
            return e.getCause() == null ? e : e.getCause();
        } catch (CancellationException e) {
            return e;
        }
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    void settled(ActionCall call) {
        pending.remove(call);
    }

    void problem(UiProblem p) {
        try {
            problems.accept(p);
        } catch (RuntimeException ignored) {
            // A failing reporter must not break settlement or close.
        }
    }

    // ── lifetime ────────────────────────────────────────────────────────────

    /** Cancels pending calls, except those whose handler is running right now (it caused this). */
    void cancelPending(String reason) {
        for (ActionCall c : List.copyOf(pending)) {
            if (!running.contains(c)) {
                c.cancel(reason);
            }
        }
    }

    /**
     * Document reload: pending calls of the old revision are cancelled and the generation moves
     * on, so their completions are rejected. Subscriptions are the binder's to rebuild.
     *
     * @return the new generation
     */
    public long renew(String reason) {
        cancelPending("reload: " + reason);
        return ++generation;
    }

    /** Screen close: releases everything. Idempotent; never throws. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        cancelPending("screen closed");
        closed = true;
        for (Channel c : List.copyOf(channels.values())) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // keep releasing the rest
            }
            c.watches.clear();
        }
        channels.clear();
        edits.discard();
        host.closed(this);
    }
}
