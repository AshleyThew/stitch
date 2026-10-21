// Phantom (chainmail): spectral mail that drifts like smoke and fades in and out of the world, burning
// away at its edges, its runes pulsing.
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

void stitch_vertex(inout StitchVertex v) {
    bool leggings;
    on = look(leggings) == 5 ? 1 : 0;
    if (on == 0) {
        return;
    }
    float t = stitch_game_time * 1200.0;
    vec3 up = stitch_up();
    float height = dot(v.position, up);
    // a slow drift, by position alone so the suit stays whole
    vec3 across = normalize(cross(up, vec3(0.0, 0.0, 1.0)) + vec3(0.0001));
    v.position += across * 0.035 * sin(height * 9.0 + t * 2.2) + up * 0.02 * sin(t * 1.3);
}
