package com.stonebreak.world.save.serialization;

import com.stonebreak.world.save.model.EntityData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytes of the entities a cube keeps in its blob (the {@code "entities"} section of the cube's SBC1 blob, see
 * {@code world/chunk/Cube}): {@code u32 count | count x (u32 len, len bytes of JsonEntitySerializer JSON)}, all
 * big-endian - the per-entity layout the column chunk files used, so {@link EntityData} + {@link EntitySerializer}
 * stay the one entity record. Offered by 3B-S for 3B-E's save hook; positions inside are doubles (Phase 4).
 */
public final class EntitySectionCodec {

    /** Name of the cube blob section holding the cube's persistent entities. */
    public static final String SECTION = "entities";

    /** Upper bound of one record, so a damaged length never allocates gigabytes. */
    private static final int MAX_RECORD_BYTES = 1 << 20;

    private EntitySectionCodec() {
    }

    /** The section bytes for {@code entities}; null for none (= remove the section). */
    public static byte[] encode(List<EntityData> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        JsonEntitySerializer serializer = new JsonEntitySerializer();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + entities.size() * 256);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(entities.size());
            for (EntityData entity : entities) {
                byte[] payload = serializer.serialize(entity);
                out.writeInt(payload.length);
                out.write(payload);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The entities of a section. A damaged section throws {@link IOException} (callers log and drop it -
     * never let one bad cube stop a world from loading); null/empty bytes = no entities.
     */
    public static List<EntityData> decode(byte[] section) throws IOException {
        if (section == null || section.length == 0) {
            return List.of();
        }
        JsonEntitySerializer serializer = new JsonEntitySerializer();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(section))) {
            int count = in.readInt();
            if (count < 0 || count > section.length / 4) {
                throw new IOException("Bad entity count " + count);
            }
            List<EntityData> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length < 0 || length > MAX_RECORD_BYTES) {
                    throw new IOException("Bad entity record length " + length);
                }
                byte[] payload = in.readNBytes(length);
                if (payload.length != length) {
                    throw new IOException("Truncated entity record: expected " + length + " bytes");
                }
                try {
                    entities.add(serializer.deserialize(payload));
                } catch (RuntimeException e) {
                    throw new IOException("Unreadable entity record " + i + ": " + e.getMessage(), e);
                }
            }
            return entities;
        }
    }
}
