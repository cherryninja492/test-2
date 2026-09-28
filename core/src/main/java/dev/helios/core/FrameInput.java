package dev.helios.core;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Everything the renderer needs from the game for one frame.
 *
 * @param cameraX         world-space camera position
 * @param viewRotation    camera rotation only (Minecraft's "frustum matrix"; no translation)
 * @param projection      OpenGL-convention projection matrix, unjittered
 * @param lightDirection  direction towards the sun (or moon at night), normalized
 * @param lightIntensity  0..1 multiplier for the directional light
 * @param skyColor        linear zenith sky colour
 * @param rain            0..1 rain strength
 * @param cameraUnderwater the camera is inside water (enables in-water absorption from the eye)
 */
public record FrameInput(
        double cameraX, double cameraY, double cameraZ,
        Matrix4f viewRotation,
        Matrix4f projection,
        Vector3f lightDirection,
        float lightIntensity,
        Vector3f skyColor,
        float rain,
        boolean cameraUnderwater) {
}
