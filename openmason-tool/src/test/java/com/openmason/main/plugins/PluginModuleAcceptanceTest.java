package com.openmason.main.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.api.OpenMasonPlugin;
import com.openmason.api.files.FileType;
import com.openmason.api.menu.MenuEntry;
import com.openmason.api.menu.MenuItem;
import com.openmason.main.systems.io.FileTypeService;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.WriteRoot;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.mcp.BuiltinMcpTools;
import com.openmason.main.systems.mcp.McpGuide;
import com.openmason.main.systems.mcp.McpSurface;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.mcp.McpToolRegistryFactory;
import com.openmason.main.systems.mcp.ToolCapabilities;
import com.openmason.main.systems.menus.MenuBarCoordinator;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #379 acceptance: a third-party plugin, as a real JPMS module ({@code provides
 * com.openmason.api.OpenMasonPlugin}), compiled here and loaded through its own {@link ModuleLayer},
 * contributes a workspace, a menu command, an MCP tool and a file type through
 * {@code PluginContext} only; the editor's registries, tool surface, file dialogs' kinds and
 * Project Browser see them like the built-in ones.
 */
@Tag("integration")
class PluginModuleAcceptanceTest {

    private static final String MODULE_INFO = """
            module hello.plugin {
                requires com.openmason;
                requires com.fasterxml.jackson.databind;
                provides com.openmason.api.OpenMasonPlugin with hello.HelloPlugin;
            }
            """;

    private static final String PLUGIN = """
            package hello;

            import com.openmason.api.OpenMasonPlugin;
            import com.openmason.api.PluginContext;
            import com.openmason.api.files.FileOpener;
            import com.openmason.api.files.FileType;
            import com.openmason.api.knowledge.GuideTopic;
            import com.openmason.api.mcp.McpToolContribution;
            import com.openmason.api.menu.MenuItem;
            import com.openmason.api.menu.TopMenu;
            import com.openmason.api.workspace.WorkspaceDescriptor;
            import com.openmason.main.systems.keybinds.KeybindAction;
            import com.openmason.main.systems.keybinds.KeybindRegistry;
            import com.openmason.main.systems.mcp.McpSchema;
            import com.openmason.main.systems.mcp.McpTool;
            import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;

            public final class HelloPlugin implements OpenMasonPlugin {
                @Override public String id() { return "hello"; }
                @Override public String displayName() { return "Hello"; }
                @Override public String version() { return "0.1"; }

                @Override
                public void register(PluginContext c) {
                    c.workspaces().register(WorkspaceDescriptor.builder("hello.ws", "Hello")
                            .description("A plugin's own workspace").layoutVersion(2)
                            .dock((dockspaceId, w, h) -> { }).build());

                    c.commands().register(new KeybindAction("hello.say", "Say Hello", "Hello",
                            KeybindRegistry.GLOBAL_CONTEXT, ShortcutKey.ctrlShift(72), () -> greet(c, "menu")));
                    c.menus().register(TopMenu.of("hello", "Hello", 500));
                    c.menuEntries().register(MenuItem.command("hello.menu.say", "hello", 10, "Say Hello", "hello.say"));
                    c.menuEntries().register(MenuItem.of("hello.tools.open", TopMenu.TOOLS, 900, "Hello Tool",
                            () -> greet(c, "tools")));

                    c.mcpTools().register(new McpToolContribution("hello", ctx -> ctx.register(new McpTool(
                            "hello_say", "Say hello to someone.",
                            McpSchema.of(ctx.mapper()).str("name", "Who to greet").build(),
                            args -> "hello " + args.path("name").asText("world")))));
                    c.guideTopics().register(new GuideTopic("hello", () -> "# Hello\\nGreet politely."));

                    c.fileTypes().register(FileType.of("hello", "Hello Greeting", ".hello")
                            .withSave("project", "Greetings", "greeting")
                            .withBrowse(".HELLO Greeting", 50, "", "Greetings"));
                    c.fileOpeners().register(new FileOpener("hello", path -> greet(c, "open " + path.getFileName())));
                }

                private static void greet(PluginContext c, String what) {
                    c.services().find(StringBuilder.class).ifPresent(sb -> sb.append(what).append(';'));
                }
            }
            """;

    private static ModuleLayer pluginLayer;

    @TempDir
    Path project;

    private final EditorRegistries registries = new EditorRegistries();
    private final PluginManager manager = new PluginManager(registries);
    private final StringBuilder greetings = new StringBuilder();

    @BeforeAll
    static void compilePluginModule(@TempDir Path build) throws Exception {
        assumeTrue(ModuleLayer.boot().findModule("com.openmason").isPresent(),
                "needs the tool on the module path (Maven/surefire module mode)");
        String modulePath = System.getProperty("jdk.module.path");
        assumeTrue(modulePath != null && !modulePath.isBlank(), "no jdk.module.path");
        // java.util.spi.ToolProvider (java.base): the tool module does not read java.compiler.
        var javac = java.util.spi.ToolProvider.findFirst("javac").orElse(null);
        assumeTrue(javac != null, "no javac tool in this runtime");

        Path src = build.resolve("src");
        Path out = build.resolve("mods/hello.plugin");
        Files.createDirectories(src.resolve("hello"));
        Files.writeString(src.resolve("module-info.java"), MODULE_INFO);
        Files.writeString(src.resolve("hello/HelloPlugin.java"), PLUGIN);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        int rc = javac.run(errStream, errStream, "--module-path", modulePath, "-d", out.toString(), "-proc:none",
                src.resolve("module-info.java").toString(), src.resolve("hello/HelloPlugin.java").toString());
        assertEquals(0, rc, () -> "plugin module failed to compile:\n" + err.toString(StandardCharsets.UTF_8));

        ModuleLayer boot = ModuleLayer.boot();
        Configuration cf = boot.configuration().resolve(ModuleFinder.of(out), ModuleFinder.of(), Set.of("hello.plugin"));
        pluginLayer = boot.defineModulesWithOneLoader(cf, ClassLoader.getSystemClassLoader());
    }

    @AfterEach
    void unregister() {
        registries.removeOwner("hello"); // also takes its command out of the shared keybind registry
    }

    private void loadPlugin() {
        registries.services().put(StringBuilder.class, greetings);
        registries.forwardCommandsTo(KeybindRegistry.getInstance());
        List<OpenMasonPlugin> found = manager.discover(pluginLayer);
        assertEquals(List.of("hello"), found.stream().map(OpenMasonPlugin::id).toList(), "ServiceLoader finds the module");
        manager.registerAll(List.of(), found);
        assertTrue(manager.isActive("hello"), () -> "plugin did not register: " + manager.statuses());
        assertEquals("hello.plugin", manager.activePlugins().get(0).module());
    }

    @Test
    void itsWorkspaceBecomesATab() {
        loadPlugin();
        WorkspaceState tabs = new WorkspaceState(registries.workspaces());
        assertTrue(tabs.isOpen("hello.ws"));
        assertEquals("hello.ws", tabs.current(), "the only workspace is in front");
        assertEquals(2, tabs.descriptor("hello.ws").orElseThrow().layoutVersion());
        assertEquals("OpenMasonWorkspace.hello.ws", tabs.descriptor("hello.ws").orElseThrow().dockspaceName());
    }

    @Test
    void itsMenuCommandIsListedAndBoundToItsKeybindCommand() {
        loadPlugin();
        MenuBarCoordinator bar = new MenuBarCoordinator(new UIVisibilityState(), registries.menus(),
                registries.menuEntries());
        List<MenuEntry> hello = bar.entriesOf("hello");
        assertEquals(1, hello.size());
        MenuItem say = (MenuItem) hello.get(0);
        assertEquals("hello.say", say.commandId());
        assertEquals(List.of("hello.tools.open"), bar.entriesOf("tools").stream().map(MenuEntry::id).toList());

        var command = KeybindRegistry.getInstance().getAction("hello.say");
        assertNotNull(command, "the command reached the keybind registry (Keybinds page, shortcut dispatch)");
        assertEquals(KeybindRegistry.GLOBAL_CONTEXT, command.getContext());
        command.execute();
        assertEquals("menu;", greetings.toString());

        registries.removeOwner("hello");
        assertNull(KeybindRegistry.getInstance().getAction("hello.say"), "rollback unregisters the command");
    }

    @Test
    void itsMcpToolJoinsTheToolSurface() throws Exception {
        loadPlugin();
        ObjectMapper mapper = new ObjectMapper();
        List<com.openmason.api.mcp.McpToolContribution> groups = new ArrayList<>(BuiltinMcpTools.all());
        groups.addAll(registries.mcpTools().all());
        McpSurface surface = new McpSurface(groups, registries.guideTopics().all(), registries.knowledgePacks().all());

        McpToolRegistry tools = McpToolRegistryFactory.build(EditorServices.empty(), mapper, ToolCapabilities.none(), surface);
        assertEquals(McpToolRegistryFactory.build(EditorServices.empty(), mapper, ToolCapabilities.none()).all().size() + 1,
                tools.all().size());
        Object result = tools.get("hello_say").handler().call(mapper.readTree("{\"name\":\"Mason\"}"));
        assertEquals("hello Mason", result);

        McpGuide guide = new McpGuide(registries.guideTopics().all());
        assertEquals(List.of("hello"), guide.topics());
        assertTrue(guide.topic("hello").contains("Greet politely"));
    }

    @Test
    void itsFileTypeIsSavedListedAndOpenedLikeABuiltIn() throws Exception {
        loadPlugin();
        FileTypeService files = new FileTypeService(registries.fileTypes(), registries.fileOpeners());
        FileType hello = files.forName("Morning.HELLO").orElseThrow();
        assertEquals("hello", hello.id());

        WriteKind kind = files.saveKinds().stream().filter(k -> k.id().equals("hello")).findFirst().orElseThrow();
        assertEquals(WriteRoot.PROJECT, kind.defaultRoot());
        assertEquals("Greetings", kind.defaultSubdir());
        assertEquals("wave.hello", kind.ensureExtension("wave"));

        Files.createDirectories(project.resolve("Greetings"));
        Files.writeString(project.resolve("top.hello"), "hi");
        Files.writeString(project.resolve("Greetings/nested.hello"), "hi");
        Files.writeString(project.resolve("ignored.txt"), "no");
        ProjectAssetScanner scanner = new ProjectAssetScanner();
        scanner.setTypes(files::browsable);
        scanner.setRoot(project);
        assertEquals(List.of("nested", "top"), scanner.scan().stream().map(ProjectAssetScanner.AssetEntry::name)
                .sorted().toList());

        assertTrue(files.open(project.resolve("top.hello")));
        assertEquals("open top.hello;", greetings.toString());
    }
}
