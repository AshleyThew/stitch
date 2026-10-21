// Magma (netherite): basalt plates split by cracks of flowing lava, embers glowing around them and the
// whole suit shimmering in its own heat.
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
    on = look(leggings) == 2 ? 1 : 0;
    if (on == 0) {
        return;
    }
    float t = stitch_game_time * 1200.0;
    // heat haze: a ripple by position alone, so corners that faces share still meet
    float wave = sin(dot(v.position, vec3(23.0, 31.0, 19.0)) + t * 7.0);
    v.position += stitch_up() * 0.006 * wave;
}
