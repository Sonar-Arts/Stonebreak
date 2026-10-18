package com.openmason.main.plugins;

import com.openmason.api.OpenMasonPlugin;
import com.openmason.api.PluginContext;
import com.openmason.api.files.FileType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagerTest {

    private final EditorRegistries registries = new EditorRegistries();
    private final PluginManager manager = new PluginManager(registries);
    private final List<String> log = new ArrayList<>();

    /** A plugin whose registration runs {@code body}. */
    private OpenMasonPlugin plugin(String id, Set<String> requires, Consumer<PluginContext> body) {
        return new OpenMasonPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> requires() {
                return requires;
            }

            @Override
            public void register(PluginContext context) {
                log.add("register " + id);
                body.accept(context);
            }

            @Override
            public void started() {
                log.add("started " + id);
            }

            @Override
            public void stopping() {
                log.add("stopping " + id);
            }
        };
    }

    private OpenMasonPlugin plugin(String id, String... requires) {
        return plugin(id, Set.of(requires), c -> { });
    }

    private Map<String, PluginManager.State> outcomes() {
        return manager.statuses().stream().collect(Collectors.toMap(s -> s.info().id(), PluginManager.PluginStatus::state));
    }

    @Test
    void builtInsRegisterFirstAndRequirementsComeBeforeTheirDependents() {
        manager.registerAll(List.of(plugin("core"), plugin("texture", "core")),
                List.of(plugin("late", "early"), plugin("early", "core")));

        assertEquals(List.of("register core", "register texture", "register early", "register late"), log);
        assertTrue(manager.activePlugins().stream().allMatch(i -> manager.isActive(i.id())));
        assertTrue(manager.statuses().get(0).info().builtIn());
    }

    @Test
    void aThrowingPluginIsRolledBackAndTheOthersStillStart() {
        FileType goodType = FileType.of("good", "Good", ".good");
        FileType badType = FileType.of("bad", "Bad", ".bad");
        manager.registerAll(List.of(plugin("core", Set.of(), c -> c.fileTypes().register(goodType))),
                List.of(plugin("broken", Set.of("core"), c -> {
                    c.fileTypes().register(badType);
                    c.services().publish(StringBuilder.class, new StringBuilder("half-built"));
                    throw new IllegalStateException("boom");
                }), plugin("fine", "core")));

        assertEquals(PluginManager.State.FAILED, outcomes().get("broken"));
        assertEquals(PluginManager.State.ACTIVE, outcomes().get("fine"));
        assertEquals(List.of(goodType), registries.fileTypes().all(), "the broken plugin's file type was removed");
        assertTrue(registries.services().find(StringBuilder.class).isEmpty(), "and so was its published service");
        assertTrue(manager.statuses().stream().anyMatch(s -> s.detail() != null && s.detail().contains("boom")));
    }

    @Test
    void dependentsOfFailedMissingOrCyclicPluginsAreBlocked() {
        manager.registerAll(List.of(plugin("core")), List.of(
                plugin("broken", Set.of(), c -> {
                    throw new IllegalStateException("boom");
                }),
                plugin("needs-broken", "broken"),
                plugin("needs-missing", "nowhere"),
                plugin("cycle-a", "cycle-b"),
                plugin("cycle-b", "cycle-a"),
                plugin("after-cycle", "core")));

        Map<String, PluginManager.State> o = outcomes();
        assertEquals(PluginManager.State.ACTIVE, o.get("core"));
        assertEquals(PluginManager.State.FAILED, o.get("broken"));
        assertEquals(PluginManager.State.BLOCKED, o.get("needs-broken"));
        assertEquals(PluginManager.State.BLOCKED, o.get("needs-missing"));
        assertEquals(PluginManager.State.BLOCKED, o.get("cycle-a"));
        assertEquals(PluginManager.State.BLOCKED, o.get("cycle-b"));
        assertEquals(PluginManager.State.ACTIVE, o.get("after-cycle"));
    }

    @Test
    void aSecondPluginWithATakenIdIsSkipped() {
        manager.registerAll(List.of(plugin("core")), List.of(plugin("core")));

        assertEquals(List.of("register core"), log);
        assertTrue(manager.statuses().stream().anyMatch(s -> s.state() == PluginManager.State.DUPLICATE));
    }

    @Test
    void theCoreIsAlwaysSatisfiedAndItsIdCannotBeTaken() {
        manager.registerAll(List.of(), List.of(plugin("needs-core", PluginManager.CORE_ID),
                plugin(PluginManager.CORE_ID)));

        Map<String, PluginManager.State> o = outcomes();
        assertEquals(PluginManager.State.ACTIVE, o.get("needs-core"), "the core is not a plugin, but it is always there");
        assertEquals(PluginManager.State.DUPLICATE, o.get(PluginManager.CORE_ID));
        assertEquals(List.of("register needs-core"), log);
    }

    @Test
    void lifecycleHooksRunInOrderAndStopInReverse() {
        manager.registerAll(List.of(plugin("core"), plugin("ui", "core")), List.of());
        log.clear();

        manager.started();
        manager.stopping();

        assertEquals(List.of("started core", "started ui", "stopping ui", "stopping core"), log);
    }

    @Test
    void thePluginsPageSummarisesContributions() {
        manager.registerAll(List.of(plugin("core", Set.of(), c -> {
            c.fileTypes().register(FileType.of("a", "A", ".a"));
            c.fileTypes().register(FileType.of("b", "B", ".b"));
        })), List.of());

        PluginsPreferencePage page = new PluginsPreferencePage(manager, registries);
        assertEquals("2 file types", page.contributions("core"));
        assertEquals("-", page.contributions("nobody"));
    }
}
