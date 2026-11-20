package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.TerrainModelRef;
import com.stonebreak.world.generation.TerrainGeneratorType;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: handshake accepted; carries the assigned player id, world seed, terrain
 * generator, terrain scale, climate layout, height-curve version and terrain model (so a DaedalusTGM-Exp client
 * builds the same terrain: its TGMPipe runs the server world's model, protocol 28), spawn
 * (int64 cell + float offset per axis), and the client's join secret for this server ("" for the in-process host).
 * The client keeps the secret and presents it in its next {@link HandshakeC2S} (issue #335).
 */
public record WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                         com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale,
                         ClimateLayout climateLayout, HeightCurveVersion heightCurve, TerrainModelRef terrainModel,
                         com.stonebreak.world.generation.StandardShapeVersion standardShape,
                         double spawnX, double spawnY, double spawnZ, String clientSecret) implements Packet {

    public WelcomeS2C {
        standardShape = com.stonebreak.world.generation.StandardShapeVersion.orCurrent(standardShape);
        clientSecret = clientSecret == null ? "" : clientSecret;
        terrainScale = terrainScale == null ? com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR : terrainScale;
        climateLayout = ClimateLayout.orDefault(climateLayout);
        heightCurve = HeightCurveVersion.orCurrent(heightCurve);
        terrainModel = TerrainModelRef.orLegacy(terrainModel);
    }

    /** A welcome of a world with the current Standard shape. */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale,
                      ClimateLayout climateLayout, HeightCurveVersion heightCurve, TerrainModelRef terrainModel,
                      double spawnX, double spawnY, double spawnZ, String clientSecret) {
        this(playerId, worldSeed, generatorType, terrainScale, climateLayout, heightCurve, terrainModel, null,
            spawnX, spawnY, spawnZ, clientSecret);
    }

    /** A welcome without a recorded terrain model ({@link TerrainModelRef#LEGACY}). */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale,
                      ClimateLayout climateLayout, HeightCurveVersion heightCurve,
                      double spawnX, double spawnY, double spawnZ, String clientSecret) {
        this(playerId, worldSeed, generatorType, terrainScale, climateLayout, heightCurve, null, spawnX, spawnY, spawnZ,
            clientSecret);
    }

    /** A current-curve world's welcome with the climate {@code climateLayout}. */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale,
                      ClimateLayout climateLayout,
                      double spawnX, double spawnY, double spawnZ, String clientSecret) {
        this(playerId, worldSeed, generatorType, terrainScale, climateLayout, null, spawnX, spawnY, spawnZ,
            clientSecret);
    }

    /** A belts world's welcome at {@code terrainScale}. */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale,
                      double spawnX, double spawnY, double spawnZ, String clientSecret) {
        this(playerId, worldSeed, generatorType, terrainScale, null, null, spawnX, spawnY, spawnZ, clientSecret);
    }

    /** A 1:4 belts world's welcome. */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      double spawnX, double spawnY, double spawnZ, String clientSecret) {
        this(playerId, worldSeed, generatorType, null, null, null, spawnX, spawnY, spawnZ, clientSecret);
    }

    /** A welcome without a secret (the in-process host). */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      double spawnX, double spawnY, double spawnZ) {
        this(playerId, worldSeed, generatorType, spawnX, spawnY, spawnZ, "");
    }

    public static final PacketCodec<WelcomeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WelcomeS2C p) {
            out.writeInt(p.playerId());
            out.writeLong(p.worldSeed());
            out.writeByte(p.generatorType().ordinal());
            out.writeByte(p.terrainScale().ordinal());
            out.writeByte(p.climateLayout().ordinal());
            out.writeByte(p.heightCurve().number());
            ByteBufIO.writeString(out, p.terrainModel().path(), TerrainModelRef.MAX_CHARS);
            ByteBufIO.writeString(out, p.terrainModel().contentId(), TerrainModelRef.MAX_CHARS);
            out.writeByte(p.standardShape().number());
            com.stonebreak.network.packet.NetCoords.writePosition(out, p.spawnX(), p.spawnY(), p.spawnZ());
            ByteBufIO.writeString(out, p.clientSecret(), HandshakeC2S.MAX_SECRET_CHARS);
        }

        @Override
        public WelcomeS2C decode(ByteBuf in) {
            int id = in.readInt();
            long seed = in.readLong();
            int type = in.readUnsignedByte();
            if (type >= TerrainGeneratorType.values().length) {
                throw new IllegalArgumentException("Unknown terrain generator " + type);
            }
            int scale = in.readUnsignedByte();
            var scales = com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.values();
            if (scale >= scales.length) {
                throw new IllegalArgumentException("Unknown terrain scale " + scale);
            }
            int climate = in.readUnsignedByte();
            ClimateLayout[] climates = ClimateLayout.values();
            if (climate >= climates.length) {
                throw new IllegalArgumentException("Unknown climate layout " + climate);
            }
            int curveNumber = in.readUnsignedByte();
            HeightCurveVersion curve = HeightCurveVersion.of(curveNumber);
            if (curve == null) {
                throw new IllegalArgumentException("Unknown height curve " + curveNumber);
            }
            String modelPath = ByteBufIO.readString(in, TerrainModelRef.MAX_CHARS);
            String modelId = ByteBufIO.readString(in, TerrainModelRef.MAX_CHARS);
            TerrainModelRef model = TerrainModelRef.parse(modelPath, modelId);
            int shapeNumber = in.readUnsignedByte();
            var shape = com.stonebreak.world.generation.StandardShapeVersion.of(shapeNumber);
            if (shape == null) {
                throw new IllegalArgumentException("Unknown Standard shape " + shapeNumber);
            }
            double x = com.stonebreak.network.packet.NetCoords.readPosition(in);
            double y = com.stonebreak.network.packet.NetCoords.readPosition(in);
            double z = com.stonebreak.network.packet.NetCoords.readPosition(in);
            return new WelcomeS2C(id, seed, TerrainGeneratorType.values()[type], scales[scale], climates[climate], curve,
                model, shape, x, y, z,
                ByteBufIO.readString(in, HandshakeC2S.MAX_SECRET_CHARS));
        }
    };
}
