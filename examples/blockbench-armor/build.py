"""Builds a resource pack of 3D armour from a Blockbench model.

    python build.py wyrmguard.bbmodel build/wyrmguard [--material iron]

Writes the pack as a folder and as a .zip beside it. Needs Python 3.8+ and Pillow.

The model: a Blockbench project of cubes (any format; "Generic Model" is simplest), one texture or
more, laid out like a Java entity model of a player standing at the origin, facing north. Its top
level folders are the armour pieces: helmet, chestplate, leggings, boots. Inside each, the cubes go in
groups named after the body part they move with: head, body, right_arm, left_arm, right_leg,
left_leg. Bones' pivots are where Minecraft's are (head and body 0 24 0, right arm 5 22 0, left arm
-5 22 0, right leg 1.9 12 0, left leg -1.9 12 0). Cubes may be rotated, inside rotated groups;
every cube stays within 32 pixels of its body part's pivot.

The pack draws those cubes on the material's armour (iron by default):
- Minecraft 1.18.2 to 1.21.1: the armour shader ray traces the cubes. Each armour box's front face
  becomes a card in front of the camera; the fragment shader recovers the limb's transform from
  screen space derivatives and traces the cubes in the limb's own space.
- Minecraft 1.21.4 (an overlay): real geometry. The material's equipment asset lists as many layers
  as the model needs; each armour face becomes one face of a cube, placed by the vertex shader from
  its four corners (shared through subgroup operations). Where the driver has no subgroups (macOS),
  the same overlay falls back to the tracer.
"""
import argparse
import base64
import io
import json
import math
import os
import random
import shutil
import zipfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

PIECES = ("helmet", "chestplate", "leggings", "boots")
PARTS = ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg")
# each body part's pivot in Minecraft's model space (pixels; y down, front -z)
PIVOT = {"head": (0, 0, 0), "body": (0, 0, 0), "right_arm": (-5, 2, 0), "left_arm": (5, 2, 0),
         "right_leg": (-1.9, 12, 0), "left_leg": (1.9, 12, 0)}
# which armour texture draws which piece: the leggings have their own
HUMANOID = ("helmet", "chestplate", "boots")
LEGGINGS = ("leggings",)

W = 512          # armour texture width
TRI = 8          # texels a traced triangle: three corners, three UVs, its normal, spare
DATA_ROW = 8     # where the tracer's triangles start, below its header rows
GROUP = 6        # triangles a tracer group
ROW = {"head": 0, "body": 1, "right_arm": 2, "left_arm": 3, "right_leg": 4, "left_leg": 5}
# 1.21.4's armour boxes, by slot: head, hat, body, right arm, left arm, right leg, left leg
SLOTS = {"head": [0, 1], "body": [2], "right_arm": [3], "left_arm": [4], "right_leg": [5], "left_leg": [6]}
RECORD = 10      # texels a 1.21.4 face record: four corners, normal, four UVs, used


# --- reading the model --------------------------------------------------------------------------------

def rotation(degrees):
    """Blockbench's rotation: Euler angles in order ZYX (x applied first)."""
    x, y, z = (math.radians(a) for a in degrees)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = ((1, 0, 0), (0, cx, -sx), (0, sx, cx))
    ry = ((cy, 0, sy), (0, 1, 0), (-sy, 0, cy))
    rz = ((cz, -sz, 0), (sz, cz, 0), (0, 0, 1))

    def mul(a, b):
        return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)) for i in range(3))
    return mul(mul(rz, ry), rx)


def turn(p, origin, degrees):
    if not any(degrees):
        return p
    r = rotation(degrees)
    d = [p[i] - origin[i] for i in range(3)]
    return tuple(origin[i] + sum(r[i][k] * d[k] for k in range(3)) for i in range(3))


# a face's corners (top left, top right, bottom right, bottom left, as seen from outside) by which
# end of each axis they take: 0 the cube's low end, 1 its high end
FACE_CORNERS = {
    "north": ((1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)),
    "south": ((0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)),
    "east": ((1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)),
    "west": ((0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)),
    "up": ((0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)),
    "down": ((0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)),
}


def load(path):
    model = json.load(open(path, encoding="utf-8"))
    res = model.get("resolution", {"width": 16, "height": 16})
    textures = []
    for t in model.get("textures", []):
        source = t.get("source", "")
        if not source.startswith("data:image/png;base64,"):
            raise SystemExit("texture %s isn't embedded in the project: save the project with it" % t.get("name"))
        image = Image.open(io.BytesIO(base64.b64decode(source.split(",", 1)[1]))).convert("RGBA")
        textures.append((image, t.get("uv_width", res["width"]), t.get("uv_height", res["height"])))
    cubes = {e["uuid"]: e for e in model["elements"] if e.get("type", "cube") == "cube"}
    faces = {piece: {part: [] for part in PARTS} for piece in PIECES}

    def walk(node, piece, part, groups):
        if isinstance(node, str):
            cube = cubes.get(node)
            if cube is None or cube.get("export") is False:
                return
            if piece is None or part is None:
                raise SystemExit("cube %s is outside a piece's body part group" % cube.get("name"))
            faces[piece][part].extend(cube_faces(cube, groups, part, textures))
            return
        name = node.get("name", "")
        if node.get("export") is False:
            return
        if piece is None and name in PIECES:
            piece = name
        elif piece is not None and name in PARTS:
            part = name
        for child in node.get("children", []):
            walk(child, piece, part, groups + [(node.get("origin", [0, 0, 0]), node.get("rotation", [0, 0, 0]))])

    for node in model["outliner"]:
        walk(node, None, None, [])
    return faces, textures


def cube_faces(cube, groups, part, textures):
    """A cube's textured faces: (corners in the part's local Minecraft space, UVs (texture, x, y))."""
    g = cube.get("inflate", 0)
    lo = [cube["from"][i] - g for i in range(3)]
    hi = [cube["to"][i] + g for i in range(3)]
    out = []
    for face, spec in cube.get("faces", {}).items():
        if spec is None or spec.get("texture") is None or face not in FACE_CORNERS:
            continue
        corners = []
        for ends in FACE_CORNERS[face]:
            p = tuple(hi[i] if ends[i] else lo[i] for i in range(3))
            p = turn(p, cube.get("origin", [0, 0, 0]), cube.get("rotation", [0, 0, 0]))
            for origin, degrees in reversed(groups):
                p = turn(p, origin, degrees)
            # Blockbench's space to Minecraft's model space, then to the body part's own
            m = (-p[0], 24 - p[1], p[2])
            corners.append(tuple(m[i] - PIVOT[part][i] for i in range(3)))
        image, uw, uh = textures[spec["texture"]]
        u1, v1, u2, v2 = spec["uv"]
        uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
        turns = int(spec.get("rotation", 0)) // 90 % 4
        uvs = uvs[-turns:] + uvs[:-turns] if turns else uvs
        uvs = [(spec["texture"], u * image.width / uw, v * image.height / uh) for u, v in uvs]
        out.append((corners, uvs))
    return out


# --- encoding ----------------------------------------------------------------------------------------

# positions are a byte an axis: a quarter of a pixel, up to 32 pixels either side of the body part's pivot
STEP = 4


def enc(v, what):
    b = round(v * STEP) + 128
    if not 0 <= b <= 255:
        raise SystemExit("%s is %.1f pixels from its body part's pivot; the limit is 32" % (what, abs(v)))
    return b


def enc_lo(v):
    return max(0, math.floor(v * STEP) + 128)


def enc_hi(v):
    return min(255, math.ceil(v * STEP) + 128)


def u24(n):
    return ((n >> 16) & 255, (n >> 8) & 255, n & 255, 255)


def uv24(x, y):
    """A texture position to a quarter pixel: 12 bits each."""
    a, b = round(x * 4), round(y * 4)
    assert 0 <= a < 4096 and 0 <= b < 4096, "texture too large"
    return u24(a << 12 | b)


def normal(c):
    e1 = [c[1][i] - c[0][i] for i in range(3)]
    e2 = [c[3][i] - c[0][i] for i in range(3)]
    n = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
    length = math.sqrt(sum(x * x for x in n)) or 1.0
    return tuple(round((x / length * 0.5 + 0.5) * 255) for x in n) + (255,)


def put(img, row, at, px):
    img.putpixel((at % W, row + at // W), px)


def clusters(points, k):
    """k-means on points: groups of nearby triangles."""
    if len(points) <= k:
        return [[i] for i in range(len(points))]
    rng = random.Random(1)
    centres = rng.sample(points, k)
    groups = []
    for _ in range(25):
        groups = [[] for _ in range(k)]
        for i, c in enumerate(points):
            groups[min(range(k), key=lambda g: sum((c[j] - centres[g][j]) ** 2 for j in range(3)))].append(i)
        centres = [tuple(sum(points[i][j] for i in g) / len(g) for j in range(3)) if g else centres[n] for n, g in enumerate(groups)]
    return [g for g in groups if g]


class Atlas:
    """The model's textures, copied into the armour texture below its data."""

    def __init__(self, textures):
        self.textures = textures
        self.height = sum(t[0].height for t in textures)
        if any(t[0].width > W for t in textures):
            raise SystemExit("textures wider than %d pixels aren't supported" % W)

    def place(self, img, top):
        y = top
        self.at = []
        for image, _, _ in self.textures:
            img.paste(image, (0, y))
            self.at.append(y)
            y += image.height

    def uv(self, t):
        index, x, y = t
        return uv24(x, self.at[index] + y)


def tracer_rows(parts):
    count = sum(2 * len(f) for f in parts.values())
    return DATA_ROW + (count * TRI + W - 1) // W


def write_tracer(img, top, parts, atlas):
    """The tracer's data from row `top`: a header row a body part, then its triangles."""
    t = 0
    for part, faces in parts.items():
        if not faces:
            continue
        tris = []
        for corners, uvs in faces:
            n = normal(corners)
            tris.append(((corners[0], corners[1], corners[2]), (uvs[0], uvs[1], uvs[2]), n))
            tris.append(((corners[2], corners[3], corners[0]), (uvs[2], uvs[3], uvs[0]), n))
        row = top + ROW[part]
        pts = [p for tri in tris for p in tri[0]]
        lo = [min(p[i] for p in pts) for i in range(3)]
        hi = [max(p[i] for p in pts) for i in range(3)]
        centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
        groups = clusters([tuple(sum(p[i] for p in tri[0]) / 3 for i in range(3)) for tri in tris],
                          max(1, min(100, round(len(tris) / GROUP))))
        img.putpixel((0, row), (len(groups), 0, 0, 255))
        img.putpixel((1, row), tuple(enc(x, "a cube of the " + part) for x in centre) + (255,))
        img.putpixel((2, row), (min(255, math.ceil(max(math.dist(centre, p) for p in pts) * STEP)), 0, 0, 255))
        img.putpixel((3, row), tuple(enc_lo(x) for x in lo) + (255,))
        img.putpixel((4, row), tuple(enc_hi(x) for x in hi) + (255,))
        for g, group in enumerate(groups):
            gp = [p for i in group for p in tris[i][0]]
            img.putpixel((8 + 4 * g, row), u24(t))
            img.putpixel((9 + 4 * g, row), u24(len(group)))
            img.putpixel((10 + 4 * g, row), tuple(enc_lo(min(p[i] for p in gp)) for i in range(3)) + (255,))
            img.putpixel((11 + 4 * g, row), tuple(enc_hi(max(p[i] for p in gp)) for i in range(3)) + (255,))
            for i in group:
                corners, uvs, n = tris[i]
                texels = [tuple(enc(x, "a cube of the " + part) for x in p) + (255,) for p in corners]
                texels += [atlas.uv(uv) for uv in uvs] + [n, (0, 0, 0, 255)]
                for k, px in enumerate(texels):
                    put(img, top + DATA_ROW, t * TRI + k, px)
                t += 1


def layers_needed(parts):
    need = 1
    for part, faces in parts.items():
        need = max(need, math.ceil(len(faces) / (6 * len(SLOTS[part]))))
    return need


def write_layers(img, parts, layers, atlas):
    """1.21.4's records: row = layer, record (slot * 6 + face) * RECORD; each armour face one cube face."""
    for part, faces in parts.items():
        slots = SLOTS[part]
        for k, (corners, uvs) in enumerate(faces):
            layer, rest = divmod(k, 6 * len(slots))
            slot, face = slots[rest // 6], rest % 6
            r = (slot * 6 + face) * RECORD
            for c in range(4):
                img.putpixel((r + c, layer), tuple(enc(x, "a cube of the " + part) for x in corners[c]) + (255,))
                img.putpixel((r + 5 + c, layer), atlas.uv(uvs[c]))
            img.putpixel((r + 4, layer), normal(corners))
            img.putpixel((r + 9, layer), (1, 0, 0, 255))


def texture_1182(parts, mark, atlas):
    rows = tracer_rows(parts)
    img = Image.new("RGBA", (W, rows + atlas.height), (0, 0, 0, 0))
    atlas.place(img, rows)
    write_tracer(img, 0, parts, atlas)
    img.putpixel((W - 1, 0), (255, mark, 255, 255))
    return img


def texture_1214(parts, mark, atlas):
    layers = layers_needed(parts)
    base = max(2, layers)
    rows = base + tracer_rows(parts)
    img = Image.new("RGBA", (W, rows + atlas.height), (0, 0, 0, 0))
    atlas.place(img, rows)
    write_layers(img, parts, layers, atlas)
    write_tracer(img, base, parts, atlas)
    img.putpixel((W - 1, 0), (255, mark, 255, 255))
    img.putpixel((W - 2, 0), u24(base))
    return img, layers


def merge(faces, pieces):
    parts = {part: [] for part in PARTS}
    for piece in pieces:
        for part in PARTS:
            parts[part].extend(faces[piece][part])
    return parts


# --- the pack -----------------------------------------------------------------------------------------

def main():
    args = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    args.add_argument("model")
    args.add_argument("out")
    args.add_argument("--material", default="iron", choices=["leather", "chainmail", "iron", "gold", "diamond", "netherite"])
    a = args.parse_args()
    faces, textures = load(a.model)
    atlas = Atlas(textures)
    humanoid, leggings = merge(faces, HUMANOID), merge(faces, LEGGINGS)

    if os.path.exists(a.out):
        shutil.rmtree(a.out)
    armor = os.path.join(a.out, "assets/minecraft/textures/models/armor")
    os.makedirs(armor)
    texture_1182(humanoid, 1, atlas).save(os.path.join(armor, "%s_layer_1.png" % a.material))
    texture_1182(leggings, 2, atlas).save(os.path.join(armor, "%s_layer_2.png" % a.material))
    shutil.copytree(os.path.join(HERE, "shaders/1.18.2"), os.path.join(a.out, "assets/minecraft/shaders/core"))

    overlay = os.path.join(a.out, "overlay_1_21_4/assets/minecraft")
    equipment = {"humanoid": [], "humanoid_leggings": []}
    for kind, parts, mark in (("humanoid", humanoid, 1), ("humanoid_leggings", leggings, 2)):
        img, layers = texture_1214(parts, mark, atlas)
        folder = os.path.join(overlay, "textures/entity/equipment", kind)
        os.makedirs(folder)
        name = "model_%s_%s" % (a.material, "h" if kind == "humanoid" else "l")
        img.save(os.path.join(folder, name + ".png"))
        # every layer the same texture, so they draw in one batch; its tint tells the shader which
        equipment[kind] = [{"texture": "minecraft:" + name, "dyeable": {"color_when_undyed": i + 1}} for i in range(layers)]
        print("%-18s %d layers" % (kind, layers))
    equipment["horse_body"] = [{"texture": "minecraft:" + a.material}] if a.material in ("leather", "iron", "gold", "diamond") else None
    equipment = {k: v for k, v in equipment.items() if v}
    os.makedirs(os.path.join(overlay, "equipment"))
    json.dump({"layers": equipment}, open(os.path.join(overlay, "equipment", a.material + ".json"), "w"), indent=2)
    shutil.copytree(os.path.join(HERE, "shaders/1.21.4/core"), os.path.join(overlay, "shaders/core"))
    shutil.copytree(os.path.join(HERE, "shaders/1.21.4/include"), os.path.join(overlay, "shaders/include"))

    json.dump({
        "pack": {"pack_format": 8, "supported_formats": {"min_inclusive": 8, "max_inclusive": 46},
                 "description": "3D armour from %s" % os.path.basename(a.model)},
        "overlays": {"entries": [{"formats": {"min_inclusive": 46, "max_inclusive": 46}, "directory": "overlay_1_21_4"}]},
    }, open(os.path.join(a.out, "pack.mcmeta"), "w"), indent=2)
    with zipfile.ZipFile(a.out.rstrip("/\\") + ".zip", "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(a.out):
            for n in files:
                p = os.path.join(root, n)
                z.write(p, os.path.relpath(p, a.out).replace(os.sep, "/"))
    print("wrote", a.out.rstrip("/\\") + ".zip")


if __name__ == "__main__":
    main()
