#version 460
#extension GL_EXT_ray_tracing : require
#include "raytracing.glsl"

layout(location = 0) rayPayloadInEXT SurfacePayload hit;

void main() {
    hit.t = -1.0;
}
