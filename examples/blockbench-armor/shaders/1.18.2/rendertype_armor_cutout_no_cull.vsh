#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec4 normal;

// The face's own corner, where Minecraft put it and where it sits in the part's model space: the two
// differ by the limb's transform, which the fragment shader reads back from their derivatives.
out vec3 rtOrig;
out vec3 rtLocal;
// this pixel's point on the card the face became
out vec3 rtBill;
out vec4 rtLight;
// traced, data row, the face's fixed axis
flat out ivec4 rtInfo;
// the part's triangle groups
flat out int rtStep;
// the eye (w 1), or the view direction for an orthographic projection (w 0)
flat out vec4 rtEye;

// a cube's corners by the sides they take: x max, y max, z max
const ivec3 CORNER[8] = ivec3[](ivec3(0, 0, 0), ivec3(1, 0, 0), ivec3(1, 1, 0), ivec3(0, 1, 0),
                                ivec3(0, 0, 1), ivec3(1, 0, 1), ivec3(1, 1, 1), ivec3(0, 1, 1));
// each face's corners in Minecraft's order: east, west, down, up, north, south
const int FACE[24] = int[](5, 1, 2, 6, 0, 4, 7, 3, 5, 4, 0, 1, 2, 3, 7, 6, 1, 0, 3, 2, 4, 5, 6, 7);
const vec2 SPREAD[4] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

vec3 decode(vec4 t) {
    return (round(t.rgb * 255.0) - 128.0) / 4.0;
}

int u24(vec4 t) {
    ivec3 b = ivec3(round(t.rgb * 255.0));
    return (b.r << 16) | (b.g << 8) | b.b;
}

void main() {
    vertexDistance = fog_distance(ModelViewMat, Position, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    texCoord1 = UV1;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
    rtOrig = Position;
    rtLocal = vec3(0.0);
    rtBill = Position;
    rtLight = texelFetch(Sampler2, UV2 / 16, 0);
    rtInfo = ivec4(0);
    rtStep = 1;
    rtEye = vec4(0.0);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    ivec2 size = textureSize(Sampler0, 0);
    vec4 mark = texelFetch(Sampler0, ivec2(size.x - 1, 0), 0);
    int layer = int(round(mark.g * 255.0));
    if (mark.r < 0.99 || mark.b < 0.99 || mark.a < 0.99 || (layer != 1 && layer != 2)) {
        return;
    }

    // which face of which box: Minecraft emits every box as its six faces in a fixed order, four
    // corners each, and the UV says which box
    int corner = gl_VertexID % 4;
    int face = (gl_VertexID / 4) % 6;
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
    float grow = layer == 2 ? 0.5 : (hat ? 1.5 : 1.0);
    vec3 lo = from - grow;
    vec3 hi = from + extent + grow;
    ivec3 side = CORNER[FACE[face * 4 + (mirrored ? 3 - corner : corner)]];
    vec3 local = vec3((side.x == 1) != mirrored ? hi.x : lo.x, side.y == 1 ? hi.y : lo.y, side.z == 1 ? hi.z : lo.z);

    // one face a part draws it all (the head box's east face; the hat box's draw nothing): its card
    // covers the part's sphere wherever the limb is
    if (face != 0 || hat) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    int groups = int(round(texelFetch(Sampler0, ivec2(0, part), 0).r * 255.0));
    if (groups == 0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    vec3 centre = decode(texelFetch(Sampler0, ivec2(1, part), 0));
    float reach = length(centre - local) + texelFetch(Sampler0, ivec2(2, part), 0).r * 255.0 / 4.0;

    vec4 view = ModelViewMat * vec4(Position, 1.0);
    mat4 unproject = inverse(ProjMat * ModelViewMat);
    bool ortho = abs(ProjMat[2][3]) < 0.5;
    if (ortho) {
        // a screen's entity preview, scaled by however much the screen likes: the card can't know
        // the model's size, so it takes a share of the screen instead
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
    vertexColor = Color;
    rtInfo = ivec4(1, part, face / 2, 0);
    rtStep = groups;
}
