# Stitch

Resource pack shaders that keep working under shader packs.

## What it does

A resource pack can change how Minecraft draws things by shipping its own copies of the game's core
shaders: armour shaped by its texture, items that hide parts of themselves, text effects. Those copies
work in vanilla and with Sodium, but a shader pack (Iris or OptiFine) draws the world with its own
programs, and the pack's changes vanish.

With Stitch, a pack writes each change once, as a **stitch**: a small GLSL function against a fixed
interface ("change this vertex", "change this texel"), and a JSON file saying what it applies to.

- **Without a shader pack**, nothing is needed on the player's side. When the pack is built, Stitch
  weaves its stitches into Minecraft's own core shaders, for every release from 1.18.2, as ordinary pack
  overlays.
- **With a shader pack**, the player adds the Stitch mod. It weaves the same stitches into the shader
  pack's programs as they load, so the pack's changes and the shader pack's lighting and shadows both
  apply.

Players without the mod see exactly what they would without Stitch.

## Adding Stitch to a pack

### 1. Write the stitches

A stitch is a JSON file and one or two GLSL files under `assets/<namespace>/stitches/`:

```
assets/example/stitches/flared_leggings.json
assets/example/stitches/flared_leggings.vert.glsl
```

```json
{
  "format": 1,
  "targets": ["armor"],
  "vertex": "example:stitches/flared_leggings.vert.glsl",
  "requires": ["texture_size"]
}
```

```glsl
void stitch_vertex(inout StitchVertex v) {
    // move, recolour or hide the vertex; the fragment side is stitch_texel(f, texel)
}
```

A target such as `armor` covers everything of its kind, so a stitch must check that it is looking at
something it was written for (usually a marker texel in the texture) and leave everything else alone.

The [specification](docs/SPEC.md) has every field, target and function. The
[flared leggings example](examples/flared-leggings/) is a complete pack to start from.

### 2. Build them into the pack

In the pack's GitHub workflow, run Stitch's action after checking the pack out:

```yaml
- uses: actions/checkout@v4
- uses: AshleyThew/stitch@v1
  with:
    pack: .
```

It fetches each Minecraft release's shaders from Mojang, weaves the pack's stitches into them, and
writes the results into the pack as overlays, with their entries in `pack.mcmeta`. A stitch that can't
be woven fails the build.

| Input | Default | Meaning |
|---|---|---|
| `pack` | `.` | The pack's folder (the one with `pack.mcmeta`) |
| `versions` | `1.18.2+` | Releases to build for: a range (`1.20.5-1.21.4`), from one on (`1.20.5+`), or a list |
| `output` | the pack | Where the overlays and `pack.mcmeta` are written |
| `base` | `true` | Also write the oldest range into the pack's base folder, for releases before overlays (to 1.20.1) |
| `strict` | `true` | Fail on warnings too, such as a stitch left out of a release that can't give what it needs |

Releases up to 1.20.1 only load a pack whose `pack_format` is exactly theirs; the base folder is for
them. A `pack.mcmeta` that every release from 1.20.1 accepts:

```json
{
  "pack": {
    "description": "...",
    "pack_format": 15,
    "supported_formats": {"min_inclusive": 15, "max_inclusive": 255},
    "min_format": 15,
    "max_format": 255
  }
}
```

### 3. Tell shader pack players about the mod

Players who use a shader pack install the Stitch mod for their game, one jar:

| Loader | Minecraft | Jar |
|---|---|---|
| Fabric with Iris | 1.18.2 | `stitch-fabric-1.18.jar` |
| | 1.19 to 1.21.11 | `stitch-fabric-1.19.jar` |
| | 26.1 and 26.2 | `stitch-fabric-26.1.jar` |
| | 26.3 on | `stitch-fabric-26.3.jar` |
| Forge with OptiFine | 1.18.2 | `stitch-forge-1.18.jar` |
| | 1.19 to 1.20.4 | `stitch-forge-1.19.jar` |
| | 1.20.6 to 1.21.11 | `stitch-forge-1.20.6.jar` |

It needs nothing from the server and changes nothing without a shader pack.

## Where it works

| Setup | Stitches apply |
|---|---|
| Vanilla, Sodium, or Iris and OptiFine with shaders off | yes, from the pack alone |
| Fabric with Iris and a shader pack | yes, with the mod |
| Forge with OptiFine and a shader pack | yes, with the mod, wherever OptiFine runs on Forge |
| OptiFine without a mod loader | no: it can't load mods |

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE.md): free for any noncommercial use.
