package com.stonebreak.world.weather;

/**
 * How terrain pushes cloud around, from a {@link TerrainRelief.Sample}. A
 * simple simulation, not a weather model. Mirrored by {@code cloudTerrainFlow},
 * {@code cloudPileup} and {@code cloudTerrainClip} in
 * {@code shaders/clouds/cloud_field.glsl}. Change both together; the shader
 * reads every number here as a uniform.
 *
 * <ul>
 *   <li><b>Pile-up.</b> Wind drives cloud into the windward face of high
 *       ground, where it clumps: more cover there, so clouds grow fuller, but
 *       never past their type's usual height.</li>
 *   <li><b>Around, or over.</b> Cloud beside ground that rises above the
 *       cloud layer ({@link #REFERENCE_Y}) is steered around it. Such a point
 *       looks the cloud map up to {@link #DEFLECT_REACH} blocks uphill and draws
 *       the cloud that would have stood over the mountain, so cloud funnels
 *       through valleys and gaps. Only a cloud mass that has clumped up enough
 *       ({@link #PASS_MASS_LO} to {@link #PASS_MASS_HI}) holds its course and
 *       rides over. The ground trims its underside, and only a tall, massive
 *       cloud still shows above the ridge.
 *       <p>The steering is judged at one altitude for the whole column, so a
 *       cloud moves as one piece instead of each slice of it sliding by a
 *       different amount (that sheared clouds into smears). {@link TerrainRelief}
 *       bakes it per cell and smooths it so it bends the cloud map without
 *       folding it.</p></li>
 *   <li><b>Down to the ground.</b> Cloud reaches the surface (a hill in cloud
 *       is in fog) but never shows inside it: {@link #clip}.</li>
 * </ul>
 */
public final class TerrainFlow {

    /** Half-width of the band in which ground goes from below a cloud's level to blocking it, blocks. */
    public static final float BLOCK_MARGIN = 16f;
    /** How far blocked cloud is steered around a mountain, blocks. */
    public static final float DEFLECT_REACH = 96f;
    /** Coverage bias where cloud squeezes past a mountain. */
    public static final float CONVERGE = 0.3f;
    /** Coverage bias on a windward face at full pile-up. */
    public static final float PILEUP_COVERAGE = 0.6f;
    /**
     * A cloud's mass is how far it has grown toward its type's full height (the
     * dome, 0..1; see {@link CloudField#mass}). Below the low mark it goes
     * around a mountain; past the high mark it goes over.
     */
    public static final float PASS_MASS_LO = 0.4f;
    public static final float PASS_MASS_HI = 0.9f;
    /**
     * Cloud fades out over this many blocks below the relief's surface. The
     * relief is coarse, so this lets cloud reach the real ground everywhere;
     * the terrain itself hides whatever lies below it.
     */
    public static final float CLIP_SINK = 4f;
    /**
     * Altitude steering is judged at, for the clouds, rain and lightning alike:
     * low in the slab, where the rain clouds' bases are and terrain matters most.
     */
    public static final float REFERENCE_Y = 168f;

    /**
     * Steering around the ground at one altitude.
     *
     * @param shiftX   blocks to add to the cloud-map lookup position
     * @param shiftZ   blocks to add to the cloud-map lookup position
     * @param coverage added to the coverage bias (squeezed cloud thickens)
     */
    public record Flow(float shiftX, float shiftZ, float coverage) {
        public static final Flow NONE = new Flow(0f, 0f, 0f);
    }

    private TerrainFlow() {}

    /** Coverage bias from cloud piling up against the windward face here. */
    public static float pileup(TerrainRelief.Sample r) {
        return PILEUP_COVERAGE * r.pileup();
    }

    /**
     * How the ground steers light cloud at {@link #REFERENCE_Y}, from smoothed
     * height {@code smooth} and its slope: the raw field {@link TerrainRelief}
     * smooths and bakes per cell.
     */
    public static Flow steer(float smooth, float gradX, float gradZ) {
        float y = REFERENCE_Y;
        float block = CloudField.smoothstep(y - BLOCK_MARGIN, y + BLOCK_MARGIN, smooth);
        float g = (float) Math.hypot(gradX, gradZ);
        // Height the ground reaches a deflection's length uphill.
        float reach = smooth + g * DEFLECT_REACH;
        float steer = CloudField.smoothstep(y - BLOCK_MARGIN, y + BLOCK_MARGIN, reach) * (1f - block);
        if (steer <= 0f || g <= 1e-4f) return Flow.NONE;
        return new Flow(gradX / g * DEFLECT_REACH * steer, gradZ / g * DEFLECT_REACH * steer, CONVERGE * steer);
    }

    /**
     * How a cloud of {@code mass} (0..1, {@link CloudField#mass}) is steered
     * around the ground here: the baked steering, less as the cloud gets massive.
     * Mirrored by {@code cloudTerrainFlow} in cloud_field.glsl.
     */
    public static Flow around(TerrainRelief.Sample r, float mass) {
        float hold = 1f - CloudField.smoothstep(PASS_MASS_LO, PASS_MASS_HI, mass);
        if (hold <= 0f || r.steer() <= 0f) return Flow.NONE;
        return new Flow(r.shiftX() * hold, r.shiftZ() * hold, CONVERGE * r.steer() * hold);
    }

    /** How much cloud survives at altitude {@code y} over this ground: 0 in it, 1 above it. */
    public static float clip(TerrainRelief.Sample r, float y) {
        return CloudField.smoothstep(r.height() - CLIP_SINK, r.height(), y);
    }
}
