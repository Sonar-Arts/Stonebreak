package com.openmason.main.systems.assistant.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseParserTest {

    private final SseParser parser = new SseParser(new ObjectMapper());

    @Test
    void contentDelta() {
        List<StreamDelta> deltas = parser.parse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}");
        assertEquals(1, deltas.size());
        assertEquals("hello", ((StreamDelta.Content) deltas.get(0)).text());
    }

    @Test
    void reasoningDelta() {
        List<StreamDelta> deltas = parser.parse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}");
        assertEquals("thinking", ((StreamDelta.Reasoning) deltas.get(0)).text());
    }

    @Test
    void doneSentinelAndNoise() {
        assertTrue(SseParser.isDone("data: [DONE]"));
        assertTrue(parser.parse(": keep-alive").isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("data: {broken json").isEmpty());
    }

    @Test
    void toolCallFragmentsMergeAcrossFrames() {
        SseParser.ToolCallAccumulator acc = new SseParser.ToolCallAccumulator();
        for (StreamDelta d : parser.parse("data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                "{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"model_summary\"," +
                "\"arguments\":\"{\\\"a\\\":\"}}]}}]}")) {
            if (d instanceof StreamDelta.ToolCallFragment f) {
                acc.accept(f);
            }
        }
        for (StreamDelta d : parser.parse("data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                "{\"index\":0,\"function\":{\"arguments\":\"1}\"}}]}}]}")) {
            if (d instanceof StreamDelta.ToolCallFragment f) {
                acc.accept(f);
            }
        }
        List<SseParser.ToolCallAccumulator.Call> calls = acc.calls();
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).id());
        assertEquals("model_summary", calls.get(0).name());
        assertEquals("{\"a\":1}", calls.get(0).argumentsJson());
    }

    @Test
    void finishAndUsage() {
        List<StreamDelta> deltas = parser.parse(
                "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}," +
                        "\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Usage u
                && u.promptTokens() == 10 && u.completionTokens() == 5));
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Finish f
                && "tool_calls".equals(f.reason())));
    }
}
