// Glitch (iron): scanlines crawl up the plates, cyan circuits (pure cyan texels) pulse, and torn slices
// split into red and blue.
vec4 stitch_texel(StitchFragment f, vec4 texel) {
    if (on == 0 || texel.a < 0.5) {
        return texel;
    }
    float t = stitch_game_time * 1200.0;
    ivec2 size = stitch_texture_size();
    ivec2 p = ivec2(f.uv * vec2(size));
    vec3 c = texel.rgb;
    if (texel.r < 0.01 && texel.g > 0.99 && texel.b > 0.99) {
        // a pulse running along the circuits
        float pulse = pow(fract(f.uv.x * 6.0 + f.uv.y * 3.0 - t * 1.2), 6.0);
        c = vec3(0.1, 0.8, 1.0) * (0.6 + 1.4 * pulse);
    }
    if (torn > 0.01) {
        float r = stitch_texel_fetch(p + ivec2(1, 0)).r;
        float b = stitch_texel_fetch(p - ivec2(1, 0)).b;
        c = vec3(r * 1.3, c.g * 0.6, b * 1.4);
    }
    float scan = 0.85 + 0.15 * step(0.5, fract(float(p.y) * 0.5 - t * 4.0));
    return vec4(c * scan, texel.a);
}
