package com.stonebreak.world.weather;

/**
 * The client's view of the weather: the shared {@link CloudField} for this
 * world, the synced {@link WeatherState}, and the bias the sky is currently
 * showing. Mode changes ease in over {@link #TRANSITION_SECONDS} instead of
 * popping, so {@code /weather storm} rolls clouds in rather than switching them.
 */
public final class ClientWeather {

    /** Time constant of the ease toward a new weather bias. */
    private static final float TRANSITION_SECONDS = 12f;

    private final CloudField field;
    private final LightningScheduler lightning;
    private final WeatherState state;
    private WeatherBias bias;

    public ClientWeather(long worldSeed, double weatherSeconds, WeatherMode mode) {
        this.field = new CloudField(worldSeed);
        this.lightning = new LightningScheduler(field, worldSeed);
        this.state = new WeatherState(weatherSeconds, mode);
        this.bias = field.bias(mode, weatherSeconds);
    }

    public void update(float deltaSeconds) {
        state.advance(deltaSeconds);
        WeatherBias target = field.bias(state.mode(), state.seconds());
        float t = 1f - (float) Math.exp(-Math.max(0f, deltaSeconds) / TRANSITION_SECONDS);
        bias = bias.lerp(target, t);
    }

    /** Applies an authoritative sample from the server. */
    public void sync(double serverSeconds, WeatherMode mode) {
        state.nudgeTo(serverSeconds);
        state.setMode(mode);
    }

    public CloudColumn columnAt(double worldX, double worldZ) {
        return field.columnAt(worldX, worldZ, state.seconds(), bias);
    }

    /** Rain or snow intensity falling at world column ({@code worldX}, {@code worldZ}). */
    public float precipitationAt(double worldX, double worldZ) {
        return columnAt(worldX, worldZ).precipitation();
    }

    /** Lightning with {@code from <= seconds < to} within {@code range} blocks (see {@link LightningScheduler}). */
    public java.util.List<LightningScheduler.Strike> strikesBetween(double from, double to,
                                                                     double x, double z, double range) {
        return lightning.strikesBetween(from, to, x, z, range, bias);
    }

    public CloudField field() {
        return field;
    }

    public double seconds() {
        return state.seconds();
    }

    public WeatherMode mode() {
        return state.mode();
    }

    public WeatherBias bias() {
        return bias;
    }
}
