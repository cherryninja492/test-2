# Changelog

Jars built by CI are named `helios-<version>+<commit>.jar`.

## 0.1.3
- Fix "Out of stack space (VulkanContext.java:137)": LWJGL's own `VkInstance` constructor lists
  every GPU's extensions on the thread's 64 KiB stack, which overflows on systems with more than one
  GPU (e.g. integrated + dedicated). Vulkan setup now runs on a helper thread with a 4 MiB stack.

## 0.1.2
- Fix "Helios could not start: Out of stack space": Helios now uses its own 1 MiB LWJGL stack
  instead of Minecraft's 64 KiB one, and compiles shaders from heap memory.
- The failure chat message now names the source location that failed.
- Jars are named with version and commit.

## 0.1.1
- Enumerate Vulkan device extensions on the heap (first attempt at the stack overflow fix).
- Build with Loom 1.10 (required for Gradle 8.14).

## 0.1.0
- Initial version: Vulkan path tracer, denoiser, TAAU/DLSS upscaling, Fabric integration.
