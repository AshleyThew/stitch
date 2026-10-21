// Seraph (gold): polished plate with a pair of great golden wings. A chestplate has no wings to give,
// so the stitch makes them from the two side faces of its body: each face's back edge becomes a wing's
// root behind the shoulder blades, its front edge swings out into the wing tip, and the fragment
// stitch draws the feathers. The wings beat slowly; in a screen (the inventory) they fold away.
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

// a player model pixel, in blocks, and the chestplate's depth (its body box, one pixel out each side)
const float PIXEL = 0.9375 / 16.0;
const float DEPTH = 6.0 * PIXEL;
const float SPAN = 1.6;

void stitch_vertex(inout StitchVertex v) {
    bool leggings;
    on = look(leggings) == 4 ? 1 : 0;
    wing = vec3(0.0);
    if (on == 0) {
        return;
    }
    float t = stitch_game_time * 1200.0;
    vec3 up = stitch_up();
    vec3 n = normalize(v.normal);
    rim = pow(1.0 - abs(dot(n, normalize(-v.position))), 3.0);
    if (leggings || stitch_in_screen() || v.corner < 0) {
        return;
    }
    // the body box's side faces: the right one spans u 16-20, the left 28-32, each corner telling which
    // end of its face it is (corners 0 and 3 are the face's high u)
    vec2 p = v.uv * vec2(64.0, 32.0);
    float x = floor(p.x + 0.5);
    bool high = v.corner == 0 || v.corner == 3;
    bool right = (x == 16.0 && !high) || (x == 20.0 && high);
    bool left = (x == 28.0 && !high) || (x == 32.0 && high);
    if (!(right || left) || p.y < 19.5) {
        return;
    }
    bool tip = right ? x == 20.0 : x == 28.0;
    bool top = p.y < 20.5;
    // behind the wearer: across from the side face, which way round by the side
    vec3 back = normalize(cross(up, n)) * stitch_handedness() * (left ? 1.0 : -1.0);
    // the beat: tips sweep back and forth and rise and fall
    float beat = sin(t * 2.4);
    float sweep = 0.3 + 0.3 * beat;
    vec3 reach = normalize(n * cos(sweep) + back * sin(sweep));
    if (tip) {
        // the front edge goes to the back first, then out to the tip
        v.position += back * (DEPTH + 0.06) + reach * SPAN + up * ((top ? 1.15 : -0.2) + 0.3 * sin(t * 2.4 + 0.8));
    } else {
        v.position += back * 0.06 + up * (top ? 0.08 : 0.0);
    }
    wing = vec3(tip ? 1.0 : 0.0, top ? 1.0 : 0.0, 1.0);
}
