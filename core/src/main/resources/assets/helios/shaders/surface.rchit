#version 460
#extension GL_EXT_ray_tracing : require
#include "raytracing.glsl"

layout(location = 0) rayPayloadInEXT SurfacePayload hit;
hitAttributeEXT vec2 bary;

void main() {
    Triangle tri = fetchTriangle(uint(gl_InstanceCustomIndexEXT), uint(gl_GeometryIndexEXT), uint(gl_PrimitiveID));
    vec4 tex = textureLod(blockAtlas, interpolateUv(tri, bary), 0.0);
    vec3 tint = unpackUnorm4x8(tri.v0.color).rgb;
    // Instances only translate, so object-space normals are world-space normals.
    vec3 n = normalize(cross(tri.v1.pos - tri.v0.pos, tri.v2.pos - tri.v0.pos));
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) n = -n;

    // Tint is sRGB-ish from Minecraft's colour providers; approximate linearization.
    hit.albedo = tex.rgb * (tint * tint);
    hit.t = gl_HitTEXT;
    hit.normal = n;
    hit.material = tri.v0.material;
}
