package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: intent to launch a projectile / place an ability entity. The SERVER
 * spawns and simulates the authoritative entity (its EntityManager listener replicates it
 * to every client, originator included — no client-local spawn, so everyone sees it).
 *
 * <p>{@code (x,y,z)} is the spawn position, {@code (vx,vy,vz)} the direction or velocity
 * depending on kind, and {@code params} kind-specific extras (validated + clamped
 * server-side):
 * <ul>
 *   <li>{@link #KIND_ARROW}: v = full velocity (speed encodes bow draw); no params</li>
 *   <li>{@link #KIND_FIRE_BOLT}: v = direction; no params</li>
 *   <li>{@link #KIND_NULL_SPIKE}: v = direction; params = [damagePerHit, spellmarkDuration, pierce(0/1), burstDamage]</li>
 *   <li>{@link #KIND_LEYLINE_BREACH}: (x,y,z) = zone center, v unused; params = [radius, pullForce, pulseDamage, duration, overloaded(0/1)]</li>
 *   <li>{@link #KIND_CALTROP}: (x,y,z) = ground pos, v unused; params = [duration]</li>
 * </ul>
 */
public record ProjectileSpawnC2S(byte kind, float x, float y, float z,
                                 float vx, float vy, float vz, float[] params) implements Packet {

    public static final byte KIND_ARROW = 1;
    public static final byte KIND_FIRE_BOLT = 2;
    public static final byte KIND_NULL_SPIKE = 3;
    public static final byte KIND_LEYLINE_BREACH = 4;
    public static final byte KIND_CALTROP = 5;

    /** Params bound (largest kind uses 5). */
    public static final int MAX_PARAMS = 8;

    public static final PacketCodec<ProjectileSpawnC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ProjectileSpawnC2S p) {
            out.writeByte(p.kind());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.vx());
            out.writeFloat(p.vy());
            out.writeFloat(p.vz());
            float[] params = p.params();
            ByteBufIO.writeVarInt(out, params.length);
            for (float v : params) {
                out.writeFloat(v);
            }
        }

        @Override
        public ProjectileSpawnC2S decode(ByteBuf in) {
            byte kind = in.readByte();
            float x = in.readFloat();
            float y = in.readFloat();
            float z = in.readFloat();
            float vx = in.readFloat();
            float vy = in.readFloat();
            float vz = in.readFloat();
            int n = ByteBufIO.readVarInt(in);
            if (n < 0 || n > MAX_PARAMS) {
                throw new IllegalArgumentException("Invalid projectile param count: " + n);
            }
            float[] params = new float[n];
            for (int i = 0; i < n; i++) {
                params[i] = in.readFloat();
            }
            return new ProjectileSpawnC2S(kind, x, y, z, vx, vy, vz, params);
        }
    };
}
