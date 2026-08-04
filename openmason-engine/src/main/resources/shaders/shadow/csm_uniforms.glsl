// Cascaded shadow map uniforms. Applied host-side by ShadowUniforms.
// Defaults (all-zero) mean "shadows off" — safe unconfigured.
uniform bool u_shadowsEnabled;
uniform sampler2DArrayShadow u_shadowMap;
uniform mat4 u_lightSpaceMatrices[3];
uniform vec3 u_cascadeSplits;      // far distance of cascades 0/1/2 (view space)
uniform float u_shadowStrength;    // 0..1 max darkening of direct light
uniform float u_shadowTexelWorld[3]; // world size of one texel per cascade
uniform vec3 u_shadowSunDir;       // normalized, toward the sun
uniform int u_shadowPcfRadius;     // PCF radius in texels: 0=hw 2x2, 1=3x3, 2=5x5
