package dev.helios.core.math;

import org.joml.Vector3f;

/** Derives the directional light from Minecraft's celestial angle. */
public final class Lighting {
    private static final float MOON_INTENSITY = 0.06f;

    private Lighting() {
    }

    /**
     * @param sunAngle Minecraft's {@code Level#getSunAngle} in radians (0 = noon, sun rises in +X)
     * @param outDir   receives the direction towards the active light source
     * @return light intensity in [0, 1]
     */
    public static float directionalLight(float sunAngle, float rain, Vector3f outDir) {
        float sx = (float) -Math.sin(sunAngle);
        float sy = (float) Math.cos(sunAngle);
        // Slight tilt so the sun is never exactly axis aligned (avoids perfectly flat shadow edges).
        Vector3f sun = new Vector3f(sx, sy, 0.15f).normalize();
        float intensity;
        if (sun.y > -0.05f) {
            outDir.set(sun);
            intensity = smoothstep(-0.05f, 0.15f, sun.y);
        } else {
            outDir.set(sun).negate();
            intensity = MOON_INTENSITY * smoothstep(-0.05f, 0.15f, outDir.y);
        }
        return intensity * (1f - 0.8f * rain);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
