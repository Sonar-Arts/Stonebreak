package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiRequirements;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.format.omui.io.CanonicalJson;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The editor's stand-in host (#289): deterministic data and action responses from a fixture,
 * with no game services behind it. It offers every contract the document declares, so the
 * preview runs what the game would, and records each call so tests and the editor can inspect
 * them. Async work only advances when {@link #release} is called or the queue is drained.
 *
 * <p>Fixture JSON (every member optional):
 * <pre>{@code
 * {
 *   "data":        { "session": { "online": true } },
 *   "collections": { "slots": { "identity": "id", "items": [ { "id": 1 } ] } },
 *   "contracts":   { "session": "stonebreak:session" },          // root -> contract id
 *   "editable":    { "settings": { "commit": "stonebreak:settings.apply" } },
 *   "actions": {
 *     "stonebreak:network.resync": { "params": { "full": "bool" }, "result": { "chunks": 12 } },
 *     "stonebreak:world.load":     { "pending": true },          // held until release()
 *     "stonebreak:world.delete":   { "error": "permission denied" }
 *   }
 * }
 * }</pre>
 * Any other top-level member is shorthand for a {@code data} root, so the format's in-archive
 * {@code editor/fixtures.json} ({@code {"session": {"online": true}}}) is a fixture as it is.
 * Data types are inferred from the fixture values; a root without a {@code contracts} entry
 * belongs to {@code fixture:<root>}. An action without {@code contract} belongs to the longest
 * declared contract id it extends ({@code stonebreak:session.leave} → {@code stonebreak:session}).
 */
public final class FixtureHost {

    /** One recorded invocation. */
    public record Call(String actionId, UiValue.Obj args, CallSite site) {
    }

    private final UiHost host = new UiHost();
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, Deque<CompletableFuture<UiValue>>> held = new HashMap<>();
    /** Fixture values as written (before type inference), for {@link #compatibility}. */
    private final Map<String, UiValue> fixtureData = new LinkedHashMap<>();
    private final Map<String, List<UiValue>> fixtureCollections = new LinkedHashMap<>();
    private final Map<String, UiValue> fixtureResults = new LinkedHashMap<>();

    private FixtureHost() {
    }

    /** The archive's own {@code editor/fixtures.json} (#284), or no data when it has none. */
    public static final String ARCHIVE_ENTRY = "editor/fixtures.json";

    private static final java.util.Set<String> SECTIONS = java.util.Set.of("data", "collections", "contracts",
        "editable", "actions");

    public static FixtureHost forArchive(com.openmason.engine.format.omui.OmuiArchive doc) {
        com.openmason.engine.format.omui.UiBytes bytes = doc.editor().get(ARCHIVE_ENTRY);
        return bytes == null ? empty(doc.manifest()) : parse(bytes.toArray(), ARCHIVE_ENTRY, doc.manifest());
    }

    public static FixtureHost empty(UiRequirements declared) {
        return of(UiValue.Obj.EMPTY, declared);
    }

    /** Parses fixture JSON; malformed JSON is an {@link IllegalArgumentException} naming {@code entry}. */
    public static FixtureHost parse(byte[] json, String entry, UiRequirements declared) {
        UiDiagnostics d = new UiDiagnostics();
        UiValue v = CanonicalJson.parse(json, entry, d);
        if (v == null) {
            throw new IllegalArgumentException("fixture " + entry + ": " + d.list());
        }
        return of(v, declared);
    }

    public static FixtureHost of(UiValue fixture, UiRequirements declared) {
        FixtureHost f = new FixtureHost();
        f.load(fixture instanceof UiValue.Obj o ? o : UiValue.Obj.EMPTY, declared);
        return f;
    }

    public UiHost host() {
        return host;
    }

    /** Every call so far, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The cell of a fixture data root, to drive notifications from a test or editor control. */
    public DataCell cell(String root) {
        DataRoot r = host.data().root(root);
        return r != null && r.source() instanceof DataCell c ? c : null;
    }

    public DataCollection collection(String root) {
        DataRoot r = host.data().root(root);
        return r != null && r.source() instanceof DataCollection c ? c : null;
    }

    /** Completes the oldest held call of {@code actionId}; the result reaches the UI at the next drain. */
    public boolean release(String actionId, UiValue result) {
        CompletableFuture<UiValue> f = next(actionId);
        return f != null && f.complete(result);
    }

    public boolean releaseFailure(String actionId, String message) {
        CompletableFuture<UiValue> f = next(actionId);
        return f != null && f.completeExceptionally(new IllegalStateException(message));
    }

    public int heldCount(String actionId) {
        Deque<CompletableFuture<UiValue>> q = held.get(actionId);
        return q == null ? 0 : q.size();
    }

    private CompletableFuture<UiValue> next(String actionId) {
        Deque<CompletableFuture<UiValue>> q = held.get(actionId);
        return q == null ? null : q.pollFirst();
    }

    // ── loading ─────────────────────────────────────────────────────────────

    private void load(UiValue.Obj f, UiRequirements declared) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        if (declared != null) {
            for (UiManifest.HostRequirement h : declared.hostApis()) {
                versions.merge(h.id(), h.version(), Math::max);
                host.offer(HostContract.of(h.id(), h.version()));
            }
            for (UiManifest.HostRequirement p : declared.providers()) {
                host.offerProvider(p.id(), p.version());
            }
        }
        Map<String, String> contracts = strings(f.get("contracts"));
        Map<String, String> commits = new HashMap<>();
        obj(f.get("editable")).fields().forEach((root, spec) -> {
            if (obj(spec).get("commit") instanceof UiValue.Str s) {
                commits.put(root, s.value());
            }
        });
        Map<String, UiValue> data = new LinkedHashMap<>(obj(f.get("data")).fields());
        f.fields().forEach((k, v) -> {
            if (!SECTIONS.contains(k)) {
                data.putIfAbsent(k, v);
            }
        });
        data.forEach((root, value) -> {
            fixtureData.put(root, value);
            HostContract c = contract(root, contracts, versions);
            DataCell cell = new DataCell(DataType.infer(value), value);
            String commit = commits.get(root);
            if (commit == null) {
                host.data().register(root, cell, c);
            } else {
                host.data().registerEditable(root, cell, c, new EditPolicy(commit, null));
                host.actions().register(ActionSpec.of(commit, c, DataType.object("value", DataType.ANY), DataType.ANY),
                    (args, ctx) -> {
                        record(commit, args, ctx);
                        cell.set(args.get("value"));
                        return CompletableFuture.completedFuture(UiValue.NULL);
                    });
            }
        });
        obj(f.get("collections")).fields().forEach((root, spec) -> {
            UiValue.Obj s = obj(spec);
            String identity = s.get("identity") instanceof UiValue.Str i ? i.value() : "id";
            DataCollection col = new DataCollection(DataType.list(DataType.ANY, identity));
            if (s.get("items") instanceof UiValue.Arr items) {
                col.setAll(items.items());
                fixtureCollections.put(root, items.items());
            } else {
                fixtureCollections.put(root, List.of());
            }
            host.data().register(root, col, contract(root, contracts, versions));
        });
        obj(f.get("actions")).fields().forEach((id, spec) -> registerAction(id, obj(spec), versions));
    }

    private void registerAction(String id, UiValue.Obj spec, Map<String, Integer> versions) {
        String contractId = spec.get("contract") instanceof UiValue.Str s ? s.value()
            : versions.keySet().stream().filter(k -> id.equals(k) || id.startsWith(k + "."))
                .max(java.util.Comparator.comparingInt(String::length)).orElse("fixture:actions");
        HostContract c = HostContract.of(contractId, versions.getOrDefault(contractId, 1));
        Map<String, DataType> params = new LinkedHashMap<>();
        strings(spec.get("params")).forEach((name, type) -> {
            ValueType t = ValueType.fromWire(type);
            params.put(name, t == null || t == ValueType.LIST || t == ValueType.OBJECT ? DataType.ANY : DataType.of(t));
        });
        UiValue result = spec.get("result");
        if (result != null) {
            fixtureResults.put(id, result);
        }
        DataType resultType = result == null ? DataType.ANY : DataType.infer(result);
        boolean pending = spec.get("pending") instanceof UiValue.Bool b && b.value();
        String error = spec.get("error") instanceof UiValue.Str e ? e.value() : null;
        ActionSpec.Reentrancy reentrancy = spec.get("reentrancy") instanceof UiValue.Str r
            ? ActionSpec.Reentrancy.valueOf(r.value().toUpperCase(java.util.Locale.ROOT).replace('-', '_'))
            : ActionSpec.Reentrancy.REJECT_WHILE_PENDING;
        host.actions().register(new ActionSpec(id, c, 1, DataType.object(params), resultType, reentrancy, true),
            (args, ctx) -> {
                record(id, args, ctx);
                if (error != null) {
                    return CompletableFuture.failedFuture(new IllegalStateException(error));
                }
                if (pending) {
                    CompletableFuture<UiValue> future = new CompletableFuture<>();
                    held.computeIfAbsent(id, k -> new ArrayDeque<>()).addLast(future);
                    ctx.onCancel(() -> held.getOrDefault(id, new ArrayDeque<>()).remove(future));
                    return future;
                }
                return CompletableFuture.completedFuture(result == null ? UiValue.NULL : result);
            });
    }

    // ── drift against the real host ─────────────────────────────────────────

    /**
     * Where this fixture disagrees with {@code reference}, the host the document really runs
     * against (the game's): data roots or actions the reference does not have, a root under a
     * different contract, fixture values or results the reference's schema would reject, and
     * action parameters it does not declare (or declares with another type). A fixture may leave
     * fields out (a preview shows a subset), but everything it does say must be true of the real
     * host, so a preview never works with data the game can never produce.
     *
     * @return problems, empty when the fixture is a faithful stand-in
     */
    public List<String> compatibility(UiHost reference) {
        List<String> out = new ArrayList<>();
        fixtureData.forEach((root, value) -> {
            DataRoot ref = reference.data().root(root);
            if (ref == null) {
                out.add("data root '" + root + "': the host has none");
                return;
            }
            sameContract(root, ref, out);
            String p = conforms(ref.source().type(), value, root);
            if (p != null) {
                out.add("data root '" + root + "': " + p);
            }
            DataRoot mine = host.data().root(root);
            if (mine != null && mine.editable() != ref.editable()) {
                out.add("data root '" + root + "': " + (ref.editable() ? "editable" : "read-only")
                    + " on the host, " + (mine.editable() ? "editable" : "read-only") + " in the fixture");
            }
        });
        fixtureCollections.forEach((root, items) -> {
            DataRoot ref = reference.data().root(root);
            if (ref == null) {
                out.add("collection '" + root + "': the host has none");
                return;
            }
            sameContract(root, ref, out);
            if (!(ref.source().type() instanceof DataType.ListOf l)) {
                out.add("collection '" + root + "': the host's root is " + ref.source().type().describe());
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                String p = conforms(l.item(), items.get(i), root + "[" + i + "]");
                if (p != null) {
                    out.add("collection '" + root + "': " + p);
                }
            }
        });
        for (ActionSpec mine : host.actions().specs()) {
            ActionRegistry.Entry ref = reference.actions().entry(mine.id());
            if (ref == null) {
                out.add("action " + mine.id() + ": the host has none");
                continue;
            }
            ActionSpec r = ref.spec();
            if (!r.contract().id().equals(mine.contract().id())) {
                out.add("action " + mine.id() + ": contract " + mine.contract().id() + " in the fixture, "
                    + r.contract().id() + " on the host");
            }
            mine.params().fields().forEach((name, type) -> {
                DataType rt = r.params().fields().get(name);
                if (rt == null) {
                    out.add("action " + mine.id() + ": parameter '" + name + "' is not declared by the host");
                } else if (!(type instanceof DataType.Any) && !rt.describe().equals(type.describe())
                    && !rt.orNull().describe().equals(type.orNull().describe())) {
                    out.add("action " + mine.id() + ": parameter '" + name + "' is " + type.describe()
                        + " in the fixture, " + rt.describe() + " on the host");
                }
            });
            UiValue result = fixtureResults.get(mine.id());
            if (result != null) {
                String p = r.result().problem(result);
                if (p != null) {
                    out.add("action " + mine.id() + ": fixture result " + p);
                }
            }
        }
        return out;
    }

    private void sameContract(String root, DataRoot ref, List<String> out) {
        DataRoot mine = host.data().root(root);
        if (mine != null && !mine.contract().id().startsWith("fixture:")
            && !mine.contract().id().equals(ref.contract().id())) {
            out.add("data root '" + root + "': contract " + mine.contract().id() + " in the fixture, "
                + ref.contract().id() + " on the host");
        }
    }

    /** {@code type.problem}, except that members the fixture leaves out are fine. */
    private static String conforms(DataType type, UiValue value, String at) {
        if (type instanceof DataType.Obj o && value instanceof UiValue.Obj v) {
            for (Map.Entry<String, UiValue> f : v.fields().entrySet()) {
                DataType ft = o.fields().get(f.getKey());
                if (ft == null) {
                    return at + "." + f.getKey() + " is not a field of " + o.describe();
                }
                String p = conforms(ft, f.getValue(), at + "." + f.getKey());
                if (p != null) {
                    return p;
                }
            }
            return null;
        }
        if (type instanceof DataType.ListOf l && value instanceof UiValue.Arr a) {
            for (int i = 0; i < a.items().size(); i++) {
                String p = conforms(l.item(), a.items().get(i), at + "[" + i + "]");
                if (p != null) {
                    return p;
                }
            }
            return null;
        }
        return type.problem(value, at);
    }

    private void record(String id, UiValue.Obj args, ActionContext ctx) {
        calls.add(new Call(id, args, ctx.site()));
    }

    private static HostContract contract(String root, Map<String, String> contracts, Map<String, Integer> versions) {
        String id = contracts.getOrDefault(root, "fixture:" + root.toLowerCase(java.util.Locale.ROOT));
        return HostContract.of(id, versions.getOrDefault(id, 1));
    }

    private static UiValue.Obj obj(UiValue v) {
        return v instanceof UiValue.Obj o ? o : UiValue.Obj.EMPTY;
    }

    private static Map<String, String> strings(UiValue v) {
        Map<String, String> out = new LinkedHashMap<>();
        obj(v).fields().forEach((k, x) -> {
            if (x instanceof UiValue.Str s) {
                out.put(k, s.value());
            }
        });
        return out;
    }
}
