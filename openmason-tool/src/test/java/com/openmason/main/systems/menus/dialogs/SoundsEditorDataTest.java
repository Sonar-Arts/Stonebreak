package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-contract tests for {@link SoundsEditor}: the load/save conversions
 * between the manifest {@link SoundData} and the editor's row model must be
 * lossless, embedded entry paths must follow the shared SBO 1.7 / SBE 1.4
 * {@code sounds/<event>_<n>.<ext>} convention aligned between
 * {@link SoundsEditor#toSoundData()} and
 * {@link SoundsEditor#soundBytesByFilename()}, and validation must flag the
 * save-blocking states. (The editor's constructor and data methods are
 * headless by design — no ImGui/GL/OpenAL calls.)
 */
class SoundsEditorDataTest {

    private static final byte[] WAV_A = "RIFF-sample-A".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WAV_B = "RIFF-sample-B".getBytes(StandardCharsets.UTF_8);

    private static SoundsEditor editor() {
        return new SoundsEditor(() -> {}, null);
    }

    @Test
    void emptySectionReturnsNullSoManifestFieldStaysAbsent() {
        SoundsEditor editor = editor();
        assertTrue(editor.isEmpty());
        assertNull(editor.toSoundData());
        assertTrue(editor.soundBytesByFilename().isEmpty());
        assertNull(editor.validate());

        editor.load(null, f -> null);
        assertNull(editor.toSoundData());
    }

    @Test
    void resourceDefRoundTrips() {
        SoundDef def = new SoundDef("step", null, null, "/sounds/GrassWalk.wav",
                0.8f, 0.9f, 1.1f, true);
        SoundsEditor editor = editor();
        editor.load(new SoundData(List.of(def)), f -> null);

        SoundData out = editor.toSoundData();
        assertNotNull(out);
        assertEquals(1, out.sounds().size());
        assertEquals(def, out.sounds().get(0));
        assertNull(editor.validate());
    }

    @Test
    void embeddedDefsRegenerateAlignedEntryPathsPerEvent() {
        SoundDef first = new SoundDef("break", "sounds/break_0.wav", "abc", null,
                1.0f, 0.9f, 1.1f, true);
        SoundDef second = new SoundDef("break", "sounds/break_1.ogg", "def", null,
                0.5f, 1.0f, 1.0f, false);
        SoundsEditor editor = editor();
        editor.load(new SoundData(List.of(first, second)),
                f -> f.equals("sounds/break_0.wav") ? WAV_A : WAV_B);

        SoundData out = editor.toSoundData();
        assertEquals("sounds/break_0.wav", out.sounds().get(0).filename());
        // Checksums are stubs for the serializer to recompute.
        assertEquals("", out.sounds().get(0).checksum());
        // The second def keeps its original extension and its per-event index.
        assertEquals("sounds/break_1.ogg", out.sounds().get(1).filename());
        assertEquals(0.5f, out.sounds().get(1).volume());
        assertEquals(false, out.sounds().get(1).variation());

        Map<String, byte[]> bytes = editor.soundBytesByFilename();
        assertEquals(2, bytes.size());
        assertArrayEquals(WAV_A, bytes.get("sounds/break_0.wav"));
        assertArrayEquals(WAV_B, bytes.get("sounds/break_1.ogg"));
        assertNull(editor.validate());
    }

    @Test
    void mixedSectionKeepsDeclarationOrder() {
        SoundsEditor editor = editor();
        editor.load(new SoundData(List.of(
                new SoundDef("hurt", null, null, "/sounds/Hurt.wav", 1.0f, 0.9f, 1.1f, true),
                new SoundDef("death", "sounds/death_0.wav", "x", null, 1.0f, 1.0f, 1.0f, false),
                new SoundDef("hurt", null, null, "/sounds/Hurt2.wav", 1.0f, 0.9f, 1.1f, true))),
                f -> WAV_A);

        SoundData out = editor.toSoundData();
        assertEquals(List.of("hurt", "death", "hurt"),
                out.sounds().stream().map(SoundDef::event).toList());
        assertEquals(Map.of("sounds/death_0.wav", WAV_A).keySet(),
                editor.soundBytesByFilename().keySet());
    }

    @Test
    void validationFlagsMissingEmbeddedBytes() {
        SoundsEditor editor = editor();
        editor.load(new SoundData(List.of(
                new SoundDef("place", "sounds/place_0.wav", "x", null, 1.0f, 0.9f, 1.1f, true))),
                f -> null); // bytes lookup misses — row has no audio
        String error = editor.validate();
        assertNotNull(error);
        assertTrue(error.contains("place"));
    }

    @Test
    void wellFormedResourceDefPassesValidation() {
        SoundsEditor editor = editor();
        editor.load(new SoundData(List.of(
                new SoundDef("hit", null, null, "/sounds/Hit.wav", 1.0f, 0.9f, 1.1f, true))),
                f -> null);
        assertNull(editor.validate());
    }
}
