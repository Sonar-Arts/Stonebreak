package com.openmason.main.systems.scripting.mcp;

import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.mcp.McpAck;
import com.openmason.main.systems.scripting.library.ScriptLibraryStore;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP handlers over the {@link ScriptLibraryStore} plus
 * {@code scripting_open_window} via the {@code ScriptingPresenter} seam.
 */
public class ScriptLibraryService {

    private final EditorServices editorServices;
    private final ScriptLibraryStore store;

    public ScriptLibraryService(EditorServices editorServices) {
        this(editorServices, new ScriptLibraryStore());
    }

    public ScriptLibraryService(EditorServices editorServices, ScriptLibraryStore store) {
        this.editorServices = editorServices;
        this.store = store;
    }

    public Map<String, Object> list() {
        List<ScriptLibraryStore.ScriptEntry> entries = store.list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("directory", store.userDir().toString());
        out.put("scripts", entries);
        return out;
    }

    public Map<String, Object> read(String name) throws IOException {
        String source = store.read(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", ScriptLibraryStore.requireScriptName(name));
        out.put("language", ScriptLibraryStore.languageOf(name));
        out.put("source", source);
        return out;
    }

    public McpAck save(String name, String source, boolean overwrite) throws IOException {
        store.save(name, source, overwrite);
        return McpAck.ok().with("name", ScriptLibraryStore.requireScriptName(name))
                .with("directory", store.userDir().toString());
    }

    public McpAck delete(String name) throws IOException {
        store.delete(name);
        return McpAck.ok().with("deleted", ScriptLibraryStore.requireScriptName(name));
    }

    public McpAck openWindow(String script) {
        String source;
        String name = null;
        if (script != null && !script.isBlank()) {
            try {
                name = ScriptLibraryStore.requireScriptName(script);
                source = store.read(name); // validate it exists before opening
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read script '" + script + "': "
                        + e.getMessage());
            }
        } else {
            source = null;
        }
        String finalName = name;
        try {
            MainThreadExecutor.submit(() -> {
                com.openmason.main.systems.services.ScriptingPresenter presenter =
                        editorServices != null ? editorServices.getScriptingPresenter() : null;
                if (presenter == null) {
                    throw new IllegalStateException(
                            "Scripting window unavailable — is the UI running?");
                }
                if (finalName != null) {
                    presenter.showWithScript(finalName);
                } else {
                    presenter.show();
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Main thread busy — try again");
        }
        return McpAck.ok().with("visible", true);
    }
}
