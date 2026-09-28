package dev.helios.core.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;

import static dev.helios.core.vk.VkCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Owns the Vulkan instance, the ray tracing capable device, one graphics+compute queue and the VMA
 * allocator. The device is chosen to match the OpenGL device (by UUID) so memory can be shared with
 * Minecraft's GL context.
 */
public final class VulkanContext implements AutoCloseable {
    public enum ExternalHandleType {
        OPAQUE_FD(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT),
        OPAQUE_WIN32(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);

        public final int vkBit;

        ExternalHandleType(int vkBit) {
            this.vkBit = vkBit;
        }
    }

    public record RayTracingProperties(int shaderGroupHandleSize, int shaderGroupBaseAlignment,
                                       int shaderGroupHandleAlignment, int maxRecursionDepth,
                                       int minScratchOffsetAlignment) {
    }

    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;
    public final VkDevice device;
    public final VkQueue queue;
    public final int queueFamily;
    public final long commandPool;
    public final long allocator;
    public final String deviceName;
    public final RayTracingProperties rtProperties;
    public final ExternalHandleType externalHandleType;
    private final long debugMessenger;

    private static final List<String> REQUIRED_DEVICE_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);

    /**
     * @param preferredDeviceUuid  {@code GL_DEVICE_UUID_EXT} of the GL context, or null
     * @param extraInstanceExts    instance extensions requested by e.g. DLSS
     * @param extraDeviceExts      device extensions requested by e.g. DLSS (skipped if unsupported)
     */
    public VulkanContext(byte[] preferredDeviceUuid, Collection<String> extraInstanceExts,
                         Collection<String> extraDeviceExts, boolean validation) {
        externalHandleType = Platform.get() == Platform.WINDOWS ? ExternalHandleType.OPAQUE_WIN32 : ExternalHandleType.OPAQUE_FD;
        try (MemoryStack stack = stackPush()) {
            instance = createInstance(stack, extraInstanceExts, validation);
            debugMessenger = validation ? DebugMessenger.create(instance) : VK_NULL_HANDLE;

            String externalMemoryExt = externalHandleType == ExternalHandleType.OPAQUE_WIN32
                    ? VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME : VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
            List<String> required = new ArrayList<>(REQUIRED_DEVICE_EXTENSIONS);
            required.add(externalMemoryExt);

            physicalDevice = pickPhysicalDevice(stack, required, preferredDeviceUuid);

            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            deviceName = props.deviceNameString();

            queueFamily = findQueueFamily(stack, physicalDevice);

            Set<String> available = deviceExtensions(stack, physicalDevice);
            List<String> enabled = new ArrayList<>(required);
            for (String ext : extraDeviceExts) {
                if (available.contains(ext) && !enabled.contains(ext)) enabled.add(ext);
            }
            device = createDevice(stack, enabled);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            queue = new VkQueue(pQueue.get(0), device);

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);
            var pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device, poolInfo, null, pPool), "vkCreateCommandPool");
            commandPool = pPool.get(0);

            allocator = createAllocator(stack);
            rtProperties = queryRtProperties(stack);
        }
    }

    private static VkInstance createInstance(MemoryStack stack, Collection<String> extraExts, boolean validation) {
        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("Minecraft"))
                .pEngineName(stack.UTF8("Helios"))
                .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                .apiVersion(VK_API_VERSION_1_2);

        Set<String> exts = new LinkedHashSet<>(extraExts);
        if (validation) exts.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        PointerBuffer pExts = stack.mallocPointer(exts.size());
        exts.forEach(e -> pExts.put(stack.UTF8(e)));
        pExts.flip();

        PointerBuffer pLayers = null;
        if (validation) {
            pLayers = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
        }

        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(pExts)
                .ppEnabledLayerNames(pLayers);
        PointerBuffer pInstance = stack.mallocPointer(1);
        check(vkCreateInstance(info, null, pInstance), "vkCreateInstance");
        return new VkInstance(pInstance.get(0), info);
    }

    private VkPhysicalDevice pickPhysicalDevice(MemoryStack stack, List<String> required, byte[] preferredUuid) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, count, null);
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, handles);

        VkPhysicalDevice best = null;
        int bestScore = -1;
        StringBuilder rejected = new StringBuilder();
        for (int i = 0; i < handles.capacity(); i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(handles.get(i), instance);
            VkPhysicalDeviceIDProperties idProps = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(idProps);
            vkGetPhysicalDeviceProperties2(pd, props2);
            VkPhysicalDeviceProperties props = props2.properties();

            Set<String> exts = deviceExtensions(stack, pd);
            List<String> missing = required.stream().filter(e -> !exts.contains(e)).toList();
            if (VK_API_VERSION_MINOR(props.apiVersion()) < 2 && VK_API_VERSION_MAJOR(props.apiVersion()) == 1) {
                rejected.append(props.deviceNameString()).append(": Vulkan 1.2 not supported; ");
                continue;
            }
            if (!missing.isEmpty()) {
                rejected.append(props.deviceNameString()).append(": missing ").append(missing).append("; ");
                continue;
            }

            int score = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 10 : 1;
            if (preferredUuid != null && Arrays.equals(preferredUuid, bytes(idProps.deviceUUID()))) {
                score += 1000;
            }
            if (score > bestScore) {
                bestScore = score;
                best = pd;
            }
        }
        if (best == null) {
            throw new VkCheck.VulkanException("No GPU with hardware ray tracing (VK_KHR_ray_tracing_pipeline) found. " + rejected);
        }
        return best;
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    static Set<String> deviceExtensions(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, null);
        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, count, props);
        Set<String> names = new HashSet<>();
        for (VkExtensionProperties p : props) names.add(p.extensionNameString());
        return names;
    }

    private static int findQueueFamily(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, families);
        int want = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & want) == want) return i;
        }
        throw new VkCheck.VulkanException("No graphics+compute queue family");
    }

    private VkDevice createDevice(MemoryStack stack, List<String> extensions) {
        VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(stack.floats(1f));

        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                .sType$Default()
                .rayTracingPipeline(true);
        VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                .sType$Default()
                .pNext(rtFeatures.address())
                .accelerationStructure(true);
        VkPhysicalDeviceVulkan12Features vk12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default()
                .pNext(asFeatures.address())
                .bufferDeviceAddress(true)
                .scalarBlockLayout(true);
        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(vk12.address());
        features2.features()
                .shaderStorageImageWriteWithoutFormat(true)
                .shaderStorageImageExtendedFormats(true)
                .samplerAnisotropy(false);

        PointerBuffer pExts = stack.mallocPointer(extensions.size());
        extensions.forEach(e -> pExts.put(stack.UTF8(e)));
        pExts.flip();

        VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pNext(features2.address())
                .pQueueCreateInfos(queueInfo)
                .ppEnabledExtensionNames(pExts);
        PointerBuffer pDevice = stack.mallocPointer(1);
        check(vkCreateDevice(physicalDevice, info, null, pDevice), "vkCreateDevice");
        return new VkDevice(pDevice.get(0), physicalDevice, info, VK_API_VERSION_1_2);
    }

    private long createAllocator(MemoryStack stack) {
        VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
        VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                .flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                .physicalDevice(physicalDevice)
                .device(device)
                .instance(instance)
                .pVulkanFunctions(functions)
                .vulkanApiVersion(VK_API_VERSION_1_2);
        PointerBuffer pAllocator = stack.mallocPointer(1);
        check(vmaCreateAllocator(info, pAllocator), "vmaCreateAllocator");
        return pAllocator.get(0);
    }

    private RayTracingProperties queryRtProperties(MemoryStack stack) {
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rt = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceAccelerationStructurePropertiesKHR as = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
                .sType$Default()
                .pNext(rt.address());
        VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(as.address());
        vkGetPhysicalDeviceProperties2(physicalDevice, props);
        return new RayTracingProperties(rt.shaderGroupHandleSize(), rt.shaderGroupBaseAlignment(),
                rt.shaderGroupHandleAlignment(), rt.maxRayRecursionDepth(),
                as.minAccelerationStructureScratchOffsetAlignment());
    }

    /** Finds a memory type for non-VMA allocations (exported images). */
    public int findMemoryType(int typeBits, int requiredFlags) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, mem);
            for (int i = 0; i < mem.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0 && (mem.memoryTypes(i).propertyFlags() & requiredFlags) == requiredFlags) {
                    return i;
                }
            }
        }
        throw new VkCheck.VulkanException("No suitable memory type");
    }

    public void waitIdle() {
        vkDeviceWaitIdle(device);
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        vmaDestroyAllocator(allocator);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDevice(device, null);
        if (debugMessenger != VK_NULL_HANDLE) DebugMessenger.destroy(instance, debugMessenger);
        vkDestroyInstance(instance, null);
    }
}
