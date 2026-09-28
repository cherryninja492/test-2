package dev.helios.core.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static dev.helios.core.vk.HeliosStack.stackPush;
import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;

/** A binary semaphore exportable to OpenGL ({@code GL_EXT_semaphore}). */
public final class SharedSemaphore implements AutoCloseable {
    public final long handle;
    private final VulkanContext ctx;

    public SharedSemaphore(VulkanContext ctx) {
        this.ctx = ctx;
        try (MemoryStack stack = stackPush()) {
            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(ctx.externalHandleType.vkBit); // semaphore and memory OPAQUE bits share values
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address());
            var p = stack.mallocLong(1);
            check(vkCreateSemaphore(ctx.device, info, null, p), "vkCreateSemaphore(exportable)");
            handle = p.get(0);
        }
    }

    /** POSIX fd (ownership passes to GL) or Win32 NT handle (close after import). */
    public long export() {
        try (MemoryStack stack = stackPush()) {
            if (ctx.externalHandleType == VulkanContext.ExternalHandleType.OPAQUE_WIN32) {
                VkSemaphoreGetWin32HandleInfoKHR info = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .semaphore(handle)
                        .handleType(ctx.externalHandleType.vkBit);
                PointerBuffer p = stack.mallocPointer(1);
                check(vkGetSemaphoreWin32HandleKHR(ctx.device, info, p), "vkGetSemaphoreWin32HandleKHR");
                return p.get(0);
            }
            VkSemaphoreGetFdInfoKHR info = VkSemaphoreGetFdInfoKHR.calloc(stack).sType$Default()
                    .semaphore(handle)
                    .handleType(ctx.externalHandleType.vkBit);
            var p = stack.mallocInt(1);
            check(vkGetSemaphoreFdKHR(ctx.device, info, p), "vkGetSemaphoreFdKHR");
            return p.get(0);
        }
    }

    @Override
    public void close() {
        vkDestroySemaphore(ctx.device, handle, null);
    }
}
