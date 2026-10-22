#version 150
#extension GL_KHR_shader_subgroup_basic : enable
#extension GL_KHR_shader_subgroup_ballot : enable
#extension GL_KHR_shader_subgroup_shuffle : enable

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:model_armor_mode.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

#ifdef MODEL_GEOMETRY

// real geometry: drawn as Minecraft draws any entity
void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    color *= vertexColor * ColorModulator;
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    color *= lightMapColor;
#endif
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}

#else

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

in vec3 rtOrig;
in vec3 rtLocal;
in vec3 rtBill;
flat in ivec4 rtInfo;
flat in int rtStep;
flat in vec4 rtEye;

// a triangle is eight texels from the tracer data's row 8: three corners, their UVs, its normal
const int TRI = 8;
const int DATA_ROW = 8;

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

vec4 tri_texel(int t, int k, int width, int base) {
    int at = t * TRI + k;
    return texelFetch(Sampler0, ivec2(at % width, base + DATA_ROW + at / width), 0);
}

void main() {
    // derivatives first, while every pixel of the quad still runs
    vec3 dpx = dFdx(rtOrig);
    vec3 dpy = dFdy(rtOrig);
    vec3 dlx = dFdx(rtLocal);
    vec3 dly = dFdy(rtLocal);

    if (rtInfo.x == 0) {
        vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
        if (color.a < ALPHA_CUTOUT) {
            discard;
        }
#endif
        color *= vertexColor * ColorModulator;
#ifndef NO_OVERLAY
        color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
        color *= lightMapColor;
#endif
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        gl_FragDepth = gl_FragCoord.z;
        return;
    }

    // The limb's transform M (model pixels to Minecraft's space) maps the face's model-space steps
    // across the screen onto its real ones: two columns from the face's plane, the third their cross.
    int c = rtInfo.z;
    int a = (c + 1) % 3;
    int b = (c + 2) % 3;
    vec2 lx = vec2(dlx[a], dlx[b]);
    vec2 ly = vec2(dly[a], dly[b]);
    float det = lx.x * ly.y - ly.x * lx.y;
    vec3 ma = (dpx * ly.y - dpy * lx.y) / det;
    vec3 mb = (dpy * lx.x - dpx * ly.x) / det;
    float s = length(ma);
    mat3 m;
    m[a] = ma;
    m[b] = mb;
    m[c] = cross(ma, mb) / s;
    vec3 t = rtOrig - m * rtLocal;
    mat3 mi = transpose(m) / (s * s);

    vec3 origin;
    vec3 dir;
    if (rtEye.w > 0.5) {
        origin = mi * (rtEye.xyz - t);
        dir = mi * (rtBill - rtEye.xyz);
    } else {
        dir = mi * rtEye.xyz;
        origin = mi * (rtBill - t) - dir * 100.0;
    }

    // the part's box first: most of a card misses it, and stops here
    int part = rtInfo.y;
    int base = rtInfo.w;
    int row = base + part;
    vec3 inv = 1.0 / dir;
    vec3 t0 = (decode(texelFetch(Sampler0, ivec2(3, row), 0)) - origin) * inv;
    vec3 t1 = (decode(texelFetch(Sampler0, ivec2(4, row), 0)) - origin) * inv;
    vec3 near = min(t0, t1);
    vec3 far = max(t0, t1);
    if (max(max(near.x, near.y), near.z) > min(min(far.x, far.y), far.z) || min(min(far.x, far.y), far.z) <= 0.0) {
        discard;
    }

    // then each group's box, and only the triangles of the groups the ray passes through
    int width = textureSize(Sampler0, 0).x;
    float best = 1e30;
    int hit = -1;
    vec2 hitUv = vec2(0.0);
    for (int g = 0; g < rtStep; g++) {
        t0 = (decode(texelFetch(Sampler0, ivec2(10 + 4 * g, row), 0)) - origin) * inv;
        t1 = (decode(texelFetch(Sampler0, ivec2(11 + 4 * g, row), 0)) - origin) * inv;
        near = min(t0, t1);
        far = max(t0, t1);
        float enter = max(max(near.x, near.y), near.z);
        float leave = min(min(far.x, far.y), far.z);
        if (enter > leave || leave <= 0.0 || enter >= best) {
            continue;
        }
        int start = u24(texelFetch(Sampler0, ivec2(8 + 4 * g, row), 0));
        int count = u24(texelFetch(Sampler0, ivec2(9 + 4 * g, row), 0));
        for (int i = start; i < start + count; i++) {
            vec3 p0 = decode(tri_texel(i, 0, width, base));
            vec3 e1 = decode(tri_texel(i, 1, width, base)) - p0;
            vec3 e2 = decode(tri_texel(i, 2, width, base)) - p0;
            vec3 p = cross(dir, e2);
            float d = dot(e1, p);
            if (abs(d) < 1e-8) {
                continue;
            }
            float invd = 1.0 / d;
            vec3 o = origin - p0;
            float u = dot(o, p) * invd;
            if (u < 0.0 || u > 1.0) {
                continue;
            }
            vec3 q = cross(o, e1);
            float v = dot(dir, q) * invd;
            if (v < 0.0 || u + v > 1.0) {
                continue;
            }
            float th = dot(e2, q) * invd;
            if (th <= 0.0 || th >= best) {
                continue;
            }
            vec2 a0 = uv_at(tri_texel(i, 3, width, base));
            vec2 a1 = uv_at(tri_texel(i, 4, width, base));
            vec2 a2 = uv_at(tri_texel(i, 5, width, base));
            // kept inside its own face: at an edge it would read the next face's texels
            vec2 at = clamp(a0 * (1.0 - u - v) + a1 * u + a2 * v, min(min(a0, a1), a2) + 0.01, max(max(a0, a1), a2) - 0.01);
            if (texelFetch(Sampler0, ivec2(at), 0).a < 0.1) {
                continue;
            }
            best = th;
            hit = i;
            hitUv = at;
        }
    }
    if (hit < 0) {
        discard;
    }

    vec3 n = normalize(m * (tri_texel(hit, 6, width, base).rgb * 2.0 - 1.0));
    vec3 hitPos = m * (origin + dir * best) + t;
    // lit from the side the eye sees
    if (dot(n, m * dir) > 0.0) {
        n = -n;
    }
    vec4 albedo = texelFetch(Sampler0, ivec2(hitUv), 0);
    vec4 color = albedo * minecraft_mix_light(Light0_Direction, Light1_Direction, n, vec4(1.0)) * lightMapColor * ColorModulator;
    vec4 clip = ProjMat * ModelViewMat * vec4(hitPos, 1.0);
    gl_FragDepth = clip.z / clip.w * 0.5 + 0.5;
    fragColor = linear_fog(color, fog_distance(hitPos, FogShape), FogStart, FogEnd, FogColor);
}

#endif
