package com.stonebreak.world.weather;

import com.stonebreak.world.generation.TerrainGenerator;

import java.util.List;

/**
 * The client's view of the weather: the shared {@link CloudField} for this
 * world, the synced {@link WeatherState}, the local weather around the camera
 * ({@link WeatherMap}) and the terrain the clouds meet ({@link TerrainRelief}).
 * Weather changes ease in over the map's transition time instead of popping,
 * so {@code /weather storm} rolls clouds in rather than switching them.
 */
public final class ClientWeather implements AutoCloseable {

    private final CloudField field;
    private final LightningScheduler lightning;
    private final WeatherState state;
    private final WeatherMap map = new WeatherMap();
    private TerrainRelief relief;
    private TerrainGenerator reliefSource;
    private double focusX, focusZ;
    private boolean focused;

    public ClientWeather(long worldSeed, double weatherSeconds, WeatherMode mode) {
        this(worldSeed, weatherSeconds, mode, List.of());
    }

    public ClientWeather(long worldSeed, double weatherSeconds, WeatherMode mode, List<WeatherZone> zones) {
        this.field = new CloudField(worldSeed);
        this.lightning = new LightningScheduler(worldSeed);
        this.state = new WeatherState(weatherSeconds, mode, zones);
        map.snap(field, state, 0, 0);
    }

    public void update(float deltaSeconds) {
        state.advance(deltaSeconds);
        map.update(field, state, focusX, focusZ, deltaSeconds);
    }

    /**
     * Centres the local weather and terrain on the camera. {@code terrain} is
     * the world's generator (a different one restarts the relief), or null to
     * ignore terrain.
     */
    public void focus(double worldX, double worldZ, TerrainGenerator terrain) {
        if (terrain != reliefSource) {
            if (relief != null) relief.close();
            relief = terrain != null ? new TerrainRelief(terrain::sampleReliefHeights) : null;
            reliefSource = terrain;
        }
        boolean jumped = !focused || Math.abs(worldX - focusX) + Math.abs(worldZ - focusZ) > WeatherMap.PERIOD_BLOCKS / 4.0;
        focusX = worldX;
        focusZ = worldZ;
        focused = true;
        if (jumped) map.snap(field, state, worldX, worldZ);
        if (relief != null) relief.focus(worldX, worldZ);
    }

    /** Applies an authoritative sample from the server. */
    public void sync(double serverSeconds, WeatherMode mode, List<WeatherZone> zones) {
        state.nudgeTo(serverSeconds);
        state.setMode(mode);
        state.setZones(zones);
    }

    /** The weather (bias) at a world column, terrain aside. */
    public WeatherBias biasAt(double worldX, double worldZ) {
        return map.sample(worldX, worldZ);
    }

    /** The relief under a world column; flat where none is known. */
    public TerrainRelief.Sample reliefAt(double worldX, double worldZ) {
        return relief != null ? relief.sample(worldX, worldZ) : TerrainRelief.Sample.FLAT;
    }

    public CloudColumn columnAt(double worldX, double worldZ) {
        return columnAt(worldX, worldZ, state.seconds());
    }

    /**
     * The cloud over a world position at {@code weatherSeconds} as rain and
     * lightning see it: smoothly filtered, under the local weather, piled up
     * and steered around the terrain as {@link TerrainFlow} says, and none
     * inside the ground. The same rule the clouds are drawn with
     * ({@code cloudColumnAt} in cloud_field.glsl).
     */
    public CloudColumn columnAt(double worldX, double worldZ, double weatherSeconds) {
        TerrainRelief.Sample r = reliefAt(worldX, worldZ);
        WeatherBias bias = biasAt(worldX, worldZ).plus(TerrainFlow.pileup(r), 0f);
        double cx = CloudField.cloudX(worldX, weatherSeconds), cz = CloudField.cloudZ(worldZ, weatherSeconds);
        CloudField.Channels ch = field.sample(cx, cz);
        TerrainFlow.Flow flow = TerrainFlow.around(r, CloudField.mass(ch, bias));
        if (flow != TerrainFlow.Flow.NONE) {
            ch = field.sample(cx + flow.shiftX(), cz + flow.shiftZ());
            bias = bias.plus(flow.coverage(), 0f);
        }
        CloudColumn col = CloudField.resolve(ch, bias);
        if (col.isClear() || r.height() <= col.baseY()) return col;
        // The ground cuts off the bottom of the column; what is above it remains.
        int base = (int) Math.ceil(r.height() / CloudField.VOXEL) * CloudField.VOXEL;
        return base >= col.topY() ? CloudColumn.CLEAR
                : new CloudColumn(col.density(), col.type(), base, col.topY(), col.build(), col.wet());
    }

    /** Rain or snow intensity falling at world column ({@code worldX}, {@code worldZ}). */
    public float precipitationAt(double worldX, double worldZ) {
        return columnAt(worldX, worldZ).precipitation();
    }

    /** Lightning with {@code from <= seconds < to} within {@code range} blocks (see {@link LightningScheduler}). */
    public List<LightningScheduler.Strike> strikesBetween(double from, double to,
                                                          double x, double z, double range) {
        return lightning.strikesBetween(from, to, x, z, range,
                this::columnAt);
    }

    public CloudField field() {
        return field;
    }

    public WeatherMap map() {
        return map;
    }

    /** The relief the clouds meet, or null when terrain is ignored. */
    public TerrainRelief relief() {
        return relief;
    }

    public double seconds() {
        return state.seconds();
    }

    public WeatherMode mode() {
        return state.mode();
    }

    public List<WeatherZone> zones() {
        return state.zones();
    }

    @Override
    public void close() {
        if (relief != null) relief.close();
        relief = null;
    }
}
