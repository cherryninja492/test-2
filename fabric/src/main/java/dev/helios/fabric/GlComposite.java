package dev.helios.fabric;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.helios.core.HeliosRenderer;
import dev.helios.core.vk.ExternalHandles;
import dev.helios.core.vk.HeliosStack;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectFD.glImportMemoryFdEXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.EXTSemaphore.glDeleteSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphore.glGenSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphore.glSignalSemaphoreEXT;
import static org.lwjgl.opengl.EXTSemaphore.glWaitSemaphoreEXT;
import static org.lwjgl.opengl.EXTSemaphoreFD.glImportSemaphoreFdEXT;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL32C.*;

/**
 * OpenGL side of the Vulkan interop: imports the renderer's output images through
 * {@code GL_EXT_memory_object} and draws them into the bound framebuffer, writing both colour and
 * depth so vanilla entities, particles, clouds and the hand are depth tested against the ray traced
 * world.
 */
final class GlComposite implements AutoCloseable {
    private static final String VERTEX = """
            #version 150
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D uColor;
            uniform sampler2D uDepth;
            in vec2 uv;
            out vec4 fragColor;
            void main() {
                // Vulkan row 0 is the top of the image; GL texture row 0 is sampled at t = 0.
                vec2 t = vec2(uv.x, 1.0 - uv.y);
                float depth = texture(uDepth, t).r; // opaque surface depth
                if (depth >= 1.0) discard; // sky: keep what vanilla drew (sun, moon, stars, sunsets)
                fragColor = vec4(texture(uColor, t).rgb, 1.0);
                gl_FragDepth = depth;
            }
            """;

    // Drawn over entities under a water surface (they are rendered after the ray traced image):
    // what the water adds, with the entity showing through by the veil's alpha.
    private static final String VEIL_FRAGMENT = """
            #version 150
            uniform sampler2D uVeil;
            uniform sampler2D uDepth;
            uniform sampler2D uSceneDepth;
            uniform float uExposure;
            in vec2 uv;
            out vec4 fragColor;
            vec3 aces(vec3 c) {
                c = mat3(0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777) * c;
                vec3 a = c * (c + 0.0245786) - 0.000090537;
                vec3 b = c * (0.983729 * c + 0.4329510) + 0.238081;
                c = mat3(1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602) * (a / b);
                return clamp(c, 0.0, 1.0);
            }
            void main() {
                vec2 t = vec2(uv.x, 1.0 - uv.y);
                vec2 depth = texture(uDepth, t).rg;       // opaque surface, water surface
                float scene = texture(uSceneDepth, uv).r; // after entities were drawn
                // Only where an entity was drawn in front of the ray traced surface but behind water.
                if (depth.g >= 1.0 || scene >= depth.r - 1.0e-6 || scene <= depth.g) discard;
                vec4 veil = texture(uVeil, t);
                fragColor = vec4(pow(aces(veil.rgb * uExposure), vec3(1.0 / 2.2)), veil.a);
            }
            """;

    private final int program;
    private final int veilProgram;
    private final int vao;
    private final int veilFramebuffer;
    private int colorTexture, depthTexture, veilTexture, colorMemory, depthMemory, veilMemory;
    private boolean pendingRelease;
    private long pendingSync;
    // GPU-side sync with Vulkan (GL_EXT_semaphore); 0 when unavailable.
    private int vulkanDone, glDone;

    GlComposite() {
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object || !(caps.GL_EXT_memory_object_fd || caps.GL_EXT_memory_object_win32)) {
            throw new IllegalStateException("OpenGL driver lacks GL_EXT_memory_object(_fd/_win32), required for Vulkan interop");
        }
        program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
        int previous = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uColor"), 0);
        glUniform1i(glGetUniformLocation(program, "uDepth"), 1);
        glUseProgram(previous);
        vao = glGenVertexArrays();

        veilProgram = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, VEIL_FRAGMENT));
        glUseProgram(veilProgram);
        glUniform1i(glGetUniformLocation(veilProgram, "uVeil"), 0);
        glUniform1i(glGetUniformLocation(veilProgram, "uDepth"), 1);
        glUniform1i(glGetUniformLocation(veilProgram, "uSceneDepth"), 2);
        glUseProgram(previous);
        veilFramebuffer = glGenFramebuffers();
    }

    /** {@code GL_DEVICE_UUID_EXT} of the GPU running this GL context, so Vulkan picks the same one. */
    static byte[] deviceUuid() {
        if (!GL.getCapabilities().GL_EXT_memory_object) return null;
        try (MemoryStack stack = HeliosStack.stackPush()) {
            ByteBuffer uuid = stack.calloc(GL_UUID_SIZE_EXT);
            glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, uuid);
            byte[] out = new byte[GL_UUID_SIZE_EXT];
            uuid.get(out);
            return out;
        }
    }

    /**
     * Imports the renderer's interop semaphores if GL supports {@code GL_EXT_semaphore}; otherwise
     * tells the renderer to fall back to CPU synchronization.
     */
    void importSemaphores(HeliosRenderer renderer) {
        GLCapabilities caps = GL.getCapabilities();
        boolean win32 = caps.GL_EXT_semaphore_win32;
        if (!caps.GL_EXT_semaphore || !(caps.GL_EXT_semaphore_fd || win32)) {
            renderer.disableGpuSync();
            return;
        }
        HeliosRenderer.SharedSemaphores semaphores = renderer.exportSemaphores();
        if (semaphores == null) return;
        vulkanDone = importSemaphore(semaphores.vulkanDone(), semaphores.win32Handles());
        glDone = importSemaphore(semaphores.glDone(), semaphores.win32Handles());
    }

    private static int importSemaphore(long handle, boolean win32) {
        int semaphore = glGenSemaphoresEXT();
        if (win32) {
            glImportSemaphoreWin32HandleEXT(semaphore, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
            ExternalHandles.closeWin32Handle(handle);
        } else {
            glImportSemaphoreFdEXT(semaphore, GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int) handle);
        }
        return semaphore;
    }

    void importTargets(HeliosRenderer.SharedTargets targets) {
        releaseTextures();
        colorMemory = importMemory(targets.color(), targets.win32Handles());
        colorTexture = texture(colorMemory, GL_RGBA8, targets.color());
        depthMemory = importMemory(targets.depth(), targets.win32Handles());
        depthTexture = texture(depthMemory, GL_RG32F, targets.depth());
        veilMemory = importMemory(targets.waterVeil(), targets.win32Handles());
        veilTexture = texture(veilMemory, GL_RGBA16F, targets.waterVeil());
    }

    private static int importMemory(HeliosRenderer.SharedImage image, boolean win32) {
        int memory = glCreateMemoryObjectsEXT();
        if (win32) {
            glImportMemoryWin32HandleEXT(memory, image.allocationSize(), GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, image.handle());
            ExternalHandles.closeWin32Handle(image.handle());
        } else {
            // GL takes ownership of the fd.
            glImportMemoryFdEXT(memory, image.allocationSize(), GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int) image.handle());
        }
        return memory;
    }

    private static int texture(int memory, int internalFormat, HeliosRenderer.SharedImage image) {
        int texture = glGenTextures();
        GlStateManager._bindTexture(texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT);
        glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, internalFormat, image.width(), image.height(), memory, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GlStateManager._bindTexture(0);
        return texture;
    }

    /** Draws the ray traced frame into the currently bound framebuffer (colour + depth). */
    void draw() {
        int[] textures = {colorTexture, depthTexture, veilTexture};
        int[] layouts = {GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT};
        if (vulkanDone != 0) glWaitSemaphoreEXT(vulkanDone, new int[0], textures, layouts);

        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);

        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vao);
        GlStateManager._activeTexture(GL_TEXTURE1);
        GlStateManager._bindTexture(depthTexture);
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._bindTexture(colorTexture);
        GlStateManager._disableBlend();
        GlStateManager._disableCull();
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL_ALWAYS);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);

        glDrawArrays(GL_TRIANGLES, 0, 3);

        GlStateManager._depthFunc(GL_LEQUAL);
        GlStateManager._activeTexture(GL_TEXTURE1);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._bindTexture(0);
        GlStateManager._glBindVertexArray(previousVao);
        GlStateManager._glUseProgram(previousProgram);
        pendingRelease = true;
    }

    /**
     * Draws the water veil over entities beneath water surfaces. Renders into {@code colorTexture}
     * (Minecraft's main colour buffer) through a separate framebuffer, so Minecraft's depth texture
     * can be sampled without a feedback loop. Leaves {@code mainFramebuffer} bound.
     */
    void drawWaterVeil(int mainFramebuffer, int mainColorTexture, int mainDepthTexture, int width, int height,
                       float exposure) {
        if (veilTexture == 0) return;
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);

        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, veilFramebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mainColorTexture, 0);
        GlStateManager._viewport(0, 0, width, height);

        GlStateManager._glUseProgram(veilProgram);
        glUniform1f(glGetUniformLocation(veilProgram, "uExposure"), exposure * 1.3f);
        GlStateManager._glBindVertexArray(vao);
        GlStateManager._activeTexture(GL_TEXTURE2);
        GlStateManager._bindTexture(mainDepthTexture);
        GlStateManager._activeTexture(GL_TEXTURE1);
        GlStateManager._bindTexture(depthTexture);
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._bindTexture(veilTexture);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL_ONE, GL_SRC_ALPHA, GL_ZERO, GL_ONE); // dst * a + veil

        glDrawArrays(GL_TRIANGLES, 0, 3);

        GlStateManager._blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
        GlStateManager._disableBlend();
        GlStateManager._depthMask(true);
        GlStateManager._enableDepthTest();
        GlStateManager._activeTexture(GL_TEXTURE2);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(GL_TEXTURE1);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._bindTexture(0);
        GlStateManager._glBindVertexArray(previousVao);
        GlStateManager._glUseProgram(previousProgram);
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, mainFramebuffer);
    }

    /**
     * Ends GL's use of the shared images for the frame that was drawn (called before the next
     * Vulkan frame): signals Vulkan on the GPU, or records a fence for the CPU fallback.
     */
    void release(HeliosRenderer renderer) {
        if (!pendingRelease) return;
        pendingRelease = false;
        if (glDone != 0) {
            int[] textures = {colorTexture, depthTexture, veilTexture};
            int[] layouts = {GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT};
            glSignalSemaphoreEXT(glDone, new int[0], textures, layouts);
            glFlush();
            renderer.markGlSignaled();
        } else {
            pendingSync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
    }

    /**
     * Without semaphores: blocks until OpenGL has finished reading the shared images, so Vulkan may
     * overwrite them. No-op with semaphores (the GPU orders the work).
     */
    void waitForGl() {
        if (pendingSync != 0L) {
            glClientWaitSync(pendingSync, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            glDeleteSync(pendingSync);
            pendingSync = 0L;
        }
    }

    private void releaseTextures() {
        waitForGl();
        if (colorTexture != 0) {
            glDeleteTextures(colorTexture);
            glDeleteTextures(depthTexture);
            glDeleteTextures(veilTexture);
            glDeleteMemoryObjectsEXT(colorMemory);
            glDeleteMemoryObjectsEXT(depthMemory);
            glDeleteMemoryObjectsEXT(veilMemory);
            colorTexture = depthTexture = veilTexture = colorMemory = depthMemory = veilMemory = 0;
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Helios composite shader: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int p = glCreateProgram();
        glAttachShader(p, vertex);
        glAttachShader(p, fragment);
        glLinkProgram(p);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Helios composite program: " + glGetProgramInfoLog(p));
        }
        return p;
    }

    @Override
    public void close() {
        releaseTextures();
        if (vulkanDone != 0) {
            glDeleteSemaphoresEXT(vulkanDone);
            glDeleteSemaphoresEXT(glDone);
            vulkanDone = glDone = 0;
        }
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
        glDeleteProgram(veilProgram);
        glDeleteFramebuffers(veilFramebuffer);
    }
}
