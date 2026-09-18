package com.stonebreak.battletest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Authored runtime layout. Spawn coordinates are feet positions in the OMO's coordinate space. */
public record BattleTestArena(
    String name, String model, Spawn playerSpawn, Spawn archonSpawn, List<Box> collisionBoxes) {
    public record Spawn(String id, float x, float y, float z, float yaw, float r, float g, float b) {
        public Vector3f position() {
            return new Vector3f(x, y, z);
        }
        public Vector3f color() {
            return new Vector3f(r, g, b);
        }
    }
    public record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public Box {
            if (!(minX < maxX && minY < maxY && minZ < maxZ))
                throw new IllegalArgumentException("Invalid arena collider");
        }
        public boolean near(Vector3f p, float margin) {
            return p.x + margin >= minX && p.x - margin <= maxX && p.z + margin >= minZ
                && p.z - margin <= maxZ && p.y + margin >= minY && p.y - margin <= maxY;
        }
        public float[] bounds() {
            return new float[] {minX, minY, minZ, maxX, maxY, maxZ};
        }
    }
    public BattleTestArena {
        collisionBoxes = List.copyOf(collisionBoxes);
    }
    public static BattleTestArena load() throws IOException {
        try (var in = BattleTestArena.class.getResourceAsStream("/battletest/arena.json")) {
            if (in == null)
                throw new IOException("Missing battle arena layout");
            JsonNode root = new ObjectMapper().readTree(in);
            List<Box> boxes = new ArrayList<>();
            for (JsonNode b : root.required("collisionBoxes"))
                boxes.add(new Box(b.get(0).floatValue(), b.get(1).floatValue(), b.get(2).floatValue(),
                    b.get(3).floatValue(), b.get(4).floatValue(), b.get(5).floatValue()));
            return new BattleTestArena(root.required("name").asText(), root.required("model").asText(),
                spawn(root.required("playerSpawn")), spawn(root.required("archonSpawn")), boxes);
        }
    }
    private static Spawn spawn(JsonNode n) {
        JsonNode p = n.required("position"), c = n.required("markerColor");
        return new Spawn(n.required("id").asText(), p.get(0).floatValue(), p.get(1).floatValue(),
            p.get(2).floatValue(), n.required("yaw").floatValue(), c.get(0).floatValue(),
            c.get(1).floatValue(), c.get(2).floatValue());
    }
}
