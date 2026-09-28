package dev.helios.core.math;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class FrameUniformsTest {
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);

    /** Projects a world-space point with a camera-relative matrix whose anchor is (ax, ay, az). */
    private static Vector4f project(Matrix4f m, double wx, double wy, double wz, long ax, long ay, long az) {
        return m.transform(new Vector4f((float) (wx - ax), (float) (wy - ay), (float) (wz - az), 1f));
    }

    @Test
    void staticCameraHasZeroMotion() {
        FrameUniforms u = new FrameUniforms();
        Matrix4f view = new Matrix4f().rotateY(0.3f);
        u.update(100.25, 64.5, -20.75, view, PROJ);
        u.update(100.25, 64.5, -20.75, view, PROJ);

        Vector4f cur = project(u.viewProj(), 103, 65, -30, u.anchorX(), u.anchorY(), u.anchorZ());
        Vector4f prev = project(u.prevViewProj(), 103, 65, -30, u.anchorX(), u.anchorY(), u.anchorZ());
        assertEquals(cur.x / cur.w, prev.x / prev.w, 1e-5);
        assertEquals(cur.y / cur.w, prev.y / prev.w, 1e-5);
    }

    @Test
    void previousMatrixIsReexpressedAcrossAnchorChanges() {
        FrameUniforms u = new FrameUniforms();
        Matrix4f view = new Matrix4f().rotateY(-0.7f).rotateX(0.2f);
        double[] target = {1_000_010.5, 70.25, -2_000_005.5};

        u.update(1_000_000.9, 64.0, -2_000_000.1, view, PROJ);
        Vector4f first = project(u.viewProj(), target[0], target[1], target[2], u.anchorX(), u.anchorY(), u.anchorZ());

        // Camera crosses a block boundary, so the anchor changes.
        u.update(1_000_001.3, 64.0, -2_000_000.1, view, PROJ);
        assertEquals(1_000_001L, u.anchorX());
        Vector4f prev = project(u.prevViewProj(), target[0], target[1], target[2], u.anchorX(), u.anchorY(), u.anchorZ());

        assertEquals(first.x / first.w, prev.x / prev.w, 1e-4);
        assertEquals(first.y / first.w, prev.y / prev.w, 1e-4);
        assertEquals(first.z / first.w, prev.z / prev.w, 1e-4);
    }

    @Test
    void cameraPositionIsFractionalPart() {
        FrameUniforms u = new FrameUniforms();
        u.update(-3.25, 70.5, 12.0, new Matrix4f(), PROJ);
        assertEquals(-4, u.anchorX());
        assertEquals(new Vector3f(0.75f, 0.5f, 0f), u.cameraRelative());
    }

    @Test
    void writesStd140Layout() {
        FrameUniforms u = new FrameUniforms();
        u.update(0.5, 0.5, 0.5, new Matrix4f(), PROJ);
        u.setLightGrid(-96, -64, 32, true);
        ByteBuffer b = ByteBuffer.allocate(FrameUniforms.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        u.write(b, new float[] {0.25f, -0.125f}, 1280, 720, new Vector3f(0, 1, 0), 0.8f,
                new Vector3f(0.2f, 0.4f, 1f), 0.5f, 42, 3, 2, FrameUniforms.FLAG_ACCUMULATE_RESET, 0.001f);
        assertEquals(320, FrameUniforms.SIZE);
        assertEquals(0.5f, b.getFloat(192));       // cameraPos.x
        assertEquals(0.8f, b.getFloat(208 + 12));  // lightDir.w
        assertEquals(0.5f, b.getFloat(224 + 12));  // skyColor.w (rain)
        assertEquals(0.25f, b.getFloat(240));      // jitter.x
        assertEquals(720f, b.getFloat(252));       // render height
        assertEquals(42, b.getInt(256));
        assertEquals(3, b.getInt(260));
        assertEquals(FrameUniforms.FLAG_ACCUMULATE_RESET, b.getInt(268));
        assertEquals(0.5f, b.getFloat(272));       // prevCameraPos.x (first frame: same as current)
        assertEquals(0.001f, b.getFloat(288));     // params.x pixel angle
        assertEquals(-96, b.getInt(304));          // lightGrid.x relative to anchor (0)
        assertEquals(32, b.getInt(312));
        assertEquals(1, b.getInt(316));            // point lights enabled
    }

    @Test
    void previousCameraIsRelativeToCurrentAnchor() {
        FrameUniforms u = new FrameUniforms();
        u.update(10.5, 64.25, -3.75, new Matrix4f(), PROJ);
        u.update(11.25, 64.25, -3.75, new Matrix4f(), PROJ);
        // anchor x is now 11, previous camera x was 10.5
        assertEquals(-0.5f, u.prevCameraRelative().x, 1e-6);
        assertEquals(0.25f, u.cameraRelative().x, 1e-6);
    }

    @Test
    void pixelAngleMatchesFieldOfView() {
        float angle = FrameUniforms.pixelAngle(PROJ, 1000);
        // 70 degree vertical FOV over 1000 pixels is about 0.0014 rad per pixel near the centre
        assertEquals(2 * Math.tan(Math.toRadians(35)) / 1000, angle, 1e-6);
    }

    @Test
    void lightingSwitchesToMoonAtNight() {
        Vector3f dir = new Vector3f();
        float noon = Lighting.directionalLight(0f, 0f, dir);
        assertEquals(1f, noon, 1e-3);
        assertTrue(dir.y > 0.9f);

        float midnight = Lighting.directionalLight((float) Math.PI, 0f, dir);
        assertTrue(dir.y > 0.9f, "moon is overhead at midnight");
        assertTrue(midnight > 0f && midnight < 0.1f);

        float rainyNoon = Lighting.directionalLight(0f, 1f, dir);
        assertTrue(rainyNoon < noon);
    }
}
