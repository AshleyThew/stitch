// Leaves every texel as it is.
vec4 stitch_texel(StitchFragment f, vec4 texel) {
    return seen >= 0.0 ? texel : vec4(0.0);
}
