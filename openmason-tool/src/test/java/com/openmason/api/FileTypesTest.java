package com.openmason.api;

import com.openmason.api.files.FileType;
import com.openmason.api.files.FileTypes;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTypesTest {

    private static final FileType JSON = FileType.of("json", "JSON", "json");
    private static final FileType USS = new FileType("uss", "Style sheet", List.of(".uss.json"), null, null);

    @Test
    void extensionsAreNormalisedAndMatchedCaseInsensitively() {
        assertEquals(".json", JSON.extension());
        assertEquals("json", JSON.filterSpec());
        assertTrue(JSON.matches("Data.JSON"));
        assertFalse(JSON.matches("data.jsonl"));
        assertEquals("theme", USS.stripExtension("theme.uss.json"));
    }

    @Test
    void theLongestExtensionWins() {
        List<FileType> types = List.of(JSON, USS);
        assertEquals(USS, FileTypes.match(types, "pause.uss.json").orElseThrow());
        assertEquals(JSON, FileTypes.match(types, Path.of("dir", "fixture.json")).orElseThrow());
        assertTrue(FileTypes.match(types, "model.omo").isEmpty());
    }

    @Test
    void saveAndBrowseRulesAreOptional() {
        FileType t = FileType.of("hello", "Hello", ".hello").withSave("project", "Hello", "greeting")
                .withBrowse(".HELLO Greeting", 9, "", "Hello/**");
        assertEquals("Hello", t.save().subdir());
        assertEquals(List.of("", "Hello/**"), t.browse().folders());
        assertEquals(null, JSON.save());
    }
}
