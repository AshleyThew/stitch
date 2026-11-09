# Stitch on CurseForge: the project form

CurseForge projects are made on its site, not through its API. At https://authors.curseforge.com,
**Create Project**, then:

| Field | Value |
|---|---|
| Game | Minecraft |
| Project type (class) | Mods |
| Name | Stitch |
| Summary | Resource pack shaders that keep working under shader packs: brings a pack's armour, item and map effects back under Iris and OptiFine. |
| Main category | API and Library |
| Additional categories | Cosmetic, Utility & QoL |
| Logo | `publish/icon.png` (512 x 512) |
| Licence | Custom licence: name "PolyForm Noncommercial 1.0.0", text from `LICENSE.md` |
| Description | `publish/description.md`, with the editor in Markdown mode |

Once it exists, in the project's settings:

| Field | Value |
|---|---|
| Source | https://github.com/AshleyThew/stitch |
| Issues | https://github.com/AshleyThew/stitch/issues |
| Wiki | https://github.com/AshleyThew/stitch/blob/main/docs/SPEC.md |
| Images | `docs/images/hero.png`, `docs/images/compare.png`, and `docs/images/showcase/*.gif` |

Files are uploaded with the project id (About Project on its page); the very first file needs forcing, as an
empty project's files page reads as unknown. Each file is tagged Fabric, Forge, Client and every
Minecraft release from 1.18.2, with Iris as an optional dependency, as a beta.

The Modrinth project (`modrinth-project.json`) is `stitch-shaders`: the `stitch` slug was taken by a project that isn't public.
