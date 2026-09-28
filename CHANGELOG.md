# Changelog

Jars built by CI are named `helios-<version>+<commit>.jar`.

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
