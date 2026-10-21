// Leggings whose texture is marked (its top right texel opaque magenta) flare out at the foot, like a
// skirt. Every other armour texture is left as it is: the armor target covers all worn armour.

bool marked() {
    ivec2 size = stitch_texture_size();
    vec4 mark = stitch_texel_fetch(ivec2(size.x - 1, 0));
    return mark.r > 0.99 && mark.g < 0.01 && mark.b > 0.99 && mark.a > 0.99;
}

void stitch_vertex(inout StitchVertex v) {
    if (!marked()) {
        return;
    }
    // where the vertex samples the 64x32 armour layout: the legs' faces are left of u 16, and the
    // bottom edge of their sides is at v 32
    vec2 p = v.uv * vec2(64.0, 32.0);
    if (p.x < 16.0 && p.y > 31.5) {
        // out from the leg, two model pixels
        v.position += normalize(v.normal) * (2.0 / 16.0);
    }
}
