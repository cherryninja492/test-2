package dev.helios.core.rt;

import dev.helios.core.vk.Descriptors;
import dev.helios.core.vk.GpuBuffer;
import dev.helios.core.vk.ShaderCompiler;
import dev.helios.core.vk.Shaders;
import dev.helios.core.vk.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The path tracing pipeline: one ray generation shader, a primary + shadow miss shader and a
 * single triangle hit group (closest hit + alpha-testing any hit), plus its shader binding table.
 */
public final class RayTracingPipeline implements AutoCloseable {
    public static final int BINDING_TLAS = 0;
    public static final int BINDING_FRAME = 1;
    public static final int BINDING_GEOMETRY_TABLE = 2;
    public static final int BINDING_ATLAS = 3;
    public static final int BINDING_OUT_ILLUMINATION = 4;
    public static final int BINDING_OUT_ALBEDO = 5;
    public static final int BINDING_OUT_NORMAL_DEPTH = 6;
    public static final int BINDING_OUT_MOTION = 7;
    public static final int BINDING_OUT_DEPTH = 8;

    private static final int STAGES = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
            | VK_SHADER_STAGE_ANY_HIT_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR;

    private final VulkanContext ctx;
    public final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final GpuBuffer sbt;
    private final long raygenStride, missStride, hitStride;
    private final long missOffset, hitOffset;

    public RayTracingPipeline(VulkanContext ctx, ShaderCompiler compiler) {
        this.ctx = ctx;
        setLayout = Descriptors.createLayout(ctx, STAGES,
                VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,
                VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

        String[] names = {"pathtrace.rgen", "primary.rmiss", "shadow.rmiss", "surface.rchit", "alphatest.rahit"};
        int[] stages = {VK_SHADER_STAGE_RAYGEN_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR,
                VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, VK_SHADER_STAGE_ANY_HIT_BIT_KHR};
        long[] modules = new long[names.length];
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < names.length; i++) modules[i] = Shaders.module(ctx, compiler, names[i]);

            var p = stack.mallocLong(1);
            check(vkCreatePipelineLayout(ctx.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)), null, p), "vkCreatePipelineLayout(rt)");
            pipelineLayout = p.get(0);

            VkPipelineShaderStageCreateInfo.Buffer stageInfos = VkPipelineShaderStageCreateInfo.calloc(names.length, stack);
            for (int i = 0; i < names.length; i++) {
                stageInfos.get(i).sType$Default().stage(stages[i]).module(modules[i]).pName(stack.UTF8("main"));
            }

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(4, stack);
            general(groups.get(0), 0);
            general(groups.get(1), 1);
            general(groups.get(2), 2);
            groups.get(3).sType$Default()
                    .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                    .generalShader(VK_SHADER_UNUSED_KHR)
                    .closestHitShader(3)
                    .anyHitShader(4)
                    .intersectionShader(VK_SHADER_UNUSED_KHR);

            VkRayTracingPipelineCreateInfoKHR.Buffer info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack).sType$Default()
                    .pStages(stageInfos)
                    .pGroups(groups)
                    .maxPipelineRayRecursionDepth(1)
                    .layout(pipelineLayout);
            check(vkCreateRayTracingPipelinesKHR(ctx.device, VK_NULL_HANDLE, VK_NULL_HANDLE, info, null, p),
                    "vkCreateRayTracingPipelinesKHR");
            pipeline = p.get(0);
        } finally {
            for (long m : modules) if (m != 0) vkDestroyShaderModule(ctx.device, m, null);
        }

        // Shader binding table: [raygen][miss, shadow miss][hit], each region base-aligned.
        VulkanContext.RayTracingProperties rt = ctx.rtProperties;
        int handleSize = rt.shaderGroupHandleSize();
        long handleStride = align(handleSize, rt.shaderGroupHandleAlignment());
        raygenStride = align(handleStride, rt.shaderGroupBaseAlignment());
        missStride = handleStride;
        hitStride = handleStride;
        missOffset = raygenStride;
        hitOffset = missOffset + align(2 * missStride, rt.shaderGroupBaseAlignment());
        long sbtSize = hitOffset + align(hitStride, rt.shaderGroupBaseAlignment());

        ByteBuffer handles = MemoryUtil.memAlloc(4 * handleSize);
        try {
            check(vkGetRayTracingShaderGroupHandlesKHR(ctx.device, pipeline, 0, 4, handles),
                    "vkGetRayTracingShaderGroupHandlesKHR");
            sbt = GpuBuffer.hostVisible(ctx, sbtSize, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                    rt.shaderGroupBaseAlignment());
            ByteBuffer dst = sbt.mapped();
            copyHandle(handles, 0, dst, 0, handleSize);
            copyHandle(handles, 1, dst, missOffset, handleSize);
            copyHandle(handles, 2, dst, missOffset + missStride, handleSize);
            copyHandle(handles, 3, dst, hitOffset, handleSize);
            sbt.flush();
        } finally {
            MemoryUtil.memFree(handles);
        }
    }

    private static void general(VkRayTracingShaderGroupCreateInfoKHR group, int shader) {
        group.sType$Default()
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(shader)
                .closestHitShader(VK_SHADER_UNUSED_KHR)
                .anyHitShader(VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);
    }

    private static void copyHandle(ByteBuffer handles, int group, ByteBuffer dst, long offset, int handleSize) {
        for (int i = 0; i < handleSize; i++) dst.put((int) offset + i, handles.get(group * handleSize + i));
    }

    private static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }

    public void trace(VkCommandBuffer cmd, long descriptorSet, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            long base = sbt.deviceAddress();
            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(base).stride(raygenStride).size(raygenStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(base + missOffset).stride(missStride).size(2 * missStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(base + hitOffset).stride(hitStride).size(hitStride);
            VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            vkCmdTraceRaysKHR(cmd, raygen, miss, hit, callable, width, height, 1);
        }
    }

    @Override
    public void close() {
        sbt.close();
        vkDestroyPipeline(ctx.device, pipeline, null);
        vkDestroyPipelineLayout(ctx.device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(ctx.device, setLayout, null);
    }
}
