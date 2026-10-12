package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson JSON ⇄ {@link UiValue}: op arguments arrive as Jackson trees (MCP arguments, Python
 * {@code json.dumps}), the document stores {@link UiValue}s. Both directions are lossless for
 * every JSON value the format accepts, so an op batch and the archive it produces agree.
 */
public final class UiJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private UiJson() {
    }

    /** The {@link UiValue} of a JSON value; JSON {@code null} is {@link UiValue#NULL}. */
    public static UiValue value(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return UiValue.NULL;
        }
        if (n.isTextual()) {
            return UiValue.of(n.asText());
        }
        if (n.isBoolean()) {
            return UiValue.of(n.asBoolean());
        }
        if (n.isNumber()) {
            return UiValue.of(n.doubleValue());
        }
        if (n.isArray()) {
            List<UiValue> items = new ArrayList<>(n.size());
            n.forEach(i -> items.add(value(i)));
            return new UiValue.Arr(items);
        }
        if (n.isObject()) {
            Map<String, UiValue> fields = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                fields.put(e.getKey(), value(e.getValue()));
            }
            return UiValue.Obj.sorted(fields);
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + n.getNodeType());
    }

    /** A JSON object of values; {@code null} members map to {@code null} (= clear) in the result. */
    public static Map<String, UiValue> nullableMap(JsonNode obj) {
        Map<String, UiValue> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            out.put(e.getKey(), v == null || v.isNull() ? null : value(v));
        }
        return out;
    }

    /** The JSON of a {@link UiValue} (whole numbers stay integral). */
    public static JsonNode json(UiValue v) {
        return switch (v) {
            case null -> NODES.nullNode();
            case UiValue.Null n -> NODES.nullNode();
            case UiValue.Bool b -> NODES.booleanNode(b.value());
            case UiValue.Num num -> num.isIntegral() ? NODES.numberNode((long) num.value())
                : NODES.numberNode(num.value());
            case UiValue.Str s -> NODES.textNode(s.value());
            case UiValue.Arr a -> {
                ArrayNode arr = NODES.arrayNode();
                a.items().forEach(i -> arr.add(json(i)));
                yield arr;
            }
            case UiValue.Obj o -> json(o.fields());
        };
    }

    public static ObjectNode json(Map<String, UiValue> map) {
        ObjectNode obj = NODES.objectNode();
        map.forEach((k, val) -> obj.set(k, json(val)));
        return obj;
    }

    /** Parses JSON text into a tree (op batches given as strings). */
    public static JsonNode parse(ObjectMapper mapper, String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new UiOpException(-1, "Op batch is not valid JSON: " + e.getMessage(),
                "expected {\"ops\":[{\"op\":\"create\",\"type\":\"Label\",...}]}");
        }
    }
}
