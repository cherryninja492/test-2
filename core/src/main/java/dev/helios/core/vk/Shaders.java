package dev.helios.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;

import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;

public final class Shaders {
    private Shaders() {
    }

    public static long module(VulkanContext ctx, ShaderCompiler compiler, String name) {
        ByteBuffer spirv = compiler.compile(name);
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            var p = stack.mallocLong(1);
            check(vkCreateShaderModule(ctx.device, info, null, p), "vkCreateShaderModule(" + name + ")");
            return p.get(0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }
}
