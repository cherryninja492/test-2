package dev.helios.fabric;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.helios.core.HeliosRenderer;
import dev.helios.core.vk.ExternalHandles;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectFD.glImportMemoryFdEXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
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
                fragColor = vec4(texture(uColor, t).rgb, 1.0);
                gl_FragDepth = texture(uDepth, t).r;
            }
            """;

    private final int program;
    private final int vao;
    private int colorTexture, depthTexture, colorMemory, depthMemory;
    private long pendingSync;

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
    }

    /** {@code GL_DEVICE_UUID_EXT} of the GPU running this GL context, so Vulkan picks the same one. */
    static byte[] deviceUuid() {
        if (!GL.getCapabilities().GL_EXT_memory_object) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uuid = stack.calloc(GL_UUID_SIZE_EXT);
            glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, uuid);
            byte[] out = new byte[GL_UUID_SIZE_EXT];
            uuid.get(out);
            return out;
        }
    }

    void importTargets(HeliosRenderer.SharedTargets targets) {
        releaseTextures();
        colorMemory = importMemory(targets.color(), targets.win32Handles());
        colorTexture = texture(colorMemory, GL_RGBA8, targets.color());
        depthMemory = importMemory(targets.depth(), targets.win32Handles());
        depthTexture = texture(depthMemory, GL_R32F, targets.depth());
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

        pendingSync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /**
     * Blocks until OpenGL has finished reading the shared images, so Vulkan may overwrite them.
     * (Without imported semaphores this CPU wait is the synchronization point.)
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
            glDeleteMemoryObjectsEXT(colorMemory);
            glDeleteMemoryObjectsEXT(depthMemory);
            colorTexture = depthTexture = colorMemory = depthMemory = 0;
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
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
    }
}
