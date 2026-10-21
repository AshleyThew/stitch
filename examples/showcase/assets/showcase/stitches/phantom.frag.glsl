// Phantom (chainmail): the mail dissolves and reforms on a slow cycle, a burning cyan edge where it is
// coming apart; blue texels are runes, pulsing.
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

vec4 stitch_texel(StitchFragment f, vec4 texel) {
    if (on == 0 || texel.a < 0.5) {
        return texel;
    }
    float t = stitch_game_time * 1200.0;
    float n = fbm(vec3(f.uv * vec2(24.0, 12.0), t * 0.1));
    // how much of the suit is gone, swinging between almost none and over half
    float gone = 0.25 + 0.3 * sin(t * 0.7);
    if (n < gone) {
        return vec4(texel.rgb, 0.0);
    }
    float edge = smoothstep(gone + 0.06, gone, n);
    vec3 c = mix(texel.rgb, texel.rgb * vec3(0.7, 0.95, 1.1), 0.5);
    if (texel.r < 0.01 && texel.g < 0.01 && texel.b > 0.99) {
        c = vec3(0.3, 0.6, 1.0) * (1.0 + 0.8 * sin(t * 3.0 + f.uv.y * 20.0));
    }
    return vec4(c + vec3(0.2, 0.9, 1.0) * edge * 1.5, texel.a);
}
