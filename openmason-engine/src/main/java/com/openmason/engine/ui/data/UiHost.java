package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.omui.UiRequirements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The one host contract every authoring surface talks to (#289): inspector bindings, Lua
 * code-behind (#292) and compiled graphs (#291) all read data and invoke actions through a
 * {@link UiScope} opened here, so they see the same values, the same validation and the same
 * lifetime rules. The game builds one with live sources; the editor preview builds one from
 * fixtures ({@link FixtureHost}) with no game services behind it.
 *
 * <p>{@link #advanceEpoch} marks a world leave or disconnect: every pending action in every scope
 * is cancelled, and completions from the old world are rejected when they arrive.
 */
public final class UiHost {

    /** Newest Lua {@code ui} API this runtime implements (manifest {@code uiApi}). */
    public static final int UI_API = 1;
    /** Layout semantics this runtime lays out. */
    public static final Set<String> LAYOUT_SEMANTICS = Set.of("flex-1");

    private final UiThreadQueue queue = new UiThreadQueue();
    private final Map<String, Integer> offered = new TreeMap<>();
    private final Map<String, Integer> providers = new TreeMap<>();
    private final DataRegistry data = new DataRegistry(queue, this::changed);
    private final ActionRegistry actions = new ActionRegistry(this::changed);
    private final Set<UiScope> scopes = new LinkedHashSet<>();
    private long epoch = 1;
    private int revision;

    public DataRegistry data() {
        return data;
    }

    public ActionRegistry actions() {
        return actions;
    }

    public UiThreadQueue queue() {
        return queue;
    }

    /** Runs posted work (async completions, cross-thread data posts). Call once per frame on the UI thread. */
    public int drain() {
        return queue.drain();
    }

    /** Declares a contract implemented by host behaviour other than data roots and actions. */
    public void offer(HostContract contract) {
        offered.merge(contract.id(), contract.version(), Math::max);
        changed();
    }

    /** A namespaced widget/draw provider ({@code stonebreak:CrucibleView}) at {@code version}. */
    public void offerProvider(String id, int version) {
        providers.merge(id, version, Math::max);
        changed();
    }

    /** Contract id → newest version, over data roots, actions and {@link #offer}ed contracts. */
    public Map<String, Integer> contracts() {
        Map<String, Integer> out = new TreeMap<>(offered);
        for (DataRoot r : data.roots()) {
            out.merge(r.contract().id(), r.contract().version(), Math::max);
        }
        for (ActionSpec s : actions.specs()) {
            out.merge(s.contract().id(), s.contract().version(), Math::max);
        }
        return Collections.unmodifiableMap(out);
    }

    public UiHostProfile profile() {
        return new UiHostProfile(UI_API, LAYOUT_SEMANTICS, contracts(), providers);
    }

    /** Requirement check of a document or export against this host (format diagnostics). */
    public List<UiDiagnostic> check(UiRequirements requirements) {
        return profile().check(requirements);
    }

    /** Bumped by every registration; lets a preview notice a host that changed under it. */
    public int revision() {
        return revision;
    }

    // ── scopes and lifetime ────────────────────────────────────────────────

    /**
     * Opens the scope of one document instance (a screen while it is open).
     *
     * @param declaredContracts contract ids the document's manifest lists; an action outside them
     *                          fails with {@code CAPABILITY_MISSING}. {@code null} skips that check.
     * @param problems          receives run-time findings (failed results, stale completions)
     */
    public UiScope openScope(String documentId, Set<String> declaredContracts, Consumer<UiProblem> problems) {
        UiScope s = new UiScope(this, documentId, declaredContracts, problems);
        scopes.add(s);
        return s;
    }

    void closed(UiScope s) {
        scopes.remove(s);
    }

    public List<UiScope> openScopes() {
        return List.copyOf(scopes);
    }

    /** Current world/session epoch; action calls remember the one they started in. */
    public long epoch() {
        return epoch;
    }

    /**
     * World leave, disconnect or session change: cancels every pending action in every scope.
     * Late completions of those calls are rejected as stale.
     */
    public void advanceEpoch(String reason) {
        epoch++;
        for (UiScope s : new ArrayList<>(scopes)) {
            s.cancelPending("epoch change: " + Objects.requireNonNullElse(reason, ""));
        }
    }

    private void changed() {
        revision++;
    }
}
