// Magma (netherite): pure red texels are cracks, filled with lava that flows downward; basalt next to a
// crack glows with its heat.
float hash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x), mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x), mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * noise(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        amp *= 0.5;
    }
    return sum;
}

bool crack(ivec2 p) {
    vec4 c = stitch_texel_fetch(p);
    return c.a > 0.5 && c.r > 0.99 && c.g < 0.01 && c.b < 0.01;
}

vec3 lava(vec2 uv, float t) {
    float n = fbm(vec3(uv * vec2(18.0, 9.0) + vec2(0.0, t * 0.6), t * 0.15));
    vec3 c = mix(vec3(0.55, 0.04, 0.0), vec3(1.0, 0.45, 0.05), smoothstep(0.3, 0.75, n));
    return mix(c, vec3(1.0, 0.92, 0.55), smoothstep(0.7, 0.9, n)) * 1.5;
}

vec4 stitch_texel(StitchFragment f, vec4 texel) {
    if (on == 0 || texel.a < 0.5) {
        return texel;
    }
    float t = stitch_game_time * 1200.0;
    ivec2 size = stitch_texture_size();
    ivec2 p = ivec2(f.uv * vec2(size));
    if (crack(p)) {
        return vec4(lava(f.uv, t), 1.0);
    }
    // embers: how close the nearest crack is
    float heat = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            if (crack(p + ivec2(x, y))) {
                heat = max(heat, 1.0 - length(vec2(x, y)) / 3.0);
            }
        }
    }
    float flicker = 0.75 + 0.25 * noise(vec3(f.uv * 40.0, t * 3.0));
    return vec4(texel.rgb + vec3(0.9, 0.25, 0.02) * heat * heat * flicker, texel.a);
}
