package com.stonebreak.gameplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.gameplay.MultipartData.Box;
import com.stonebreak.gameplay.MultipartData.Condition;
import com.stonebreak.gameplay.MultipartData.Part;
import com.stonebreak.gameplay.MultipartData.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code connectTags} and {@code multipart} survive the codec, and bad rows degrade, not fail. */
class SboGameplayMultipartRoundTripTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void connectTagsAndMultipartRoundTrip() {
        MultipartData table = new MultipartData(List.of(
                new Part("Post", Map.of(), List.of(new Box(0.375f, 0f, 0.375f, 0.625f, 1.5f, 0.625f))),
                new Part("ArmNorth", Map.of(Side.NORTH, Condition.parse("tag:stonebreak:fence"),
                        Side.UP, Condition.parse("!full_cube"), Side.EAST, Condition.parse("!same")), List.of())));
        SboGameplay data = SboGameplay.NONE.withConnectTags(List.of(" stonebreak:fence ", "stonebreak:fence"))
                .withMultipart(table);
        assertEquals(List.of("stonebreak:fence"), data.connectTags(), "stripped and de-duplicated");

        JsonNode json = SboGameplayCodec.INSTANCE.encode(data);
        assertEquals("tag:stonebreak:fence", json.path("multipart").get(1).path("when").path("north").asText());
        assertEquals(data, SboGameplayCodec.INSTANCE.decode(json));
    }

    @Test
    void anInvalidPartIsSkippedAndAnEmptyTableIsAbsent() throws Exception {
        JsonNode root = JSON.readTree("""
                {"multipart": [
                  {"state": "Good", "when": {"up": "full_cube"}},
                  {"state": "BadSide", "when": {"sideways": "full_cube"}},
                  {"state": "BadBox", "boxes": [[0, 0, 0, 2, 1, 1]]}
                ]}""");
        MultipartData read = SboGameplayCodec.readMultipart(root);
        assertEquals(1, read.parts().size());
        assertEquals("Good", read.parts().getFirst().state());

        assertNull(SboGameplayCodec.readMultipart(JSON.readTree("{\"multipart\": [{\"when\": {}}]}")));
        assertTrue(SboGameplay.NONE.withMultipart(new MultipartData(List.of())).multipart() == null);
    }

    @Test
    void conditionsParseAndEncodeSymmetrically() {
        for (String text : List.of("full_cube", "!full_cube", "same", "!same", "tag:a:b", "!tag:a:b")) {
            assertEquals(text, Condition.parse(text).encode());
        }
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("solid"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("tag:"));
    }
}
