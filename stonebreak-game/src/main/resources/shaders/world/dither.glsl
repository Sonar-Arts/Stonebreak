// Triangular-PDF dither for 8-bit output. A smooth dark gradient (the night
// sky, a fog veil over it) changes by less than one 8-bit step across many
// pixels, so rounding draws it as visible bands. Adding noise before the
// rounding trades the bands for grain the eye does not see. Two uniform
// hashes summed make a triangular distribution on [-1, 1]: unlike plain
// uniform noise its error variance does not depend on the signal, so the
// grain is equally fine in every tone. One step either way is 1/255.
// sky.frag (engine) carries its own copy, since it cannot include this.

// Dave Hoskins' sine-free hash: uniform on [0, 1), no visible pattern.
float ditherHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Triangular noise on [-1/255, 1/255] for this pixel.
float ditherTpdf(vec2 fragCoord) {
    return (ditherHash(fragCoord) + ditherHash(fragCoord + vec2(57.31, 113.97)) - 1.0) / 255.0;
}
