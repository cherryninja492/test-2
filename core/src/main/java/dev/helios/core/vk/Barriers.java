package dev.helios.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static dev.helios.core.vk.HeliosStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Coarse synchronization helpers. The frame graph is short and linear, so full memory barriers
 * between passes cost little and keep the code obviously correct.
 */
public final class Barriers {
    private Barriers() {
    }

    public static VkImageSubresourceRange colorRange(MemoryStack stack) {
        return VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
    }

    /** All prior writes (shaders, transfers, AS builds) visible to all subsequent reads/writes. */
    public static void full(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, barrier, null, null);
        }
    }

    public static void transition(VkCommandBuffer cmd, long image, int oldLayout, int newLayout) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(colorRange(stack));
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, barrier);
        }
    }
}
