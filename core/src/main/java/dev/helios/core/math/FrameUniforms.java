package dev.helios.core.math;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Builds the per-frame uniform block ({@code FrameUBO} in {@code common.glsl}, std140).
 *
 * <p>All matrices are relative to an integer "anchor" (the camera's block position this frame) so
 * that floats keep sub-millimetre precision far from the world origin. The TLAS instance
 * transforms use the same anchor. The previous frame's view-projection is re-expressed relative to
 * the current anchor so motion vectors are correct when the anchor moves.
 */
public final class FrameUniforms {
    public static final int SIZE = 3 * 64 + 7 * 16;

    public static final int FLAG_ACCUMULATE_RESET = 1;
    public static final int FLAG_CAMERA_UNDERWATER = 2;

    private final Matrix4f viewProj = new Matrix4f();
    private final Matrix4f invViewProj = new Matrix4f();
    private final Matrix4f prevViewProj = new Matrix4f();
    private final Vector3f cameraRel = new Vector3f();
    private final Vector3f prevCameraRel = new Vector3f();

    private long anchorX, anchorY, anchorZ;
    private double camX, camY, camZ;

    private boolean hasPrevious;
    private final Matrix4f prevView = new Matrix4f();
    private final Matrix4f prevProj = new Matrix4f();
    private double prevCamX, prevCamY, prevCamZ;

    /** Advances to a new frame. Call once per frame before {@link #write}. */
    public void update(double cameraX, double cameraY, double cameraZ, Matrix4f viewRotation, Matrix4f projection) {
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        anchorX = (long) Math.floor(cameraX);
        anchorY = (long) Math.floor(cameraY);
        anchorZ = (long) Math.floor(cameraZ);
        cameraRel.set((float) (cameraX - anchorX), (float) (cameraY - anchorY), (float) (cameraZ - anchorZ));

        viewProj.set(projection).mul(viewRotation).translate(-cameraRel.x, -cameraRel.y, -cameraRel.z);
        viewProj.invert(invViewProj);

        if (!hasPrevious) {
            prevView.set(viewRotation);
            prevProj.set(projection);
            prevCamX = cameraX;
            prevCamY = cameraY;
            prevCamZ = cameraZ;
            hasPrevious = true;
        }
        prevViewProj.set(prevProj).mul(prevView).translate(
                (float) (anchorX - prevCamX), (float) (anchorY - prevCamY), (float) (anchorZ - prevCamZ));
        prevCameraRel.set((float) (prevCamX - anchorX), (float) (prevCamY - anchorY), (float) (prevCamZ - anchorZ));

        prevView.set(viewRotation);
        prevProj.set(projection);
        prevCamX = cameraX;
        prevCamY = cameraY;
        prevCamZ = cameraZ;
    }

    /** Forget history (teleport, dimension change): next frame has zero motion. */
    public void reset() {
        hasPrevious = false;
    }

    public long anchorX() {
        return anchorX;
    }

    public long anchorY() {
        return anchorY;
    }

    public long anchorZ() {
        return anchorZ;
    }

    public Vector3f cameraRelative() {
        return cameraRel;
    }

    public Vector3f prevCameraRelative() {
        return prevCameraRel;
    }

    public Matrix4f viewProj() {
        return viewProj;
    }

    public Matrix4f invViewProj() {
        return invViewProj;
    }

    public Matrix4f prevViewProj() {
        return prevViewProj;
    }

    public double cameraX() {
        return camX;
    }

    public double cameraY() {
        return camY;
    }

    public double cameraZ() {
        return camZ;
    }

    public void write(ByteBuffer dst, float[] jitterPx, int renderWidth, int renderHeight,
                      Vector3f lightDir, float lightIntensity, Vector3f skyColor, float rain,
                      int frameIndex, int maxBounces, int samplesPerPixel, int flags, float pixelAngle) {
        ByteBuffer b = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        putMatrix(b, invViewProj);
        putMatrix(b, viewProj);
        putMatrix(b, prevViewProj);
        b.putFloat(cameraRel.x).putFloat(cameraRel.y).putFloat(cameraRel.z).putFloat(0f);
        b.putFloat(lightDir.x).putFloat(lightDir.y).putFloat(lightDir.z).putFloat(lightIntensity);
        b.putFloat(skyColor.x).putFloat(skyColor.y).putFloat(skyColor.z).putFloat(rain);
        b.putFloat(jitterPx[0]).putFloat(jitterPx[1]).putFloat(renderWidth).putFloat(renderHeight);
        b.putInt(frameIndex).putInt(maxBounces).putInt(samplesPerPixel).putInt(flags);
        b.putFloat(prevCameraRel.x).putFloat(prevCameraRel.y).putFloat(prevCameraRel.z).putFloat(0f);
        b.putFloat(pixelAngle).putFloat(0f).putFloat(0f).putFloat(0f);
    }

    /** Angular size of one render pixel (radians), for texture LOD selection. */
    public static float pixelAngle(Matrix4f projection, int renderHeight) {
        // projection.m11() = 1 / tan(fovY / 2)
        return 2f / (projection.m11() * renderHeight);
    }

    // Column-major, as std140 expects. Goes through a float[] so heap and direct buffers both work.
    private final float[] scratch = new float[16];

    private void putMatrix(ByteBuffer b, Matrix4f m) {
        m.get(scratch);
        for (float f : scratch) b.putFloat(f);
    }
}
