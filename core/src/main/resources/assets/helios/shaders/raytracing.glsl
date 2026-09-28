// Declarations shared by the ray tracing stages. Include right after the stage's #extension lines.
#extension GL_EXT_scalar_block_layout : require
#extension GL_EXT_buffer_reference2 : require
#extension GL_EXT_buffer_reference_uvec2 : require

#include "common.glsl"

const float SUN_ANGULAR_RADIUS = 0.012;    // radians; a bit larger than the real sun for soft edges
const vec3 SUN_COLOR = vec3(1.0, 0.94, 0.86);
const float SUN_STRENGTH = 3.2;            // irradiance / PI folded into one factor
const float SKY_AMBIENT = 0.55;            // sky radiance scale for indirect light (sun:sky balance)
const float SUN_DISK_RADIANCE = 40.0;
const float EMISSION_STRENGTH = 2.6;       // radiance of a light level 15 block, relative to albedo
const vec3 BLOCK_LIGHT_COLOR = vec3(1.0, 0.76, 0.5);
const float BLOCK_LIGHT_STRENGTH = 1.8;    // irradiance at block light level 15 (torch-lit surfaces)
const float GLASS_IOR = 1.5;
const float ICE_IOR = 1.31;
const float MAX_DISTANCE = 4096.0;
const float RAY_EPSILON = 1.0e-3;

// Instance masks
const uint MASK_WORLD = 0x01u;             // chunk sections: visible to every ray
const uint MASK_SHADOW_CASTER = 0x02u;     // entity proxies: only shadow rays hit them
const uint MASK_ALL = 0xFFu;

// frameInfo.w flags
const uint FLAG_RESET = 1u;
const uint FLAG_CAMERA_UNDERWATER = 2u;

// Water optics (per block travelled). Scattering is scaled by the water surface colour.
const vec3 WATER_ABSORPTION = vec3(0.45, 0.12, 0.09);
const float WATER_SCATTER_ALBEDO = 0.12;
const vec3 DEFAULT_WATER_ALBEDO = vec3(0.12, 0.24, 0.45);

layout(set = 0, binding = 1, std140) uniform FrameUBO {
    mat4 invViewProj;   // unjittered, camera-anchor relative
    mat4 viewProj;
    mat4 prevViewProj;
    vec4 cameraPos;     // xyz relative to anchor
    vec4 lightDir;      // xyz towards light, w intensity
    vec4 skyColor;      // rgb linear zenith colour, w rain
    vec4 jitter;        // xy jitter in pixels (+y down), zw render size
    uvec4 frameInfo;    // x frame index, y max bounces, z samples per pixel, w flags
    vec4 prevCameraPos; // previous camera position, relative to the current anchor
    vec4 params;        // x angular size of one render pixel (radians)
} frame;

// Vertex layout written by SectionGeometry (32 bytes, scalar).
struct Vertex {
    vec3 pos;
    vec2 uv;
    uint color;
    uint material;
    uint pad;
};

layout(buffer_reference, scalar, buffer_reference_align = 4) readonly buffer VertexBuffer {
    Vertex v[];
};

struct GeometryEntry {
    uvec2 vertexAddress;
    uint opaqueQuads;
    uint pad;
};

layout(set = 0, binding = 2, scalar) readonly buffer GeometryTable {
    GeometryEntry entries[];
} geometryTable;

layout(set = 0, binding = 3) uniform sampler2D blockAtlas;

struct SurfacePayload {
    vec3 albedo;
    float t;        // < 0 on miss
    vec3 normal;    // geometric, facing the incoming ray
    uint material;
    float alpha;    // texture alpha (glass transparency)
};

struct Triangle {
    Vertex v0, v1, v2;
};

// Quads are split as (0,1,2) and (0,2,3); opaque quads precede cutout quads in the vertex buffer
// and each lives in its own BLAS geometry, whose primitive IDs restart at 0.
Triangle fetchTriangle(uint instance, uint geometryIndex, uint primitive) {
    GeometryEntry e = geometryTable.entries[instance];
    VertexBuffer vb = VertexBuffer(e.vertexAddress);
    uint quad = primitive / 2u + (geometryIndex == 1u ? e.opaqueQuads : 0u);
    uint second = primitive & 1u;
    uint base = quad * 4u;
    Triangle tri;
    tri.v0 = vb.v[base];
    tri.v1 = vb.v[base + 1u + second];
    tri.v2 = vb.v[base + 2u + second];
    return tri;
}

vec2 interpolateUv(Triangle tri, vec2 bary) {
    vec3 w = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    return tri.v0.uv * w.x + tri.v1.uv * w.y + tri.v2.uv * w.z;
}

vec3 skyRadiance(vec3 dir, bool includeSunDisk) {
    vec3 zenith = frame.skyColor.rgb;
    float rain = frame.skyColor.w;
    vec3 horizon = mix(zenith * 1.5 + vec3(0.06), vec3(luminance(zenith) * 1.3 + 0.02), 0.4);
    vec3 sky = mix(horizon, zenith, pow(clamp(dir.y, 0.0, 1.0), 0.45));
    if (dir.y < 0.0) {
        sky = horizon * mix(1.0, 0.3, clamp(-dir.y * 3.0, 0.0, 1.0));
    }
    float mu = max(dot(dir, frame.lightDir.xyz), 0.0);
    float intensity = frame.lightDir.w;
    sky += SUN_COLOR * intensity * (1.0 - rain) * 0.3 * pow(mu, 12.0);
    if (includeSunDisk && mu > cos(SUN_ANGULAR_RADIUS)) {
        sky += SUN_COLOR * intensity * (1.0 - rain) * SUN_DISK_RADIANCE;
    }
    return sky;
}

vec3 emissionOf(uint material) {
    return vec3(float(material & MAT_EMISSION_MASK) * (EMISSION_STRENGTH / 15.0));
}

// Light from nearby light sources, from Minecraft's own light propagation (stored per quad).
// Complements ray traced emission, which alone is too noisy/dim for small lights like torches.
vec3 blockLightOf(uint material) {
    if ((material & MAT_EMISSION_MASK) != 0u) return vec3(0.0); // light sources are lit by their own emission
    float level = float((material >> MAT_BLOCK_LIGHT_SHIFT) & 0xFu) / 15.0;
    return BLOCK_LIGHT_COLOR * (BLOCK_LIGHT_STRENGTH * pow(level, 2.6));
}
