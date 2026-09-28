package dev.helios.core.vk;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeliosStackTest {
    @Test
    void holdsAllocationsBeyondLwjglsDefaultStack() {
        try (MemoryStack stack = HeliosStack.stackPush()) {
            // More than the 64 KiB default stack that overflowed during Vulkan setup.
            assertEquals(256 * 1024, stack.malloc(256 * 1024).capacity());
        }
    }
}
