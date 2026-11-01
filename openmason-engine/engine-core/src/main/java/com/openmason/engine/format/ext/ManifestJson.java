package com.openmason.engine.format.ext;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manifest JSON helpers that keep foreign data exact (#383): reading captures the raw text of
 * chosen members so a re-save writes them back byte for byte, and writing appends that raw text
 * after the format's own fields.
 */
public final class ManifestJson {

    /** The member that holds namespaced extensions. */
    public static final String EXTENSIONS_FIELD = "extensions";

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ManifestJson() {
    }

    /**
     * The members of the JSON object {@code objectJson}, each mapped to the exact text of its value
     * (in document order). A value that is not an object at the top yields an empty map.
     */
    public static Map<String, String> rawMembers(String objectJson) {
        Map<String, String> out = new LinkedHashMap<>();
        try (JsonParser p = FACTORY.createParser(objectJson)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return out;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                int start = (int) p.currentTokenLocation().getCharOffset();
                if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    p.skipChildren();
                } else {
                    p.finishToken(); // a string token is read lazily; make the location point past it
                }
                int end = (int) p.currentLocation().getCharOffset();
                out.put(name, objectJson.substring(start, end));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable manifest JSON", e);
        }
        return out;
    }

    /**
     * Splits a manifest into the extensions it carries and the top-level fields the format does
     * not own ({@code coreFields}; JSON {@code null} values are dropped as absent).
     */
    public static FormatExtensions readExtensions(String manifestJson, Set<String> coreFields) {
        Map<String, String> top = rawMembers(manifestJson);
        Map<String, String> extensions = new LinkedHashMap<>();
        Map<String, String> legacy = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : top.entrySet()) {
            String name = e.getKey();
            String raw = e.getValue();
            if (EXTENSIONS_FIELD.equals(name)) {
                extensions.putAll(rawMembers(raw));
            } else if (!coreFields.contains(name) && !"null".equals(raw.trim())) {
                legacy.put(name, raw);
            }
        }
        return FormatExtensions.ofRaw(extensions, legacy);
    }

    /**
     * The manifest text: {@code core}'s fields in order (pretty-printed), then {@code extra}'s
     * foreign top-level fields, then its {@code extensions} object (omitted when empty). Preserved
     * values are written as their original text.
     */
    public static String write(ObjectNode core, FormatExtensions extra) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = PRETTY.getFactory().createGenerator(out)) {
            g.setCodec(PRETTY);
            g.useDefaultPrettyPrinter();
            g.writeStartObject();
            Iterator<Map.Entry<String, JsonNode>> fields = core.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if (EXTENSIONS_FIELD.equals(f.getKey()) || extra.legacyFieldNames().contains(f.getKey())) {
                    continue;
                }
                g.writeFieldName(f.getKey());
                g.writeTree(f.getValue());
            }
            extra.writeLegacyFields(g);
            extra.writeExtensions(g);
            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("could not write manifest JSON", e);
        }
        return out.toString();
    }
}
