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
