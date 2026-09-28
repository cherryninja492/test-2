// Shared by the ray generation shaders. Include after raytracing.glsl.

layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;

layout(location = 0) rayPayloadEXT SurfacePayload hit;
layout(location = 1) rayPayloadEXT uint shadowMissed;

bool traceSurface(vec3 origin, vec3 dir) {
    hit.t = -1.0;
    traceRayEXT(tlas, gl_RayFlagsNoneEXT, MASK_WORLD, 0, 0, 0, origin, 0.0, dir, MAX_DISTANCE, 0);
    return hit.t >= 0.0;
}

bool unoccludedMasked(vec3 origin, vec3 dir, float tMax, uint mask) {
    shadowMissed = 0u;
    traceRayEXT(tlas, gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
                mask, 0, 0, 1, origin, 0.0, dir, tMax, 1);
    return shadowMissed != 0u;
}

bool unoccluded(vec3 origin, vec3 dir) {
    return unoccludedMasked(origin, dir, MAX_DISTANCE, MASK_ALL);
}

float lightIntensity() {
    return frame.lightDir.w * (1.0 - 0.7 * frame.skyColor.w);
}

// Light from the sun/moon arriving at a diffuse surface, without its albedo. Foliage is lit from
// either side with wrapped diffuse so thin plants are never black on the side away from the sun.
vec3 directLight(vec3 pos, vec3 n, uint material, inout uint rng) {
    float intensity = lightIntensity();
    if (intensity <= 0.0) return vec3(0.0);
    vec3 l = sampleCone(frame.lightDir.xyz, SUN_ANGULAR_RADIUS, rand2(rng));
    float ndl = dot(n, l);
    if ((material & MAT_FOLIAGE) != 0u) {
        if (ndl < 0.0) n = -n;
        ndl = 0.45 + 0.55 * abs(ndl);
    }
    if (ndl <= 0.0 || !unoccluded(pos + n * RAY_EPSILON, l)) return vec3(0.0);
    return SUN_COLOR * (intensity * SUN_STRENGTH * ndl);
}
