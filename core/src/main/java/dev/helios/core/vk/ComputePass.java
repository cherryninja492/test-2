package dev.helios.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A compute shader with one descriptor set layout, push constants, and several pre-allocated
 * descriptor sets (for ping-pong variants). Dispatches in 8x8 tiles.
 */
public final class ComputePass implements AutoCloseable {
    private final VulkanContext ctx;
    private final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[] sets;
    private final int pushSize;

    public ComputePass(VulkanContext ctx, ShaderCompiler compiler, long pool, String shader, int pushSize,
                       int setCount, int... bindingTypes) {
        this.ctx = ctx;
        this.pushSize = pushSize;
        setLayout = Descriptors.createLayout(ctx, VK_SHADER_STAGE_COMPUTE_BIT, bindingTypes);
        long module = Shaders.module(ctx, compiler, shader);
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout));
            if (pushSize > 0) {
                layoutInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushSize));
            }
            var p = stack.mallocLong(1);
            check(vkCreatePipelineLayout(ctx.device, layoutInfo, null, p), "vkCreatePipelineLayout");
            pipelineLayout = p.get(0);

            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                    .layout(pipelineLayout);
            info.stage().sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module)
                    .pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(ctx.device, VK_NULL_HANDLE, info, null, p), "vkCreateComputePipelines(" + shader + ")");
            pipeline = p.get(0);
        } finally {
            vkDestroyShaderModule(ctx.device, module, null);
        }
        sets = new long[setCount];
        for (int i = 0; i < setCount; i++) sets[i] = Descriptors.allocate(ctx, pool, setLayout);
    }

    public long set(int index) {
        return sets[index];
    }

    public void dispatch(VkCommandBuffer cmd, int setIndex, ByteBuffer push, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(sets[setIndex]), null);
            if (pushSize > 0) vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    @Override
    public void close() {
        vkDestroyPipeline(ctx.device, pipeline, null);
        vkDestroyPipelineLayout(ctx.device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(ctx.device, setLayout, null);
    }
}
