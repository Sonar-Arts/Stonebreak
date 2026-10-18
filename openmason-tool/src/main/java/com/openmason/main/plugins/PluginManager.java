package com.openmason.main.plugins;

import com.openmason.api.OpenMasonPlugin;
import com.openmason.api.PluginInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds, orders and registers the editor's plugins (#379).
 *
 * <ul>
 *   <li><b>Discovery</b>: the built-in plugins, then every {@link OpenMasonPlugin} provider
 *       {@link ServiceLoader} finds on the module path (or class path). A provider that fails to
 *       load is reported and skipped; a second plugin with an id already taken is skipped.</li>
 *   <li><b>Order</b>: a plugin registers after everything it {@link OpenMasonPlugin#requires()
 *       requires}; otherwise discovery order (built-ins first) is kept.</li>
 *   <li><b>Failure isolation</b>: a plugin whose {@code register} throws is rolled back (all its
 *       contributions and published services removed) and marked failed; plugins requiring a
 *       failed, missing or cyclic plugin are not registered. The editor starts regardless.</li>
 * </ul>
 */
public final class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    /**
     * Id of the editor's own features (registered by the host before any plugin, not a plugin
     * itself). A plugin may name it in {@code requires()}; it is always satisfied.
     */
    public static final String CORE_ID = "openmason";

    /** What happened to a plugin. */
    public enum State {
        /** Registered and active. */
        ACTIVE,
        /** Its {@code register} threw; its contributions were rolled back. */
        FAILED,
        /** Not registered: a plugin it requires is missing, failed or part of a cycle. */
        BLOCKED,
        /** Not registered: another plugin already has its id. */
        DUPLICATE,
        /** Its provider could not be loaded. */
        UNLOADABLE
    }

    /** A plugin's outcome, for the Plugins preference page and the log. */
    public record PluginStatus(PluginInfo info, State state, String detail) {
    }

    private final EditorRegistries registries;
    private final List<OpenMasonPlugin> active = new ArrayList<>();
    private final List<PluginStatus> statuses = new ArrayList<>();

    public PluginManager(EditorRegistries registries) {
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    /**
     * The plugins on the module path (the boot layer) and the class path: what the editor loads at
     * start-up.
     */
    public List<OpenMasonPlugin> discover() {
        return collect(ServiceLoader.load(OpenMasonPlugin.class));
    }

    /**
     * The plugins {@link ServiceLoader} finds for {@code loader} (null = the boot layer and the
     * class path of the thread's context loader). Providers that fail to instantiate are recorded
     * as {@link State#UNLOADABLE} and skipped.
     */
    public List<OpenMasonPlugin> discover(ClassLoader loader) {
        return collect(loader == null
                ? ServiceLoader.load(OpenMasonPlugin.class)
                : ServiceLoader.load(OpenMasonPlugin.class, loader));
    }

    /**
     * The plugins provided by the modules of {@code layer} (and its parents): the seam for loading
     * drop-in plugin jars into their own {@link ModuleLayer} (epic #390, D1).
     */
    public List<OpenMasonPlugin> discover(ModuleLayer layer) {
        return collect(ServiceLoader.load(layer, OpenMasonPlugin.class));
    }

    private List<OpenMasonPlugin> collect(ServiceLoader<OpenMasonPlugin> serviceLoader) {
        List<OpenMasonPlugin> found = new ArrayList<>();
        Iterator<OpenMasonPlugin> it = serviceLoader.iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                logger.error("[plugins] A plugin provider could not be loaded: {}", e.getMessage(), e);
                statuses.add(new PluginStatus(new PluginInfo("?", "?", "?", java.util.Set.of(), false, "?"),
                        State.UNLOADABLE, e.getMessage()));
            }
        }
        return found;
    }

    /**
     * Registers {@code builtIns} then {@code discovered}, in dependency order. Call once, after
     * the editor's core services are published in {@link EditorRegistries#services()}.
     */
    public void registerAll(List<? extends OpenMasonPlugin> builtIns, List<? extends OpenMasonPlugin> discovered) {
        // Discovery order, first id wins.
        Map<String, OpenMasonPlugin> byId = new LinkedHashMap<>();
        Map<String, Boolean> builtIn = new HashMap<>();
        for (OpenMasonPlugin p : builtIns) {
            add(byId, builtIn, p, true);
        }
        for (OpenMasonPlugin p : discovered) {
            add(byId, builtIn, p, false);
        }

        Map<String, State> outcome = new HashMap<>();
        outcome.put(CORE_ID, State.ACTIVE);
        List<OpenMasonPlugin> pending = new ArrayList<>(byId.values());
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Iterator<OpenMasonPlugin> it = pending.iterator(); it.hasNext(); ) {
                OpenMasonPlugin p = it.next();
                PluginInfo info = PluginInfo.of(p, builtIn.get(p.id()));
                String blocker = blocker(p, byId, outcome);
                if (blocker != null) {
                    it.remove();
                    outcome.put(p.id(), State.BLOCKED);
                    record(info, State.BLOCKED, blocker);
                    progress = true;
                    break; // restart: the outcome may unblock or block others
                }
                if (p.requires().stream().allMatch(r -> outcome.get(r) == State.ACTIVE)) {
                    it.remove();
                    outcome.put(p.id(), register(p, info));
                    progress = true;
                    break; // restart from the front so discovery order is kept
                }
            }
        }
        for (OpenMasonPlugin p : pending) {
            record(PluginInfo.of(p, builtIn.get(p.id())), State.BLOCKED, "dependency cycle among " + ids(pending));
        }
    }

    /** Tells every active plugin the editor finished starting. */
    public void started() {
        for (OpenMasonPlugin p : active) {
            try {
                p.started();
            } catch (RuntimeException | LinkageError e) {
                logger.error("[plugins] {} failed in started()", p.id(), e);
            }
        }
    }

    /** Tells every active plugin, last registered first, that the editor is shutting down. */
    public void stopping() {
        List<OpenMasonPlugin> reversed = new ArrayList<>(active);
        Collections.reverse(reversed);
        for (OpenMasonPlugin p : reversed) {
            try {
                p.stopping();
            } catch (RuntimeException | LinkageError e) {
                logger.error("[plugins] {} failed in stopping()", p.id(), e);
            }
        }
    }

    /** Every plugin's outcome, in the order they were processed. */
    public List<PluginStatus> statuses() {
        return List.copyOf(statuses);
    }

    /** The active plugins, in registration order. */
    public List<PluginInfo> activePlugins() {
        return statuses.stream().filter(s -> s.state() == State.ACTIVE).map(PluginStatus::info).toList();
    }

    public boolean isActive(String id) {
        return statuses.stream().anyMatch(s -> s.state() == State.ACTIVE && s.info().id().equals(id));
    }

    private void add(Map<String, OpenMasonPlugin> byId, Map<String, Boolean> builtIn, OpenMasonPlugin p, boolean isBuiltIn) {
        String id = p.id();
        if (id == null || id.isBlank()) {
            record(PluginInfo.of(new Unnamed(p), isBuiltIn), State.UNLOADABLE, "blank plugin id");
            return;
        }
        if (byId.containsKey(id) || CORE_ID.equals(id)) {
            record(PluginInfo.of(p, isBuiltIn), State.DUPLICATE,
                    "id already used by " + (CORE_ID.equals(id) ? "the editor core" : byId.get(id).getClass().getName()));
            return;
        }
        byId.put(id, p);
        builtIn.put(id, isBuiltIn);
    }

    /** Why {@code p} can never register, or null when it still might. */
    private static String blocker(OpenMasonPlugin p, Map<String, OpenMasonPlugin> byId, Map<String, State> outcome) {
        for (String r : p.requires()) {
            if (CORE_ID.equals(r)) {
                continue;
            }
            if (!byId.containsKey(r)) {
                return "requires missing plugin '" + r + "'";
            }
            State s = outcome.get(r);
            if (s != null && s != State.ACTIVE) {
                return "requires '" + r + "', which is " + s.name().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    private State register(OpenMasonPlugin p, PluginInfo info) {
        long t0 = System.nanoTime();
        try {
            p.register(registries.contextFor(info));
        } catch (RuntimeException | LinkageError e) {
            registries.removeOwner(info.id());
            logger.error("[plugins] {} failed to register and was rolled back: {}", info.id(), e.toString(), e);
            record(info, State.FAILED, e.toString());
            return State.FAILED;
        }
        active.add(p);
        record(info, State.ACTIVE, null);
        logger.info("[plugins] {} {} registered in {} ms", info.id(), info.version(),
                (System.nanoTime() - t0) / 1_000_000);
        return State.ACTIVE;
    }

    private void record(PluginInfo info, State state, String detail) {
        statuses.add(new PluginStatus(info, state, detail));
        if (state != State.ACTIVE) {
            logger.warn("[plugins] {}: {}{}", info.id(), state, detail == null ? "" : " (" + detail + ")");
        }
    }

    private static String ids(List<OpenMasonPlugin> plugins) {
        return plugins.stream().map(OpenMasonPlugin::id).toList().toString();
    }

    /** Stand-in so a plugin with a blank id can still be reported. */
    private record Unnamed(OpenMasonPlugin plugin) implements OpenMasonPlugin {
        @Override
        public String id() {
            return "<" + plugin.getClass().getName() + ">";
        }

        @Override
        public void register(com.openmason.api.PluginContext context) {
        }
    }
}
