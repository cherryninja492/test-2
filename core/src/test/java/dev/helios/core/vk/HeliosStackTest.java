package dev.helios.core.vk;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

class HeliosStackTest {
    private static final int BEYOND_DEFAULT = 256 * 1024; // LWJGL's default thread stack is 64 KiB

    @Test
    void privateStackHoldsLargeAllocations() {
        try (MemoryStack stack = HeliosStack.stackPush()) {
            assertEquals(BEYOND_DEFAULT, stack.malloc(BEYOND_DEFAULT).capacity());
        }
    }

    @Test
    void helperThreadEnlargesLwjglDefaultStack() {
        // This is what LWJGL's VkInstance constructor does internally (MemoryStack.stackPush()).
        int capacity = HeliosStack.callWithLargeStack("test", () -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                return stack.malloc(BEYOND_DEFAULT).capacity();
            }
        });
        assertEquals(BEYOND_DEFAULT, capacity);
    }

    @Test
    void defaultStackIsStillSmallElsewhere() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertThrows(OutOfMemoryError.class, () -> stack.malloc(BEYOND_DEFAULT));
        }
    }

    @Test
    void helperThreadRethrowsExceptions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HeliosStack.callWithLargeStack("test", () -> {
                    throw new IllegalArgumentException("boom");
                }));
        assertEquals("boom", e.getMessage());
    }
}
