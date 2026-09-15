package com.openmason.main.systems.scripting.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptLibraryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveReadDeleteRoundTrip() throws IOException {
        ScriptLibraryStore store = new ScriptLibraryStore(tempDir);
        store.save("my_script", "print('hi')", false);
        assertEquals("print('hi')", store.read("my_script.py"));
        assertTrue(store.exists("my_script.py"));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("my_script.py", "x", false), "overwrite must be explicit");
        store.save("my_script.py", "print('v2')", true);
        assertEquals("print('v2')", store.read("my_script"));
        store.delete("my_script.py");
        assertThrows(IllegalArgumentException.class, () -> store.read("my_script.py"));
    }

    @Test
    void nameSanitization() {
        assertThrows(IllegalArgumentException.class,
                () -> ScriptLibraryStore.requireScriptName("../evil"));
        assertThrows(IllegalArgumentException.class,
                () -> ScriptLibraryStore.requireScriptName("a/b.py"));
        assertThrows(IllegalArgumentException.class,
                () -> ScriptLibraryStore.requireScriptName(".hidden"));
        assertEquals("thing.py", ScriptLibraryStore.requireScriptName("thing"));
        assertEquals("ops.json", ScriptLibraryStore.requireScriptName("ops.json"));
    }

    @Test
    void bundledExamplesListedAndReadable() throws IOException {
        ScriptLibraryStore store = new ScriptLibraryStore(tempDir);
        List<ScriptLibraryStore.ScriptEntry> entries = store.list();
        assertTrue(entries.stream().anyMatch(e -> e.example()
                && e.name().equals("checker_texture.py")), "bundled example missing");
        String source = store.read("checker_texture.py");
        assertTrue(source.contains("om"));
        assertThrows(IllegalArgumentException.class,
                () -> store.delete("checker_texture.py"), "examples are read-only");
    }

    @Test
    void languageDerivedFromExtension() {
        assertEquals("python", ScriptLibraryStore.languageOf("a.py"));
        assertEquals("json_ops", ScriptLibraryStore.languageOf("a.json"));
    }
}
