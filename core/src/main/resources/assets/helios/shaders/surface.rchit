#version 460
#extension GL_EXT_ray_tracing : require
#include "raytracing.glsl"

layout(location = 0) rayPayloadInEXT SurfacePayload hit;
hitAttributeEXT vec2 bary;

void main() {
    Triangle tri = fetchTriangle(uint(gl_InstanceCustomIndexEXT), uint(gl_GeometryIndexEXT), uint(gl_PrimitiveID));
    // Pick a mip level from the pixel footprint (16 texels per block) so distant blocks do not
    // shimmer; uses Minecraft's own atlas mipmaps.
    float texelsPerPixel = gl_HitTEXT * frame.params.x * 16.0;
    float lod = log2(max(texelsPerPixel, 1.0));
    vec4 tex = textureLod(blockAtlas, interpolateUv(tri, bary), lod);
    vec3 tint = unpackUnorm4x8(tri.v0.color).rgb;
    // Instances only translate, so object-space normals are world-space normals.
    vec3 n = normalize(cross(tri.v1.pos - tri.v0.pos, tri.v2.pos - tri.v0.pos));
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) n = -n;

    // Tint is sRGB from Minecraft's colour providers; approximate linearization.
    hit.albedo = tex.rgb * (tint * tint);
    hit.t = gl_HitTEXT;
    hit.normal = n;
    hit.material = tri.v0.material;
    hit.alpha = tex.a;
}
