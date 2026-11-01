package com.openmason.engine.format.ext;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The data an SBO/SBE manifest carries for others (#383): namespaced {@code extensions}
 * ({@code "stonebreak:gameplay": {...}}) and the top-level fields older files wrote that the
 * format no longer owns. Neither is interpreted by the engine. A value read from a file keeps its
 * exact text and is written back unchanged; a value set through {@link #with} is written as JSON.
 *
 * <p>Immutable. Equality compares the JSON values (not their formatting). Typed reads through a
 * {@link FormatExtensionCodec} are decoded once and cached.
 */
public final class FormatExtensions {

    private static final Logger logger = LoggerFactory.getLogger(FormatExtensions.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** No extensions, no foreign fields. */
    public static final FormatExtensions EMPTY = new FormatExtensions(new LinkedHashMap<>(), new LinkedHashMap<>());

    /** One JSON value: its original text (when read from a file) and/or its tree. */
    private static final class Value {
        private final String raw;
        private volatile JsonNode tree;
        private volatile String canonical;

        Value(String raw, JsonNode tree) {
            this.raw = raw;
            this.tree = tree;
        }

        JsonNode tree() {
            JsonNode t = tree;
            if (t == null) {
                try {
                    t = JSON.readTree(raw);
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException("unreadable extension JSON", e);
                }
                tree = t;
            }
            return t;
        }

        String canonical() {
            String c = canonical;
            if (c == null) {
                try {
                    c = JSON.writeValueAsString(tree());
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                canonical = c;
            }
            return c;
        }

        void write(JsonGenerator g) throws IOException {
            if (raw != null) {
                g.writeRawValue(raw);
            } else {
                g.writeTree(tree());
            }
        }
    }

    private final Map<String, Value> extensions;
    private final Map<String, Value> legacy;
    private final Map<FormatExtensionCodec<?>, Optional<?>> decoded = new ConcurrentHashMap<>();

    private FormatExtensions(LinkedHashMap<String, Value> extensions, LinkedHashMap<String, Value> legacy) {
        this.extensions = Collections.unmodifiableMap(extensions);
        this.legacy = Collections.unmodifiableMap(legacy);
    }

    /** From a file: extension values and foreign top-level fields as their exact JSON text. */
    public static FormatExtensions ofRaw(Map<String, String> extensions, Map<String, String> legacyFields) {
        LinkedHashMap<String, Value> ext = new LinkedHashMap<>();
        extensions.forEach((k, v) -> ext.put(k, new Value(Objects.requireNonNull(v), null)));
        LinkedHashMap<String, Value> leg = new LinkedHashMap<>();
        legacyFields.forEach((k, v) -> leg.put(k, new Value(Objects.requireNonNull(v), null)));
        return ext.isEmpty() && leg.isEmpty() ? EMPTY : new FormatExtensions(ext, leg);
    }

    /** True when there are no extensions and no foreign fields. */
    public boolean isEmpty() {
        return extensions.isEmpty() && legacy.isEmpty();
    }

    /** The extension keys, in file order. */
    public Set<String> keys() {
        return extensions.keySet();
    }

    public boolean has(String key) {
        return extensions.containsKey(key);
    }

    /** An extension's JSON value. */
    public Optional<JsonNode> tree(String key) {
        Value v = extensions.get(key);
        return v == null ? Optional.empty() : Optional.of(v.tree().deepCopy());
    }

    /** The foreign top-level fields kept from an older file, in file order. */
    public Set<String> legacyFieldNames() {
        return legacy.keySet();
    }

    /** A foreign top-level field's JSON value. */
    public Optional<JsonNode> legacyField(String name) {
        Value v = legacy.get(name);
        return v == null ? Optional.empty() : Optional.of(v.tree().deepCopy());
    }

    /**
     * The typed extension: the {@code codec.key()} extension when present, else (an older file)
     * an object of the codec's {@linkplain FormatExtensionCodec#legacyFields legacy fields} when any
     * is present, else empty. An unreadable value is logged and read as empty.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(FormatExtensionCodec<T> codec) {
        return (Optional<T>) decoded.computeIfAbsent(codec, c -> decode(codec));
    }

    private <T> Optional<T> decode(FormatExtensionCodec<T> codec) {
        JsonNode source = null;
        Value ext = extensions.get(codec.key());
        if (ext != null) {
            source = ext.tree();
        } else {
            ObjectNode upgraded = null;
            for (String field : codec.legacyFields()) {
                Value v = legacy.get(field);
                if (v != null) {
                    if (upgraded == null) upgraded = JSON.createObjectNode();
                    upgraded.set(field, v.tree().deepCopy());
                }
            }
            source = upgraded;
        }
        if (source == null || source.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(codec.decode(source));
        } catch (RuntimeException e) {
            logger.warn("Ignoring unreadable '{}' extension: {}", codec.key(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * With the {@code codec.key()} extension set to {@code value} (null removes it). Either way the
     * codec's legacy fields are dropped: the data now lives (or not) in the extension.
     */
    public <T> FormatExtensions with(FormatExtensionCodec<T> codec, T value) {
        FormatExtensions out = value == null ? without(codec.key()) : withTree(codec.key(), codec.encode(value));
        return out.withoutLegacyFields(codec.legacyFields());
    }

    /** With the extension {@code key} set to a JSON value. */
    public FormatExtensions withTree(String key, JsonNode value) {
        requireKey(key);
        Objects.requireNonNull(value, "value");
        LinkedHashMap<String, Value> ext = new LinkedHashMap<>(extensions);
        ext.put(key, new Value(null, value.deepCopy()));
        return new FormatExtensions(ext, new LinkedHashMap<>(legacy));
    }

    /** Without the extension {@code key}. */
    public FormatExtensions without(String key) {
        if (!extensions.containsKey(key)) {
            return this;
        }
        LinkedHashMap<String, Value> ext = new LinkedHashMap<>(extensions);
        ext.remove(key);
        return new FormatExtensions(ext, new LinkedHashMap<>(legacy));
    }

    /** Without the named foreign top-level fields. */
    public FormatExtensions withoutLegacyFields(Collection<String> names) {
        if (names.stream().noneMatch(legacy::containsKey)) {
            return this;
        }
        LinkedHashMap<String, Value> leg = new LinkedHashMap<>(legacy);
        names.forEach(leg::remove);
        return new FormatExtensions(new LinkedHashMap<>(extensions), leg);
    }

    /** Writes the foreign top-level fields as members of the object being generated. */
    public void writeLegacyFields(JsonGenerator g) throws IOException {
        for (Map.Entry<String, Value> e : legacy.entrySet()) {
            g.writeFieldName(e.getKey());
            e.getValue().write(g);
        }
    }

    /** Writes the {@code extensions} member (nothing when there are none). */
    public void writeExtensions(JsonGenerator g) throws IOException {
        if (extensions.isEmpty()) {
            return;
        }
        g.writeFieldName(ManifestJson.EXTENSIONS_FIELD);
        g.writeStartObject();
        for (Map.Entry<String, Value> e : extensions.entrySet()) {
            g.writeFieldName(e.getKey());
            e.getValue().write(g);
        }
        g.writeEndObject();
    }

    /**
     * The JSON view (tool dumps, Jackson): {@code {"extensions": {key: value}, "legacyFields":
     * {name: value}}}. Files are written by {@link ManifestJson}, not through this.
     */
    @com.fasterxml.jackson.annotation.JsonValue
    public Map<String, Object> toJson() {
        Map<String, Object> ext = new LinkedHashMap<>();
        extensions.forEach((k, v) -> ext.put(k, v.tree()));
        Map<String, Object> leg = new LinkedHashMap<>();
        legacy.forEach((k, v) -> leg.put(k, v.tree()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("extensions", ext);
        out.put("legacyFields", leg);
        return out;
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("extension key must be <namespace>:<name> in lower case, got " + key);
        }
    }

    private static Map<String, String> canonical(Map<String, Value> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, v.canonical()));
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof FormatExtensions other
                && canonical(extensions).equals(canonical(other.extensions))
                && canonical(legacy).equals(canonical(other.legacy));
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonical(extensions), canonical(legacy));
    }

    @Override
    public String toString() {
        return "FormatExtensions[extensions=" + canonical(extensions) + ", legacyFields=" + canonical(legacy) + "]";
    }
}
