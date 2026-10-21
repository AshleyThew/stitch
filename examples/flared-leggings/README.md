# Example: flared leggings

A pack with one stitch, `example:flared_leggings`: leggings whose texture is marked flare out at the
foot, like a skirt. Every other armour is left as it is.

```
pack.mcmeta
assets/example/stitches/flared_leggings.json        the definition: the armor target, a vertex function
assets/example/stitches/flared_leggings.vert.glsl   stitch_vertex: moves the legs' bottom edges outward
```

To try it, mark a leggings texture: set its top right texel to opaque magenta (255, 0, 255, 255).
For leather leggings that is `assets/minecraft/textures/models/armor/leather_layer_2.png` (to 1.21.1)
and `assets/minecraft/textures/entity/equipment/humanoid_leggings/leather.png` (from 1.21.2). Then
build the pack with the Stitch action, and wear the leggings or put them on an armour stand.

The `armor` target runs on every armour layer, so the stitch first checks for its mark and returns
otherwise. Any stitch on a shared target needs a check like this.
