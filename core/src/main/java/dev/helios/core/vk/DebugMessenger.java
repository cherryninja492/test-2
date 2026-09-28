package dev.helios.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;

import java.util.logging.Logger;

import static dev.helios.core.vk.HeliosStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.VK_FALSE;

/** Routes validation layer messages to the log. Only created when validation is enabled. */
final class DebugMessenger {
    private static final Logger LOG = Logger.getLogger("Helios/Vulkan");

    private DebugMessenger() {
    }

    static long create(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                    .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                    .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                    .pfnUserCallback((severity, types, pCallbackData, pUserData) -> {
                        String msg = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData).pMessageString();
                        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) LOG.severe(msg);
                        else LOG.warning(msg);
                        return VK_FALSE;
                    });
            var p = stack.mallocLong(1);
            VkCheck.check(vkCreateDebugUtilsMessengerEXT(instance, info, null, p), "vkCreateDebugUtilsMessengerEXT");
            return p.get(0);
        }
    }

    static void destroy(VkInstance instance, long messenger) {
        vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
    }
}
