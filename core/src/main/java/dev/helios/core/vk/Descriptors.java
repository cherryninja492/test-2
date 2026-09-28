package dev.helios.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static dev.helios.core.vk.VkCheck.check;
import static dev.helios.core.vk.HeliosStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** Descriptor layout/pool/write helpers. Binding {@code i} of a layout has type {@code types[i]}. */
public final class Descriptors {
    private Descriptors() {
    }

    public static long createLayout(VulkanContext ctx, int stageFlags, int... types) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(stageFlags);
            }
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            var p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(ctx.device, info, null, p), "vkCreateDescriptorSetLayout");
            return p.get(0);
        }
    }

    public static long createPool(VulkanContext ctx, int maxSets) {
        int[] types = {
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR};
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) sizes.get(i).type(types[i]).descriptorCount(maxSets * 8);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(maxSets)
                    .pPoolSizes(sizes);
            var p = stack.mallocLong(1);
            check(vkCreateDescriptorPool(ctx.device, info, null, p), "vkCreateDescriptorPool");
            return p.get(0);
        }
    }

    public static long allocate(VulkanContext ctx, long pool, long layout) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout));
            var p = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(ctx.device, info, p), "vkAllocateDescriptorSets");
            return p.get(0);
        }
    }

    public static void storageImage(VulkanContext ctx, long set, int binding, GpuImage image) {
        image(ctx, set, binding, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, image.view, VK_NULL_HANDLE);
    }

    public static void sampledImage(VulkanContext ctx, long set, int binding, GpuImage image, long sampler) {
        image(ctx, set, binding, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, image.view, sampler);
    }

    private static void image(VulkanContext ctx, long set, int binding, int type, long view, long sampler) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(view)
                    .sampler(sampler)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(set).dstBinding(binding).descriptorCount(1).descriptorType(type).pImageInfo(info);
            vkUpdateDescriptorSets(ctx.device, write, null);
        }
    }

    public static void buffer(VulkanContext ctx, long set, int binding, int type, GpuBuffer buffer) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.handle).offset(0).range(VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(set).dstBinding(binding).descriptorCount(1).descriptorType(type).pBufferInfo(info);
            vkUpdateDescriptorSets(ctx.device, write, null);
        }
    }

    public static void accelerationStructure(VulkanContext ctx, long set, int binding, long tlas) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType$Default()
                    .pAccelerationStructures(stack.longs(tlas));
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .pNext(as.address())
                    .dstSet(set).dstBinding(binding).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            vkUpdateDescriptorSets(ctx.device, write, null);
        }
    }

    public static long sampler(VulkanContext ctx, int filter) {
        return sampler(ctx, filter, 0f);
    }

    /** Clamp-to-edge sampler; {@code maxLod > 0} enables nearest-mip sampling up to that level. */
    public static long sampler(VulkanContext ctx, int filter, float maxLod) {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(maxLod);
            var p = stack.mallocLong(1);
            check(vkCreateSampler(ctx.device, info, null, p), "vkCreateSampler");
            return p.get(0);
        }
    }
}
