// Astral (diamond): black panels show the sky, a starfield in three depths over a slow nebula.
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

vec3 stars(vec3 d, float scale, float t) {
    vec3 cell = floor(d * scale);
    float h = hash(cell);
    if (h < 0.9) {
        return vec3(0.0);
    }
    vec3 centre = (cell + 0.5 + (vec3(hash(cell + 1.3), hash(cell + 2.7), hash(cell + 5.1)) - 0.5) * 0.6) / scale;
    float dist = length(d - centre) * scale;
    float twinkle = 0.6 + 0.4 * sin(t * (2.0 + 4.0 * h) + h * 40.0);
    vec3 tint = mix(vec3(0.7, 0.8, 1.0), vec3(1.0, 0.85, 0.6), hash(cell + 9.0));
    return tint * smoothstep(0.45, 0.0, dist) * twinkle * 2.0;
}

vec4 stitch_texel(StitchFragment f, vec4 texel) {
    if (on == 0 || texel.a < 0.5) {
        return texel;
    }
    float t = stitch_game_time * 1200.0;
    if (max(texel.r, max(texel.g, texel.b)) < 0.02) {
        vec3 d = normalize(sky);
        // the panels are small, so the sky through them is fine-grained: a few degrees hold a nebula
        float n = fbm(d * 30.0 + vec3(0.0, 0.0, t * 0.05));
        vec3 nebula = mix(vec3(0.02, 0.01, 0.08), vec3(0.45, 0.1, 0.6), smoothstep(0.3, 0.75, n));
        nebula = mix(nebula, vec3(0.05, 0.45, 0.7), smoothstep(0.5, 0.85, fbm(d * 55.0 - vec3(t * 0.04))));
        vec3 sum = nebula + stars(d, 300.0, t) + stars(d, 600.0, t) * 0.8 + stars(d, 1100.0, t) * 0.6;
        return vec4(sum, 1.0);
    }
    // the trim catches light at its edges
    return vec4(texel.rgb + rim * vec3(0.25, 0.55, 0.9), texel.a);
}
