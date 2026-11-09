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
  "uses": ["fragment_depth"],
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
| `uses` | no | Capabilities it uses where the program has them. The stitch applies either way; `#ifdef STITCH_HAS_<CAPABILITY>` (`STITCH_HAS_FRAGMENT_DEPTH`) says which it got, in both functions. |
| `passes` | no | `main`, `shadow`, or both (default both). Shapes belong in both, so shadows match. |
| `order` | no | Lower runs first among one pack's stitches. Across packs, pack order decides (lowest pack first). |
| `core` | no | `false` keeps the stitch out of Minecraft's core shaders (default `true`), for an effect only shader packs need it for. |

## Targets

Targets name what is drawn, not a shader. Each reader maps them to the programs it has.

| Target | What | Minecraft core shaders | Iris (shader pack) programs |
|---|---|---|---|
| `armor` | worn armour layers | `rendertype_armor_cutout_no_cull`, `entity` (1.21.2+) | entities, entities cutout, and their shadow programs |
| `entity` | entity models | `rendertype_entity_*`, `entity` | entities (all variants) and shadow |
| `item_world` | items in a hand, on the ground, in frames | `rendertype_item_entity_translucent_cull`, `rendertype_entity_translucent_cull`, `item` | hand, hand translucent, entities, shadow |
| `item_gui` | items in a screen | the same item shaders, in a screen | none (shader packs never draw screens) |
| `text_world` | text in the world (signs, name tags, maps in item frames) | `rendertype_text`, `text` (26.2+) | text programs, hand text, shadow text |
| `text_gui` | text in a screen | `rendertype_text*` in a screen | none |
| `glint` | enchantment glint | `rendertype_*glint*` | glint |

OptiFine picks a program by what is being drawn rather than how: its entity programs (and `shadow`) draw text an
entity draws, such as a name tag or a map in an item frame, and `gbuffers_block` draws the text on signs, so
`text_world` stitches go into those, beside `armor` and `entity` ones.

Where Minecraft draws a target with a shader it shares with other drawing (from 1.21.2 armour is drawn with
the entity shader, each kind of entity a program of its own defines), a reader weaves a stitch only into
the target's own programs, and leaves the rest as if there were no stitch. For armour those are the
programs with `ALPHA_CUTOUT` and `NO_OVERLAY` that neither move their texture (`APPLY_TEXTURE_MATRIX`)
nor glow (`EMISSIVE`); from 26.1 also `PER_FACE_LIGHTING`, which tells armour from the end crystal's beam.

Where a release draws a target with more than one core shader (items to 1.21.1: one on an entity, in a frame or
on the ground with `rendertype_entity_translucent_cull`, a translucent one under Fabulous graphics with
`rendertype_item_entity_translucent_cull`), the stitches go into each.

Where the pack has its own core shader for a target (a release's, through its overlays, or its base folder's),
the compiler weaves the stitches into that one rather than Minecraft's, so the pack's own changes stay. One the
compiler wove already is refused: the pack's own must be built again first, or the stitches would go in twice.

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
    vec3 position;    // the vertex function's position, interpolated (fragment_position); else 0
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
| `stitch_vertex_index()` | `vertex_index` | The vertex's number in its draw, counted as `corner` is (-1 where it can't be). Vertex only |
| `stitch_quad_corners(out vec3 position[4], out vec2 uv[4])` | `quad_corners` | The quad's four corners as submitted (before any stitch moved them), in corner order. Returns false, and leaves them 0, where the device can't share them this time: a stitch keeps a way to work without. Vertex only |
| `stitch_tangent()` | `tangent` | The quad's tangent as the shader pack hands it over. Vertex only |
| `stitch_set_depth(vec3 p)` | `fragment_depth` | Draw this fragment at the depth of `p`, a point in `position`'s space, instead of its own. Fragment only |
| `stitch_set_screen_position(vec2 p, float layer)` | `screen_position` | Draw this vertex at `p` on the screen (-1 to 1, left to right and bottom to top) in front of the world, a higher `layer` in front of a lower one, whatever its `position` says. For things that belong to the screen but are drawn in the world (a map in an item frame the server keeps beside the player). Vertex only |
| `bool stitch_final(out vec4 color)` | `final_color` | Not called, but defined by the stitch: returning true draws the fragment in `color`, with no lighting, tint or fog (the first stitch to claim a fragment draws it). For what belongs to the screen but is drawn in the world. Fragment only |
| `stitch_view_rotation()` | `view_rotation` | The `mat3` turning the world's axes to the camera's: `transpose(stitch_view_rotation()) * vec3(1.0, 0.0, 0.0)` is the camera's right in the world. Vertex only |
| varyings | listed in `varyings` | Read and write by name, in both functions |

A uniform a reader has no source for still compiles: `stitch_game_time` reads 0 (the stitch applies,
standing still) and `stitch_screen_size` reads 1×1. Minecraft 1.21.5 is such a release for
`game_time`: it binds no clock to the armour shader.

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
| `vertex_index` | `stitch_vertex_index()` works |
| `quad_corners` | `stitch_quad_corners()` is there; it may still return false on a device without subgroup shuffles (macOS), so a stitch should list this in `uses`, not `requires` |
| `tangent` | `stitch_tangent()` works (shader packs, which give entity vertices one) |
| `fragment_position` | `StitchFragment.position` is the interpolated position |
| `fragment_depth` | `stitch_set_depth()` works: the fragment's depth is written from the point (so the program loses early depth testing; give it only to stitches that need it). A depth set in the shadow pass is not supported |
| `screen_position` | `stitch_set_screen_position()` works. Main-pass programs only: the shadow pass has no screen, so a stitch using it usually hides the vertex there (`#ifndef STITCH_HAS_SCREEN_POSITION`). What it draws is still the shader pack's to light |
| `view_rotation` | `stitch_view_rotation()` works. Main-pass programs only |
| `final_color` | `stitch_final` is called. In Minecraft's core shaders before the program does anything, so the colour is drawn exactly. In a shader pack's main-pass programs (Iris and OptiFine) at the end of main, over the colour the shader pack lit, its other outputs (normals, materials) as it wrote them; its later full-screen passes (tone mapping, bloom, anti-aliasing) still run over the whole frame, this too. The texel function still runs first, so a fragment the stitch drops (alpha 0) is dropped there |

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
