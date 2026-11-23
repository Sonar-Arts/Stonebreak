package com.stonebreak.audio.music;

import com.openmason.engine.audio.MusicDirector.Track;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped {@code songs/music.json} and the catalog parser's rules. */
class MusicCatalogTest {

    private static MusicCatalog parse(String json) throws IOException {
        return MusicCatalog.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shippedCatalogKeepsTheMenuAndBattlePlaylists() throws IOException {
        MusicCatalog catalog = MusicCatalog.load();
        assertEquals(List.of(
                new Track("music_look_beyond", "/songs/menu music/Look Beyond.ogg"),
                new Track("music_venusian_drift", "/songs/menu music/Venusian Drift.ogg"),
                new Track("music_whispering_woods_lofi_lullaby", "/songs/menu music/Whispering Woods (Lofi Lullaby).ogg")),
                catalog.playlist("menu"), "Look Beyond first: a fresh launch opens on the main menu theme");
        assertEquals(List.of(new Track("music_the_deadly_dance", "/songs/battle music/The Deadly Dance.ogg")),
                catalog.playlist("battle"));
    }

    @Test
    void shippedShadeThemePlaysAtNightOnTheShadeSurface() throws IOException {
        MusicCue shade = MusicCatalog.load().cues().stream()
                .filter(c -> c.id().equals("the_shade")).findFirst().orElseThrow();
        assertEquals(new Track("music_the_shade", "/songs/biome music/The Shade.ogg"), shade.track());
        assertEquals(MusicCue.Time.NIGHT, shade.time());
        assertEquals(Set.of(BiomeType.SHADE), shade.biomes());
        assertEquals(Boolean.TRUE, shade.surface());
    }

    @Test
    void everyShippedTrackExists() throws IOException {
        MusicCatalog catalog = MusicCatalog.load();
        List<Track> tracks = new ArrayList<>();
        catalog.playlists().values().forEach(tracks::addAll);
        catalog.cues().forEach(c -> tracks.add(c.track()));
        assertFalse(tracks.isEmpty());
        for (Track t : tracks) {
            try (InputStream in = MusicCatalog.class.getResourceAsStream(t.resourcePath())) {
                assertNotNull(in, "missing music resource " + t.resourcePath());
            }
        }
    }

    @Test
    void defaultsAreAnyTimeAnyBiomeEitherLevel() throws IOException {
        MusicCue cue = parse("{\"cues\":[{\"id\":\"x\",\"track\":\"a/X.ogg\"}]}").cues().getFirst();
        assertEquals(MusicCue.Time.ANY, cue.time());
        assertTrue(cue.biomes().isEmpty());
        assertNull(cue.surface());
        assertEquals("/songs/a/X.ogg", cue.track().resourcePath());
    }

    @Test
    void namesAreCaseInsensitive() throws IOException {
        MusicCue cue = parse("{\"cues\":[{\"id\":\"x\",\"track\":\"X.ogg\",\"time\":\"Night\",\"biomes\":[\"shade\"]}]}")
                .cues().getFirst();
        assertEquals(MusicCue.Time.NIGHT, cue.time());
        assertEquals(Set.of(BiomeType.SHADE), cue.biomes());
    }

    @Test
    void rejectsMistakes() {
        assertThrows(IOException.class, () -> parse("{\"cues\":[{\"id\":\"x\",\"track\":\"X.ogg\",\"time\":\"dusk\"}]}"));
        assertThrows(IOException.class, () -> parse("{\"cues\":[{\"id\":\"x\",\"track\":\"X.ogg\",\"biomes\":[\"MORDOR\"]}]}"));
        assertThrows(IOException.class, () -> parse("{\"cues\":[{\"track\":\"X.ogg\"}]}"));
        assertThrows(IOException.class, () -> parse("{\"cues\":[{\"id\":\"x\"}]}"));
        assertThrows(IOException.class, () -> parse(
                "{\"cues\":[{\"id\":\"x\",\"track\":\"X.ogg\"},{\"id\":\"x\",\"track\":\"Y.ogg\"}]}"));
        assertThrows(IOException.class, () -> parse(
                "{\"playlists\":{\"menu\":[\"a/Song.ogg\",\"b/Song.ogg\"]}}"), "two files, one sound name");
    }

    @Test
    void aTrackMayBeSharedBetweenAPlaylistAndACue() throws IOException {
        MusicCatalog c = parse("{\"playlists\":{\"menu\":[\"S.ogg\"]},\"cues\":[{\"id\":\"x\",\"track\":\"S.ogg\"}]}");
        assertEquals(c.playlist("menu").getFirst(), c.cues().getFirst().track());
    }

    @Test
    void soundNames() {
        assertEquals("music_look_beyond", MusicCatalog.soundName("/songs/menu music/Look Beyond.ogg"));
        assertEquals("music_whispering_woods_lofi_lullaby",
                MusicCatalog.soundName("/songs/menu music/Whispering Woods (Lofi Lullaby).ogg"));
    }
}
