package dev.helios.core.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import static dev.helios.core.vk.VkCheck.check;
import static dev.helios.core.vk.HeliosStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.vkGetMemoryFdKHR;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A 2D image with a view. Either VMA-allocated (internal render targets) or allocated with
 * exportable dedicated memory so OpenGL can import it via {@code GL_EXT_memory_object}.
 */
public final class GpuImage implements AutoCloseable {
    public final long image;
    public final long view;
    public final int format;
    public final int width;
    public final int height;
    public final int mipLevels;

    private final VulkanContext ctx;
    private final long vmaAllocation;
    private final long exportMemory;
    public final long exportSize;

    private GpuImage(VulkanContext ctx, int width, int height, int format, int usage, boolean exportable, int mipLevels) {
        this.ctx = ctx;
        this.mipLevels = mipLevels;
        this.width = width;
        this.height = height;
        this.format = format;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.set(width, height, 1))
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            var pImage = stack.mallocLong(1);

            if (exportable) {
                VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                        .handleTypes(ctx.externalHandleType.vkBit);
                info.pNext(external.address());
                check(vkCreateImage(ctx.device, info, null, pImage), "vkCreateImage(exportable)");
                image = pImage.get(0);

                VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(ctx.device, image, req);
                VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
                VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default()
                        .pNext(dedicated.address())
                        .handleTypes(ctx.externalHandleType.vkBit);
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .pNext(export.address())
                        .allocationSize(req.size())
                        .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                var pMem = stack.mallocLong(1);
                check(vkAllocateMemory(ctx.device, alloc, null, pMem), "vkAllocateMemory(exportable)");
                exportMemory = pMem.get(0);
                exportSize = req.size();
                check(vkBindImageMemory(ctx.device, image, exportMemory, 0), "vkBindImageMemory");
                vmaAllocation = 0L;
            } else {
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                PointerBuffer pAlloc = stack.mallocPointer(1);
                check(vmaCreateImage(ctx.allocator, info, allocInfo, pImage, pAlloc, null), "vmaCreateImage");
                image = pImage.get(0);
                vmaAllocation = pAlloc.get(0);
                exportMemory = VK_NULL_HANDLE;
                exportSize = 0;
            }

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange(Barriers.colorRange(stack).levelCount(mipLevels));
            var pView = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device, viewInfo, null, pView), "vkCreateImageView");
            view = pView.get(0);
        }
    }

    public static GpuImage create(VulkanContext ctx, int width, int height, int format, int usage) {
        return new GpuImage(ctx, width, height, format, usage, false, 1);
    }

    public static GpuImage createMipmapped(VulkanContext ctx, int width, int height, int format, int usage, int mipLevels) {
        return new GpuImage(ctx, width, height, format, usage, false, mipLevels);
    }

    public static GpuImage createExportable(VulkanContext ctx, int width, int height, int format, int usage) {
        return new GpuImage(ctx, width, height, format, usage, true, 1);
    }

    /**
     * Exports the memory as a POSIX fd (ownership passes to the importer) or a Win32 NT handle
     * (the importer does not take ownership; close it after importing).
     */
    public long exportHandle() {
        if (exportMemory == VK_NULL_HANDLE) throw new IllegalStateException("Image is not exportable");
        try (MemoryStack stack = stackPush()) {
            if (ctx.externalHandleType == VulkanContext.ExternalHandleType.OPAQUE_WIN32) {
                VkMemoryGetWin32HandleInfoKHR info = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .memory(exportMemory)
                        .handleType(ctx.externalHandleType.vkBit);
                PointerBuffer pHandle = stack.mallocPointer(1);
                check(vkGetMemoryWin32HandleKHR(ctx.device, info, pHandle), "vkGetMemoryWin32HandleKHR");
                return pHandle.get(0);
            }
            VkMemoryGetFdInfoKHR info = VkMemoryGetFdInfoKHR.calloc(stack).sType$Default()
                    .memory(exportMemory)
                    .handleType(ctx.externalHandleType.vkBit);
            var pFd = stack.mallocInt(1);
            check(vkGetMemoryFdKHR(ctx.device, info, pFd), "vkGetMemoryFdKHR");
            return pFd.get(0);
        }
    }

    @Override
    public void close() {
        vkDestroyImageView(ctx.device, view, null);
        if (vmaAllocation != 0L) {
            vmaDestroyImage(ctx.allocator, image, vmaAllocation);
        } else {
            vkDestroyImage(ctx.device, image, null);
            vkFreeMemory(ctx.device, exportMemory, null);
        }
    }
}
