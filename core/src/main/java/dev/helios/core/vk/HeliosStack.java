package dev.helios.core.vk;

import org.lwjgl.system.MemoryStack;

/**
 * Helios' own per-thread LWJGL stack. Minecraft's render thread uses LWJGL's default 64 KiB
 * {@link MemoryStack}, which is too small for Vulkan setup (device queries, pipeline creation), so
 * Helios never allocates from it.
 */
public final class HeliosStack {
    private static final int SIZE = 1 << 20;
    private static final ThreadLocal<MemoryStack> STACK = ThreadLocal.withInitial(() -> MemoryStack.create(SIZE));

    private HeliosStack() {
    }

    /** Drop-in replacement for {@link MemoryStack#stackPush()}. */
    public static MemoryStack stackPush() {
        return STACK.get().push();
    }
}
