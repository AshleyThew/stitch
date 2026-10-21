// Glitch (iron): a suit that won't hold still. Every so often thin slices of it tear sideways, its
// colours split and its circuits flare.
// The armour texture's mark: its top right texel opaque magenta, green carrying the look (in
// sixteenths) and whether this is the leggings layer (eight more). Anything unmarked is left alone.
int look(out bool leggings) {
    ivec2 size = stitch_texture_size();
    vec4 m = stitch_texel_fetch(ivec2(size.x - 1, 0));
    leggings = false;
    if (m.r < 0.99 || m.b < 0.99 || m.a < 0.99) {
        return 0;
    }
    int g = int(m.g * 255.0 + 0.5);
    leggings = (g - (g / 16) * 16) >= 8;
    return g / 16;
}
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

void stitch_vertex(inout StitchVertex v) {
    bool leggings;
    on = look(leggings) == 3 ? 1 : 0;
    torn = 0.0;
    if (on == 0) {
        return;
    }
    float t = stitch_game_time * 1200.0;
    float frame = floor(t * 12.0);
    // a burst of glitching some frames, not all
    if (hash(vec3(frame, 1.0, 7.0)) < 0.55) {
        return;
    }
    vec3 up = stitch_up();
    // slices by height, by position alone, so a slice moves whole
    float slice = floor(dot(v.position, up) * 24.0);
    if (hash(vec3(slice, frame, 3.0)) > 0.7) {
        vec3 across = normalize(cross(up, vec3(0.0, 0.0, 1.0)) + cross(up, vec3(1.0, 0.0, 0.0)) * 0.3);
        v.position += across * (hash(vec3(slice, frame, 9.0)) - 0.5) * 0.22;
        torn = 1.0;
    }
}
