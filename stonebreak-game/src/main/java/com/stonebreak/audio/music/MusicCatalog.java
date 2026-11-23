package com.stonebreak.audio.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.audio.MusicDirector.Track;
import com.stonebreak.world.generation.biomes.BiomeType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * All of Stonebreak's music, read from {@value #RESOURCE}. Data, not code: a new playlist track or
 * biome theme is a JSON entry plus its {@code .ogg}.
 *
 * <pre>{@code
 * { "playlists": { "menu": ["menu music/Look Beyond.ogg", ...], "battle": [...] },
 *   "cues": [ { "id": "the_shade", "track": "biome music/The Shade.ogg",
 *               "time": "night", "biomes": ["SHADE"], "surface": true } ] }
 * }</pre>
 * Track paths are relative to {@value #ROOT}. Playlists follow the engine's {@code MusicDirector}
 * rules (in order from the first track, then rotating; one track loops). A cue is a one-shot theme
 * ({@link MusicCue}): {@code time} = {@code any|day|night} on the world clock (default any),
 * {@code biomes} = biome names (default any), {@code surface} = true/false (default either).
 * A track's sound name is derived from its file name ({@code music_look_beyond}).
 */
public record MusicCatalog(Map<String, List<Track>> playlists, List<MusicCue> cues) {

    public static final String RESOURCE = "/songs/music.json";
    public static final String ROOT = "/songs/";

    public MusicCatalog {
        Map<String, List<Track>> copy = new LinkedHashMap<>();
        playlists.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        playlists = java.util.Collections.unmodifiableMap(copy);
        cues = List.copyOf(cues);
    }

    public static MusicCatalog empty() {
        return new MusicCatalog(Map.of(), List.of());
    }

    /** A playlist's tracks; empty when the catalog has none of that name. */
    public List<Track> playlist(String name) {
        return playlists.getOrDefault(name, List.of());
    }

    /** The shipped catalog ({@value #RESOURCE}). */
    public static MusicCatalog load() throws IOException {
        try (InputStream in = MusicCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException(RESOURCE + " is missing");
            return parse(in);
        }
    }

    public static MusicCatalog parse(InputStream in) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        if (root == null || !root.isObject()) throw new IOException("music catalog: expected a JSON object");
        Map<String, String> pathByName = new HashMap<>();

        Map<String, List<Track>> playlists = new LinkedHashMap<>();
        var fields = root.path("playlists").properties();
        for (var entry : fields) {
            List<Track> tracks = new ArrayList<>();
            for (JsonNode path : entry.getValue()) {
                tracks.add(track(path, "playlist '" + entry.getKey() + "'", pathByName));
            }
            playlists.put(entry.getKey(), tracks);
        }

        List<MusicCue> cues = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode c : root.path("cues")) {
            String id = c.path("id").asText("");
            if (id.isBlank()) throw new IOException("music catalog: a cue has no id");
            if (!ids.add(id)) throw new IOException("music catalog: duplicate cue id '" + id + "'");
            String where = "cue '" + id + "'";
            Track track = track(c.get("track"), where, pathByName);
            MusicCue.Time time = time(c.path("time").asText("any"), where);
            Set<BiomeType> biomes = EnumSet.noneOf(BiomeType.class);
            for (JsonNode b : c.path("biomes")) biomes.add(biome(b.asText(), where));
            JsonNode s = c.get("surface");
            Boolean surface = s == null || s.isNull() ? null : s.asBoolean();
            cues.add(new MusicCue(id, track, time, biomes, surface));
        }
        return new MusicCatalog(playlists, cues);
    }

    /** {@code music_} + the file name lowercased, every non-alphanumeric run as one {@code _}. */
    static String soundName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        if (dot > 0) file = file.substring(0, dot);
        String slug = file.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return "music_" + slug;
    }

    private static Track track(JsonNode node, String where, Map<String, String> pathByName) throws IOException {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IOException("music catalog: " + where + " needs a track path");
        }
        String path = ROOT + node.asText();
        String name = soundName(path);
        String other = pathByName.putIfAbsent(name, path);
        if (other != null && !other.equals(path)) {
            throw new IOException("music catalog: '" + path + "' and '" + other + "' share the sound name " + name);
        }
        return new Track(name, path);
    }

    private static MusicCue.Time time(String text, String where) throws IOException {
        try {
            return MusicCue.Time.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IOException("music catalog: " + where + " has unknown time '" + text + "' (any|day|night)");
        }
    }

    private static BiomeType biome(String text, String where) throws IOException {
        try {
            return BiomeType.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IOException("music catalog: " + where + " has unknown biome '" + text + "'");
        }
    }
}
