// Astral (diamond): every panel is a window onto a starfield that stays put in the world as you walk
// around it, the trim lit at its edges, and each piece floating free of the body on its own swell.
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
    on = look(leggings) == 1 ? 1 : 0;
    if (on == 0) {
        return;
    }
    float t = stitch_game_time * 1200.0;
    vec2 p = v.uv * vec2(64.0, 32.0);
    // which piece: the head, a leg, an arm or the body, each rising and falling on its own phase
    bool head = p.y < 16.0;
    bool leg = !head && p.x < 16.0;
    bool arm = !head && p.x >= 40.0;
    float phase = head ? 0.0 : (leg ? 2.1 : (arm ? 4.2 : 1.0));
    float lift = head ? 0.09 : (leggings || leg ? 0.025 : 0.05);
    if (!stitch_in_screen()) {
        v.position += stitch_up() * lift * (0.6 + 0.4 * sin(t * 1.7 + phase));
    }
    // the direction to the eye: the sky is looked up along it
    vec3 eye = normalize(-v.position);
    sky = -eye;
    rim = pow(1.0 - abs(dot(normalize(v.normal), eye)), 2.0);
}
