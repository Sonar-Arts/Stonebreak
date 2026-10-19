// Translucent surfaces drawn AFTER the clouds were composited (ice, glass,
// water). The framebuffer already holds cloud + T * scene, where the half-res
// cloud buffer carries the premultiplied cloud light (rgb) and its
// transmittance T (a). A surface blended over that at its own alpha would paint
// over cloud lying in front of it; thinning its alpha by T instead dimmed the
// cloud too and washed the surface out. Writing
//     color' = T * color + cloud
// at the surface's UNCHANGED alpha gives exactly
//     cloud + T * (alpha * color + (1 - alpha) * scene)
// — the surface composited behind the cloud. Set by CloudsInFront (Java).
uniform sampler2D u_cloudBuffer;
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
    vec4 cloud = texture(u_cloudBuffer, (gl_FragCoord.xy - u_cloudViewport.xy) / u_cloudViewport.zw);
    return color * cloud.a + cloud.rgb;
}
