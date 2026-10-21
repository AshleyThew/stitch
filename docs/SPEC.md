# Stitch format, version 1

What a resource pack ships so that its shader changes apply with or without a shader pack.

## Files

A stitch is one JSON file and up to two GLSL files, anywhere under `assets/<namespace>/stitches/`:

```
assets/<namespace>/stitches/<name>.json
assets/<namespace>/stitches/<name>.vert.glsl     optional
assets/<namespace>/stitches/<name>.frag.glsl     optional
```

Its id is `<namespace>:<name>`. Packs can ship different versions for different Minecraft versions
with ordinary pack overlays; Stitch reads what the resource manager resolves.

## The definition

```json
{
  "format": 1,
  "targets": ["armor"],
  "vertex": "example:stitches/trim.vert.glsl",
  "fragment": "example:stitches/trim.frag.glsl",
  "varyings": [
    {"name": "tint", "type": "vec4"},
    {"name": "own", "type": "int", "flat": true}
  ],
  "uniforms": ["game_time"],
  "requires": ["vertex_corner", "texture_size"],
  "passes": ["main", "shadow"],
  "order": 0
}
```

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | This document's version. A reader skips a stitch whose format it doesn't know, and says so once in the log. |
| `targets` | yes | Which kinds of drawing it applies to (see Targets). |
| `vertex`, `fragment` | at least one | The GLSL files. |
| `varyings` | no | Values the vertex function hands the fragment function. Types: `float`, `vec2`-`vec4`, `int`, `ivec2`-`ivec4` (ints must be `flat`). At most 4 per stitch. |
| `uniforms` | no | Values the reader supplies, from the fixed list below. |
| `requires` | no | Capabilities it needs. A reader that can't give one skips the whole stitch for that target; never half of it. |
| `passes` | no | `main`, `shadow`, or both (default both). Shapes belong in both, so shadows match. |
| `order` | no | Lower runs first among one pack's stitches. Across packs, pack order decides (lowest pack first). |

## Targets

Targets name what is drawn, not a shader. Each reader maps them to the programs it has.

| Target | What | Minecraft core shaders | Iris (shader pack) programs |
|---|---|---|---|
| `armor` | worn armour layers | `rendertype_armor_cutout_no_cull`, `entity` (1.21.2+) | entities, entities cutout, and their shadow programs |
| `entity` | entity models | `rendertype_entity_*`, `entity` | entities (all variants) and shadow |
| `item_world` | items in a hand, on the ground, in frames | `rendertype_item_entity_translucent_cull`, `rendertype_entity_translucent_cull`, `item` | hand, hand translucent, entities, shadow |
| `item_gui` | items in a screen | the same item shaders, in a screen | none (shader packs never draw screens) |
| `text_world` | text in the world (signs, name tags) | `rendertype_text*` | text programs, hand text, shadow text |
| `text_gui` | text in a screen | `rendertype_text*` in a screen | none |
| `glint` | enchantment glint | `rendertype_*glint*` | glint |

**A target is coarse.** Iris draws armour, mobs and players with the same programs, and vanilla draws
many things with one core shader. So a stitch runs on everything its target covers and **must leave
untouched anything it doesn't recognise.** The usual way: mark the textures the stitch is for (a texel
in an unused corner) and return early when the mark isn't there, as the
[flared leggings example](../examples/flared-leggings/) does. This is how pack core shaders already
work.

## The vertex function

```glsl
void stitch_vertex(inout StitchVertex v);

struct StitchVertex {
    vec3 position;    // model space as Minecraft submits it (view space before 1.20.5)
    vec2 uv;          // into the bound texture
    vec4 color;       // vertex colour: the dye for armour, the tint for items
    vec3 normal;
    ivec2 light;      // block, sky light (0-240)
    ivec2 overlay;    // hurt / white flash overlay
    int corner;       // 0-3 within the quad (requires vertex_corner); -1 where the device can't tell
    bool hidden;      // set true to drop the vertex (its triangles vanish)
};
```

Rules:

- Change any field. The reader feeds the changed values to the rest of the program, Minecraft's or the
  shader pack's, as if Minecraft had submitted them.
- `position` changes move geometry (a skirt's flare). They also apply in the shadow pass when
  `passes` includes `shadow`.
- No reads of the program's other inputs or outputs, and no writes to `gl_Position`: those differ
  between renderers. Everything a stitch may touch is in this interface.

## The fragment function

```glsl
vec4 stitch_texel(StitchFragment f, vec4 texel);

struct StitchFragment {
    vec2 uv;          // as the vertex function left it, interpolated
    bool shadow;      // in the shadow pass
};
```

It is called on the albedo (base colour) sample, before any lighting, and returns the texel to use.
Returning alpha 0 drops the fragment, as a cutout would; the reader turns that into a `discard` where
the program has none. A shader pack that samples the albedo texture several times (parallax, normal
reconstruction) gets the function on each sample.

## What a stitch may call

| Function or value | Requires | Meaning |
|---|---|---|
| `stitch_texture_size()` | `texture_size` | The bound albedo texture's size in texels |
| `stitch_texel_fetch(ivec2 p)` | `texture_size` | One texel of it, unfiltered, level 0 |
| `stitch_in_screen()` | none | True in a screen (GUI), false in the world. Vertex only |
| `stitch_up()` | `orientation` | Up, in `position`'s space: the world's up, or the screen's in a screen's entity preview (the player in the inventory). Vertex only |
| `stitch_handedness()` | `orientation` | 1, or -1 where that space is mirrored, so `cross(stitch_up(), normal) * stitch_handedness()` always runs to the model's left on its front. Vertex only |
| `stitch_game_time` | uniform `game_time` | 0-1 over twenty minutes, Minecraft's day (`* 1200.0` gives seconds); in both functions. Under a shader pack it runs on the shader pack's clock |
| `stitch_screen_size` | uniform `screen_size` | Framebuffer size in pixels |

A uniform a reader has no source for still compiles: `stitch_game_time` reads 0 (the stitch applies,
standing still) and `stitch_screen_size` reads 1×1. Minecraft 1.21.5 is such a release for
`game_time`: it binds no clock to the armour shader.
| varyings | listed in `varyings` | Read and write by name, in both functions |

Names a stitch declares itself (functions, constants) are kept private to it: the reader prefixes them
per stitch, so two packs can both have a `tint`.

## Capabilities

| Capability | What it promises |
|---|---|
| `vertex_corner` | `corner` counts 0-3 in Minecraft's quad order on every path (draw base vertex handled), or is -1 on a device that can't number them (26.3 without draw parameters): a stitch moving vertices by corner leaves them alone then |
| `texture_size` | The texture size and texel fetch functions work on the albedo texture |
| `position_model_space` | `position` is model space (always true from 1.20.5) |
| `orientation` | `stitch_up()` and `stitch_handedness()` work (the reader works them out from its matrices, which stitches can't read) |
| `shadow_pass` | The stitch runs in shadow programs too |

## Limits

- At most 32 stitches per program, 4 varyings each, 64 KiB of GLSL per stitch.
- GLSL version: what the program uses (Minecraft core shaders 150, Iris translates). Write portable
  GLSL 1.50 core: no extensions, no `#version`, no `#include`.
- A stitch that fails to compile on a program is dropped from that program only, with one log line
  naming the stitch, the program and the compiler's message.

## Versioning

- `format` grows when the interface changes incompatibly. Readers support a range of formats.
- New fields, capabilities and targets can arrive within a format; readers ignore what they don't know
  and skip stitches that require what they can't give.
