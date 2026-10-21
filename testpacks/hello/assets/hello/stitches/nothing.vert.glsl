// Leaves every vertex as it is; reads the texture's size so the helper is compiled and used.
void stitch_vertex(inout StitchVertex v) {
    seen = stitch_texture_size().x > 0 ? 1.0 : 0.0;
}
