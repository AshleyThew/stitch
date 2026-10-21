// Seraph (gold): a diagonal sweep of light runs over the plates; magenta texels are gems, cut to
// sparkle. On the wings, feathers: a leading edge, coverts, and long flight feathers scalloped below.
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

vec4 feathers(vec2 w, float t) {
    // w: x from the root (0) to the tip (1), y from the bottom edge (0) to the top (1)
    // the leading edge arches up from the root and drops to a point at the tip
    float lead = 1.0 - 0.3 * w.x * w.x - 0.25 * smoothstep(0.75, 1.0, w.x);
    float count = 7.0;
    float fx = fract(w.x * count);
    // flight feathers hang lowest at the root, each with a rounded end and a notch between
    float tipOf = 1.0 - sqrt(max(0.0, 1.0 - pow(fx * 2.0 - 1.0, 2.0)));
    float hang = mix(0.0, 0.55, pow(w.x, 1.4)) + 0.22 * tipOf;
    if (w.y > lead || w.y < hang) {
        return vec4(0.0);
    }
    float depth = (w.y - hang) / max(0.01, lead - hang);
    vec3 c = mix(vec3(0.95, 0.68, 0.22), vec3(1.0, 0.97, 0.86), clamp(w.x * 0.6 + depth * 0.5, 0.0, 1.0));
    // every other feather a shade darker, a shaft down each, coverts in rows along the top
    c *= mod(floor(w.x * count), 2.0) < 1.0 ? 1.0 : 0.9;
    c *= 1.0 - 0.4 * smoothstep(0.06, 0.0, abs(fx - 0.5)) * step(depth, 0.65);
    if (depth > 0.65) {
        float row = fract(w.x * count * 2.5 + depth * 4.0);
        c *= 0.82 + 0.18 * smoothstep(0.3, 0.7, row);
    }
    // a glow along the edges, and the light sweeping out along the wing
    float edge = smoothstep(0.06, 0.0, min(lead - w.y, w.y - hang));
    float sweep = fract(w.x * 0.9 - t * 0.35);
    c += vec3(1.0, 0.9, 0.6) * (edge * 0.5 + smoothstep(0.0, 0.05, sweep) * smoothstep(0.15, 0.05, sweep) * 0.6);
    return vec4(c, 1.0);
}

vec4 stitch_texel(StitchFragment f, vec4 texel) {
    if (on == 0) {
        return texel;
    }
    float t = stitch_game_time * 1200.0;
    if (wing.z > 0.5) {
        return feathers(wing.xy, t);
    }
    if (texel.a < 0.5) {
        return texel;
    }
    if (texel.r > 0.99 && texel.g < 0.01 && texel.b > 0.99) {
        float sparkle = pow(max(0.0, sin(t * 5.0 + hash(vec3(floor(f.uv * 64.0), 1.0)) * 30.0)), 12.0);
        return vec4(mix(vec3(0.75, 0.05, 0.25), vec3(1.0, 0.75, 0.9), sparkle) * (1.0 + sparkle), 1.0);
    }
    float sweep = fract((f.uv.x + f.uv.y * 0.6) * 0.8 - t * 0.35);
    float shine = smoothstep(0.0, 0.04, sweep) * smoothstep(0.12, 0.04, sweep);
    vec3 gold = texel.rgb * (1.0 + 0.35 * rim) + vec3(1.0, 0.9, 0.6) * shine * 0.9;
    return vec4(gold, texel.a);
}
