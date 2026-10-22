#version 150
#extension GL_KHR_shader_subgroup_basic : enable
#extension GL_KHR_shader_subgroup_ballot : enable
#extension GL_KHR_shader_subgroup_shuffle : enable

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:model_armor_mode.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

#ifndef MODEL_GEOMETRY
// The tracer: the face's own corner, where Minecraft put it and where it sits in the part's model
// space (the fragment shader reads the limb's transform back from their derivatives), this pixel's
// point on the card the face became, and what the trace needs to know
out vec3 rtOrig;
out vec3 rtLocal;
out vec3 rtBill;
// traced, part, the face's fixed axis, the tracer data's first row
flat out ivec4 rtInfo;
flat out int rtStep;
// the eye (w 1), or the view direction for an orthographic projection (w 0)
flat out vec4 rtEye;
#endif

// The armour's own boxes, by slot: head, hat, body, right arm, left arm, right leg, left leg
const vec3 FROM[7] = vec3[](vec3(-4.0, -8.0, -4.0), vec3(-4.0, -8.0, -4.0), vec3(-4.0, 0.0, -2.0),
                            vec3(-3.0, -2.0, -2.0), vec3(-1.0, -2.0, -2.0), vec3(-2.0, 0.0, -2.0), vec3(-2.0, 0.0, -2.0));

vec3 decode(vec4 t) {
    return (round(t.rgb * 255.0) - 128.0) / 4.0;
}

// a texture position, to a quarter pixel: 12 bits each
vec2 uv_at(vec4 t) {
    ivec3 b = ivec3(round(t.rgb * 255.0));
    int n = (b.r << 16) | (b.g << 8) | b.b;
    return vec2(float(n >> 12), float(n & 4095)) / 4.0;
}

int u24(vec4 t) {
    ivec3 b = ivec3(round(t.rgb * 255.0));
    return (b.r << 16) | (b.g << 8) | b.b;
}

void hide() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}

#ifdef MODEL_GEOMETRY
// A corner's place on its box, from where it sits in the box's texture layout (Minecraft's box UV):
// face 0 down, 1 up, 2 west, 3 north, 4 east, 5 south; uv in texture pixels from the box's corner.
vec3 on_box(int face, vec2 uv, vec3 dim, vec3 lo, vec3 hi) {
    float w = dim.x;
    float h = dim.y;
    float d = dim.z;
    vec3 span = hi - lo;
    if (face == 0) {
        return vec3(lo.x + (uv.x - d) / w * span.x, lo.y, hi.z - uv.y / d * span.z);
    } else if (face == 1) {
        return vec3(lo.x + (uv.x - d - w) / w * span.x, hi.y, hi.z - uv.y / d * span.z);
    } else if (face == 2) {
        return vec3(lo.x, lo.y + (uv.y - d) / h * span.y, hi.z - uv.x / d * span.z);
    } else if (face == 3) {
        return vec3(lo.x + (uv.x - d) / w * span.x, lo.y + (uv.y - d) / h * span.y, lo.z);
    } else if (face == 4) {
        return vec3(hi.x, lo.y + (uv.y - d) / h * span.y, lo.z + (uv.x - d - w) / d * span.z);
    }
    return vec3(hi.x - (uv.x - 2.0 * d - w) / w * span.x, lo.y + (uv.y - d) / h * span.y, hi.z);
}
#else
// a cube's corners by the sides they take: x max, y max, z max
const ivec3 CORNER[8] = ivec3[](ivec3(0, 0, 0), ivec3(1, 0, 0), ivec3(1, 1, 0), ivec3(0, 1, 0),
                                ivec3(0, 0, 1), ivec3(1, 0, 1), ivec3(1, 1, 1), ivec3(0, 1, 1));
// each face's corners, faces numbered east, west, down, up, north, south
const int FACE[24] = int[](5, 1, 2, 6, 0, 4, 7, 3, 5, 4, 0, 1, 2, 3, 7, 6, 1, 0, 3, 2, 4, 5, 6, 7);
// this version emits a box's faces down, up, west, north, east, south
const int OLD_FACE[6] = int[](2, 3, 1, 4, 0, 5);
const vec2 SPREAD[4] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
#ifndef MODEL_GEOMETRY
    rtOrig = Position;
    rtLocal = vec3(0.0);
    rtBill = Position;
    rtInfo = ivec4(0);
    rtStep = 1;
    rtEye = vec4(0.0);
#endif

    ivec2 size = textureSize(Sampler0, 0);
    vec4 mark = texelFetch(Sampler0, ivec2(size.x - 1, 0), 0);
    int kind = int(round(mark.g * 255.0));
    if (mark.r < 0.99 || mark.b < 0.99 || mark.a < 0.99 || (kind != 1 && kind != 2)) {
        return;
    }
    bool leggings = kind == 2;
    // the layer: its tint carries its index plus one
    int layer = int(round(Color.b * 255.0)) - 1;

#ifdef MODEL_GEOMETRY
    // each armour draw starts at its first vertex on these versions
    int vid = gl_VertexID;
    // the face's four corners run side by side: each reads the other three's position and UV
    int corner = vid & 3;
    int lane0 = int(gl_SubgroupInvocationID) - corner;
    bool inRange = lane0 >= 0 && lane0 + 3 < int(gl_SubgroupSize);
    uvec4 running = subgroupBallot(true);
    bool whole = inRange;
    vec3 p[4];
    vec2 t[4];
    for (int k = 0; k < 4; k++) {
        uint lane = inRange ? uint(lane0 + k) : gl_SubgroupInvocationID;
        bool on = subgroupBallotBitExtract(running, lane);
        uint from = on ? lane : gl_SubgroupInvocationID;
        int sibling = subgroupShuffle(vid, from);
        p[k] = subgroupShuffle(Position, from);
        t[k] = subgroupShuffle(UV0, from) * vec2(64.0, 32.0);
        if (!on || sibling != vid - corner + k) {
            whole = false;
        }
    }
    if (!whole) {
        hide();
        return;
    }

    // which box and which face, from where the face sits in the texture layout
    vec2 tmin = min(min(t[0], t[1]), min(t[2], t[3]));
    vec2 tmax = max(max(t[0], t[1]), max(t[2], t[3]));
    int box = tmax.y < 16.01 ? (tmin.x > 31.99 ? 1 : 0) : (tmin.x > 39.99 ? 3 : (tmin.x > 15.99 ? 2 : 4));
    vec3 dim = box < 2 ? vec3(8.0) : (box == 2 ? vec3(8.0, 12.0, 4.0) : vec3(4.0, 12.0, 4.0));
    vec2 offs = box == 0 ? vec2(0.0) : (box == 1 ? vec2(32.0, 0.0) : (box == 2 ? vec2(16.0, 16.0) : (box == 3 ? vec2(40.0, 16.0) : vec2(0.0, 16.0))));
    vec2 rect = tmin - offs;
    int face = rect.y < dim.z - 0.01 ? (rect.x < dim.z + dim.x - 0.01 ? 0 : 1)
        : (rect.x < dim.z - 0.01 ? 2 : (rect.x < dim.z + dim.x - 0.01 ? 3 : (rect.x < 2.0 * dim.z + dim.x - 0.01 ? 4 : 5)));

    // the corners at the face's u and v edges
    int c00 = 0, c10 = 0, c01 = 0;
    for (int k = 0; k < 4; k++) {
        bool uLow = t[k].x < (tmin.x + tmax.x) * 0.5;
        bool vLow = t[k].y < (tmin.y + tmax.y) * 0.5;
        if (uLow && vLow) c00 = k;
        if (!uLow && vLow) c10 = k;
        if (uLow && !vLow) c01 = k;
    }
    vec3 du = p[c10] - p[c00];
    vec3 dv = p[c01] - p[c00];

    // A mirrored box (the left limbs) runs its texture the other way round its face. A screen's
    // entity preview draws mirrored, so its sense flips there too.
    bool ortho = abs(ProjMat[2][3]) < 0.5;
    float sense = sign(dot(cross(du, dv), Normal)) * (ortho ? -1.0 : 1.0);
    bool mirrored = (box == 3 || box == 4) && sense != (face == 1 ? 1.0 : -1.0);
    int slot = box < 3 ? box : (box == 3 ? (mirrored ? 4 : 3) : (mirrored ? 6 : 5));
    float grow = slot == 1 ? 1.5 : (slot >= 5 ? (leggings ? 0.4 : 0.9) : (slot == 2 && leggings ? 0.5 : 1.0));

    int record = (slot * 6 + face) * 10;
    if (layer < 0 || layer >= size.y || texelFetch(Sampler0, ivec2(record + 9, layer), 0).r < 0.002) {
        hide();
        return;
    }

    // the limb's transform, from the face: two edges give two of its axes, their cross the third
    vec3 lo = FROM[slot] - grow;
    vec3 hi = FROM[slot] + dim + grow;
    vec3 l00 = on_box(face, t[c00] - offs, dim, lo, hi);
    vec3 l10 = on_box(face, t[c10] - offs, dim, lo, hi);
    vec3 l01 = on_box(face, t[c01] - offs, dim, lo, hi);
    if (mirrored) {
        l00.x = lo.x + hi.x - l00.x;
        l10.x = lo.x + hi.x - l10.x;
        l01.x = lo.x + hi.x - l01.x;
    }
    int au = (face == 2 || face == 4) ? 2 : 0;
    int av = (face < 2) ? 2 : 1;
    int aw = 3 - au - av;
    vec3 mu = du / (l10 - l00)[au];
    vec3 mv = dv / (l01 - l00)[av];
    bool cyclic = (au + 1) % 3 == av;
    vec3 mw = cross(mu, mv) / length(mu) * (cyclic ? 1.0 : -1.0) * (ortho ? -1.0 : 1.0);
    mat3 m;
    m[au] = mu;
    m[av] = mv;
    m[aw] = mw;
    vec3 origin = p[c00] - m * l00;

    // this corner of the cube's face
    vec3 pos = m * decode(texelFetch(Sampler0, ivec2(record + corner, layer), 0)) + origin;
    vec3 n = normalize(m * (texelFetch(Sampler0, ivec2(record + 4, layer), 0).rgb * 2.0 - 1.0));
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, n, vec4(1.0));
    texCoord0 = uv_at(texelFetch(Sampler0, ivec2(record + 5 + corner, layer), 0)) / vec2(size);
#else
    // The tracer: the first layer's boxes carry it, one face a box; every other face and layer hides
    int base = u24(texelFetch(Sampler0, ivec2(size.x - 2, 0), 0));
    int corner = gl_VertexID % 4;
    int face = OLD_FACE[(gl_VertexID / 4) % 6];
    vec2 p = UV0 * vec2(64.0, 32.0);
    float u = p.x + ((corner == 0 || corner == 3) ? -0.5 : 0.5);
    bool top = p.y < 15.5 || (p.y < 16.5 && face != 2 && face != 3);
    int part = 0;
    bool hat = false;
    bool mirrored = false;
    vec3 from = vec3(-4.0, -8.0, -4.0);
    vec3 extent = vec3(8.0);
    if (top) {
        hat = u > 32.0;
    } else if (u > 16.0 && u < 40.0) {
        part = 1;
        from = vec3(-4.0, 0.0, -2.0);
        extent = vec3(8.0, 12.0, 4.0);
    } else {
        // a mirrored box (the left limbs) lists its corners backwards
        float g = face == 2 ? 16.0 : 20.0;
        float i = face == 2 ? 20.0 : (face == 3 ? 16.0 : 32.0);
        mirrored = abs(p.y - (corner < 2 ? g : i)) > 0.5;
        extent = vec3(4.0, 12.0, 4.0);
        if (u > 40.0) {
            part = mirrored ? 3 : 2;
            from = vec3(mirrored ? -1.0 : -3.0, -2.0, -2.0);
        } else {
            part = mirrored ? 5 : 4;
            from = vec3(-2.0, 0.0, -2.0);
        }
    }
    if (layer != 0 || face != 0 || hat) {
        hide();
        return;
    }
    int groups = int(round(texelFetch(Sampler0, ivec2(0, base + part), 0).r * 255.0));
    if (groups == 0) {
        hide();
        return;
    }
    float grow = part >= 4 ? (leggings ? 0.4 : 0.9) : (part == 1 && leggings ? 0.5 : 1.0);
    vec3 lo = from - grow;
    vec3 hi = from + extent + grow;
    ivec3 side = CORNER[FACE[face * 4 + (mirrored ? 3 - corner : corner)]];
    vec3 local = vec3((side.x == 1) != mirrored ? hi.x : lo.x, side.y == 1 ? hi.y : lo.y, side.z == 1 ? hi.z : lo.z);

    // the card covers the part's sphere wherever the limb is
    vec3 centre = decode(texelFetch(Sampler0, ivec2(1, base + part), 0));
    float reach = length(centre - local) + texelFetch(Sampler0, ivec2(2, base + part), 0).r * 255.0 / 4.0;
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    mat4 unproject = inverse(ProjMat * ModelViewMat);
    bool ortho = abs(ProjMat[2][3]) < 0.5;
    if (ortho) {
        // a screen's entity preview, scaled by however much the screen likes: the card takes a share
        // of the screen instead
        gl_Position = ProjMat * view;
        gl_Position.xy += SPREAD[corner] * 0.3 * gl_Position.w;
        vec4 back = unproject * gl_Position;
        rtBill = back.xyz / back.w;
    } else {
        float radius = reach / 16.0;
        float d = length(view.xyz);
        float spread = d > radius * 1.2 ? 1.4 * radius * d / sqrt(d * d - radius * radius) * d / max(-view.z, 0.05) : 64.0;
        vec4 card = view + vec4(SPREAD[corner] * spread, 0.0, 0.0);
        gl_Position = ProjMat * card;
        // no further past the screen's edge than needed: on a card many screens wide, the values the
        // limb is read back from barely change from pixel to pixel, and lose precision
        if (gl_Position.w > 0.0) {
            gl_Position.xy = clamp(gl_Position.xy / gl_Position.w, -1.25, 1.25) * gl_Position.w;
        }
        vec4 back = unproject * gl_Position;
        rtBill = back.xyz / back.w;
    }
    vec4 eye = unproject * vec4(0.0, 0.0, 1.0, 0.0);
    rtEye = ortho ? vec4(normalize(eye.xyz), 0.0) : vec4(eye.xyz / eye.w, 1.0);
    rtLocal = local;
    vertexColor = vec4(1.0);
    rtInfo = ivec4(1, part, face / 2, base);
    rtStep = groups;
#endif
}
