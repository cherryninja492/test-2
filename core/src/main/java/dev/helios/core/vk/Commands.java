package dev.helios.core.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.function.Consumer;

import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Command buffer allocation and synchronous submission. */
public final class Commands {
    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L;

    private Commands() {
    }

    public static VkCommandBuffer allocate(VulkanContext ctx) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(ctx.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer p = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(ctx.device, info, p), "vkAllocateCommandBuffers");
            return new VkCommandBuffer(p.get(0), ctx.device);
        }
    }

    public static long createFence(VulkanContext ctx) {
        try (MemoryStack stack = stackPush()) {
            var p = stack.mallocLong(1);
            check(vkCreateFence(ctx.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, p), "vkCreateFence");
            return p.get(0);
        }
    }

    public static void begin(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
            check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
        }
    }

    /** Ends, submits and blocks until the GPU has finished {@code cmd}. */
    public static void submitAndWait(VulkanContext ctx, VkCommandBuffer cmd, long fence) {
        try (MemoryStack stack = stackPush()) {
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(ctx.queue, submit, fence), "vkQueueSubmit");
            check(vkWaitForFences(ctx.device, fence, true, FENCE_TIMEOUT_NS), "vkWaitForFences");
            check(vkResetFences(ctx.device, fence), "vkResetFences");
        }
    }

    /** Records and runs a one-off command buffer (uploads, layout transitions). */
    public static void immediate(VulkanContext ctx, Consumer<VkCommandBuffer> recorder) {
        VkCommandBuffer cmd = allocate(ctx);
        long fence = createFence(ctx);
        try {
            begin(cmd);
            recorder.accept(cmd);
            submitAndWait(ctx, cmd, fence);
        } finally {
            vkDestroyFence(ctx.device, fence, null);
            vkFreeCommandBuffers(ctx.device, ctx.commandPool, cmd);
        }
    }
}
