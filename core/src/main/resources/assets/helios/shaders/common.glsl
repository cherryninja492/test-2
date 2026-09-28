// Shared by all Helios shaders: constants, material bits, RNG, sampling and colour helpers.

const float PI = 3.14159265359;

// Material bits (see dev.helios.core.geometry.Materials)
const uint MAT_EMISSION_MASK = 0xFu;
const uint MAT_CUTOUT = 1u << 4;
const uint MAT_WATER = 1u << 5;
const uint MAT_BLOCK_LIGHT_SHIFT = 8u; // bits 8-11: Minecraft block light level at the surface
const uint MAT_SURFACE_SHIFT = 12u;    // bits 12-15: surface type
const uint SURFACE_DIFFUSE = 0u;
const uint SURFACE_GLASS = 1u;         // refractive dielectric (glass, ice)
const uint SURFACE_METAL = 2u;         // glossy metal, tinted reflections
const uint SURFACE_POLISHED = 3u;      // diffuse base with a glossy clear coat (gems, polished stone)

uint surfaceOf(uint material) {
    return (material >> MAT_SURFACE_SHIFT) & 0xFu;
}

// Normal/depth images store this distance for sky pixels.
const float SKY_DEPTH = 1.0e6;

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 rgbToYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b,
                0.5 * c.r - 0.5 * c.b,
               -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 yCoCgToRgb(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// PCG hash (Jarzynski & Olano 2020)
uint pcgHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

uint initRng(uvec2 pixel, uint frame) {
    return pcgHash(pixel.x + pcgHash(pixel.y + pcgHash(frame)));
}

float rand(inout uint state) {
    state = pcgHash(state);
    return float(state) * (1.0 / 4294967296.0);
}

vec2 rand2(inout uint state) {
    return vec2(rand(state), rand(state));
}

// Orthonormal basis from a unit vector (Duff et al. 2017)
void basis(vec3 n, out vec3 t, out vec3 b) {
    float s = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (s + n.z);
    float c = n.x * n.y * a;
    t = vec3(1.0 + s * n.x * n.x * a, s * c, -s * n.x);
    b = vec3(c, s + n.y * n.y * a, -n.y);
}

vec3 sampleCosineHemisphere(vec3 n, vec2 u) {
    float r = sqrt(u.x);
    float phi = 2.0 * PI * u.y;
    vec3 t, b;
    basis(n, t, b);
    return normalize(t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(max(0.0, 1.0 - u.x)));
}

vec3 sampleCone(vec3 dir, float angle, vec2 u) {
    float cosTheta = mix(1.0, cos(angle), u.x);
    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
    float phi = 2.0 * PI * u.y;
    vec3 t, b;
    basis(dir, t, b);
    return normalize(t * (sinTheta * cos(phi)) + b * (sinTheta * sin(phi)) + dir * cosTheta);
}

float fresnelSchlick(float cosTheta, float f0) {
    return f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);
}
