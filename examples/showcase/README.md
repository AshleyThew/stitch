# Showcase pack

Five armour sets drawn over Minecraft's five plain armour materials, each a single stitch. Put the
pack's folder in `resourcepacks`, build it with the Stitch action (or the compiler), and wear the armour
or put it on armour stands. Under a shader pack, add the Stitch mod.

| Look | Material | Stitch | What it does |
|---|---|---|---|
| Astral | diamond | `astral` | Black panels show a starfield and nebula fixed in the world's directions; the trim lights at its edges; each piece floats on its own swell |
| Magma | netherite | `magma` | Red texels are cracks of flowing lava; basalt beside a crack glows; a heat shimmer by position |
| Glitch | iron | `glitch` | Bursts of slices torn sideways with split colour; crawling scanlines; cyan circuits that pulse |
| Seraph | gold | `seraph` | The chestplate's side faces become a pair of beating, feathered wings; a sweep of light; twinkling gems |
| Phantom | chainmail | `phantom` | The mail dissolves and reforms with a burning edge; blue runes pulse; a smoke-like drift |

## How the pieces fit

- **Textures** are original, on Minecraft's 64×32 armour layout, under both of its paths:
  `textures/models/armor/<material>_layer_<n>.png` (to 1.21.1) and
  `textures/entity/equipment/humanoid[_leggings]/<material>.png` (from 1.21.2).
- **The mark.** Every texture's top right texel is opaque magenta. Its green carries the look (in
  sixteenths: 1 Astral to 5 Phantom), plus eight on the leggings layer. Each stitch reads it with
  `stitch_texel_fetch` and leaves anything unmarked alone, which keeps the other armour in the game as it
  was.
- **Keyed colours** in the textures tell the fragment stitches where to work: black for Astral's windows,
  pure red for Magma's cracks, pure cyan for Glitch's circuits, magenta for Seraph's gems, and pure blue
  for Phantom's runes.
- **Watertight movement.** A box's corner is a separate vertex on each face that shares it. So the stitches
  move vertices by a function of position alone (Magma's shimmer, Phantom's drift, Glitch's slices), or by
  the whole piece at once (Astral's float), and the faces stay joined. Seraph is the exception: it tears two
  faces off on purpose.
- **Time.** Every stitch reads `stitch_game_time`. Minecraft 1.21.5 binds no clock to the armour shader,
  so there the effects show, standing still.

## The wings

Seraph's vertex stitch finds the chestplate's body box side faces by their texture coordinates (the right
side spans u 16 to 20, the left 28 to 32) and vertex corner. It moves each face's back edge just behind
the shoulder blades and swings its front edge out to the wing's tip. It also passes the fragment stitch a
coordinate across the wing, from root to tip and bottom to top, and the fragment stitch draws the feathers
in it with alpha. In a screen, such as the inventory's player preview, the wings fold away.
