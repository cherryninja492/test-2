package dev.helios.core.vk;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

public final class VkCheck {
    private VkCheck() {
    }

    public static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new VulkanException(what + " failed: VkResult " + result);
        }
    }

    public static final class VulkanException extends RuntimeException {
        public VulkanException(String message) {
            super(message);
        }
    }
}
