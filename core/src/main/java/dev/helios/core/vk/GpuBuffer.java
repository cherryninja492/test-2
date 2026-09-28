package dev.helios.core.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.ByteBuffer;

import static dev.helios.core.vk.VkCheck.check;
import static dev.helios.core.vk.HeliosStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/** A VMA-backed buffer, optionally persistently mapped, always with a device address. */
public final class GpuBuffer implements AutoCloseable {
    public final long handle;
    public final long size;
    private final long allocation;
    private final long allocator;
    private final long mapped;
    private final long deviceAddress;

    private GpuBuffer(VulkanContext ctx, long size, int usage, boolean hostVisible, long alignment) {
        this(ctx, size, usage, hostVisible, alignment, false);
    }

    private GpuBuffer(VulkanContext ctx, long size, int usage, boolean hostVisible, long alignment, boolean readback) {
        this.size = size;
        this.allocator = ctx.allocator;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
            if (hostVisible) {
                allocInfo.flags((readback ? VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                        : VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT) | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            var pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo result = VmaAllocationInfo.calloc(stack);
            check(vmaCreateBufferWithAlignment(allocator, info, allocInfo, alignment, pBuffer, pAlloc, result), "vmaCreateBuffer");
            handle = pBuffer.get(0);
            allocation = pAlloc.get(0);
            mapped = hostVisible ? result.pMappedData() : 0L;

            deviceAddress = vkGetBufferDeviceAddress(ctx.device,
                    VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle));
        }
    }

    public static GpuBuffer deviceLocal(VulkanContext ctx, long size, int usage) {
        return new GpuBuffer(ctx, size, usage, false, 16);
    }

    public static GpuBuffer deviceLocal(VulkanContext ctx, long size, int usage, long alignment) {
        return new GpuBuffer(ctx, size, usage, false, alignment);
    }

    public static GpuBuffer hostVisible(VulkanContext ctx, long size, int usage) {
        return new GpuBuffer(ctx, size, usage, true, 16);
    }

    public static GpuBuffer hostVisible(VulkanContext ctx, long size, int usage, long alignment) {
        return new GpuBuffer(ctx, size, usage, true, alignment);
    }

    /** Host-visible buffer the CPU reads back (cached memory); call {@link #invalidate()} before reading. */
    public static GpuBuffer readback(VulkanContext ctx, long size, int usage) {
        return new GpuBuffer(ctx, size, usage, true, 16, true);
    }

    /** Makes device writes visible to the host on non-coherent memory; no-op otherwise. */
    public void invalidate() {
        vmaInvalidateAllocation(allocator, allocation, 0, VK_WHOLE_SIZE);
    }

    public long deviceAddress() {
        return deviceAddress;
    }

    /** Mapped view of the whole buffer (host-visible buffers only). */
    public ByteBuffer mapped() {
        if (mapped == 0L) throw new IllegalStateException("Buffer is not host visible");
        return MemoryUtil.memByteBuffer(mapped, (int) size);
    }

    public long mappedAddress() {
        return mapped;
    }

    /** Makes host writes visible to the device on non-coherent memory; no-op otherwise. */
    public void flush() {
        vmaFlushAllocation(allocator, allocation, 0, VK_WHOLE_SIZE);
    }

    @Override
    public void close() {
        vmaDestroyBuffer(allocator, handle, allocation);
    }
}
