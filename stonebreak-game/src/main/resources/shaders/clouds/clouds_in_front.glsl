// Translucent surfaces drawn AFTER the clouds were composited (ice, glass,
// water). The framebuffer already holds cloud + T * scene, where the half-res
// cloud buffer carries the premultiplied cloud light (rgb) and its
// transmittance T (a). A surface blended over that at its own alpha would paint
// over cloud lying in front of it; thinning its alpha by T instead dimmed the
// cloud too and washed the surface out. Writing
//     color' = T * color + cloud
// at the surface's UNCHANGED alpha gives exactly
//     cloud + T * (alpha * color + (1 - alpha) * scene)
// — the surface composited behind the cloud. Only the cloud nearer than the
// surface is in front of it: the buffer holds every cloud along the ray, and
// compositing cloud lying BEHIND a window or lake in front of it drew the cloud
// over the glass and water. The march's cloud distance (clouds_march.frag's
// second output) says which side of the surface the cloud is on. Set by
// CloudsInFront (Java).
uniform sampler2D u_cloudBuffer;
uniform sampler2D u_cloudDistanceBuffer; // NO_CLOUD (-1) / OCCLUDED (-2) where none
uniform bool u_cloudsInFront;       // a cloud buffer is bound for this pass
uniform vec4 u_cloudViewport;       // x, y, width, height of the scene
uniform float u_cloudSlabBottom;
uniform float u_cloudSlabTop;

// eye and pos in the same space (render space).
vec3 behindClouds(vec3 color, vec3 eye, vec3 pos) {
    if (!u_cloudsInFront) return color;
    // The same test clouds_composite.frag makes: a surface the ray reaches
    // before it enters the slab has no cloud in front of it, though the buffer
    // holds the cloud behind it (a window looking at the sky).
    vec3 ray = pos - eye;
    float d = length(ray);
    float dy = ray.y / max(d, 1e-4);
    if (eye.y < u_cloudSlabBottom) {
        if (dy <= 1e-5 || d * dy < u_cloudSlabBottom - eye.y) return color;
    } else if (eye.y > u_cloudSlabTop) {
        if (dy >= -1e-5 || d * dy > u_cloudSlabTop - eye.y) return color;
    }
    vec2 uv = (gl_FragCoord.xy - u_cloudViewport.xy) / u_cloudViewport.zw;
    float cloudDist = texture(u_cloudDistanceBuffer, uv).r;
    if (cloudDist < 0.0) return color;
    // Soft across a few percent of the distance, so a cloud wrapped around the
    // surface fades from in front to behind instead of snapping per pixel.
    float inFront = 1.0 - smoothstep(d * 0.95, d * 1.05, cloudDist);
    vec4 cloud = texture(u_cloudBuffer, uv);
    return mix(color, color * cloud.a + cloud.rgb, inFront);
}
