# Helios — ray traced lighting for Minecraft

Helios is a Fabric mod for Minecraft Java 1.21.1 that replaces vanilla terrain lighting with
**hardware ray tracing** (Vulkan `VK_KHR_ray_tracing_pipeline`), with optional **NVIDIA DLSS**
upscaling and a built-in temporal upscaler for everything else.

Where Iris runs rasterized shader packs inside Minecraft's OpenGL pipeline, Helios builds a
ray tracing acceleration structure from the world and path traces it in Vulkan, then hands the
result back to OpenGL.

## Features

- **Path traced global illumination**: diffuse bounces (default 2) with Russian roulette.
- **Ray traced sun/moon shadows** with soft penumbrae, following the day/night cycle and rain.
- **Emissive blocks** (torches, glowstone, lava…) light the world from their light level.
- **Water** you can see into, with depth-based absorption, Fresnel reflections and underwater fog.
- **Entity shadows**: mobs and players cast (box-shaped) ray traced shadows.
- **Alpha-tested foliage** (leaves, grass, flowers) via an any-hit shader.
- **Denoiser**: temporal accumulation + edge-aware à-trous wavelet filter on demodulated lighting.
- **Upscaling**: NVIDIA DLSS Super Resolution (DLAA…Ultra Performance) when available, otherwise
  a built-in TAAU pass; both use sub-pixel jitter and motion vectors from the ray tracer.
- Entities, block entities, particles, clouds, weather and the hand still render through vanilla,
  depth tested against the ray traced world.

## Requirements

- A GPU with hardware ray tracing: NVIDIA RTX 20xx+, AMD RX 6000+, Intel Arc.
- Up-to-date drivers exposing `VK_KHR_ray_tracing_pipeline` and OpenGL `GL_EXT_memory_object`
  (Windows or Linux; macOS has no Vulkan ray tracing).
- Minecraft 1.21.1, Fabric Loader 0.16+, Fabric API.
- DLSS additionally needs an RTX GPU and the self-built bridge in [`native/dlss`](native/dlss/README.md).

## Controls & config

| Key | Action |
| --- | --- |
| F9  | Toggle ray tracing (falls back to vanilla instantly) |
| F10 | Cycle upscaling quality |

`config/helios.properties`:

| Key | Default | |
| --- | --- | --- |
| `enabled` | `true` | |
| `upscaler` | `DLSS` | `DLSS` or `TAAU` (DLSS falls back to TAAU if unavailable) |
| `quality` | `QUALITY` | `NATIVE` (DLAA/TAA), `QUALITY`, `BALANCED`, `PERFORMANCE`, `ULTRA_PERFORMANCE` |
| `maxBounces` | `2` | diffuse bounces after the primary hit |
| `samplesPerPixel` | `1` | paths per pixel per frame |
| `denoiser` / `denoiserIterations` | `true` / `4` | |
| `exposure` | `1.0` | |
| `sectionsPerFrame` | `64` | max chunk sections meshed per frame |
| `meshBudgetMs` | `3.0` | time per frame spent meshing chunk sections |
| `entityShadows` | `true` | mobs and players cast box-shaped shadows |
| `validation` | `false` | Vulkan validation layers (or `-Dhelios.validation=true`) |

## How it works

```
Minecraft (OpenGL)                       Helios core (Vulkan)
──────────────────                       ────────────────────
chunk load / block change ──► SectionMesher ──► per-section BLAS (opaque + any-hit geometry)
block atlas texture ─────────────────────────► sampled in hit shaders
camera, sun, sky ────────────────────────────► TLAS rebuild (camera-anchored) → path trace
                                                 │ illumination / albedo / normal+depth / motion / depth
                                                 ▼
                                               temporal accumulation → à-trous ×4 → re-modulate
                                                 ▼
                                               DLSS  or  TAAU  →  ACES tonemap
LevelRenderer#renderSky  ◄── GL_EXT_memory_object ── shared colour + depth images
  (composite colour + gl_FragDepth, then vanilla entities/particles/hand on top)
```

| Path | Contents |
| --- | --- |
| `core/` | Minecraft-independent renderer: Vulkan context, VMA memory, acceleration structures, RT pipeline + SBT, denoiser/upscaler passes, DLSS JNI bindings, GLSL shaders |
| `core/src/main/resources/assets/helios/shaders/` | `pathtrace.rgen`, `surface.rchit`, `alphatest.rahit`, miss shaders, compute passes |
| `fabric/` | Fabric mod: mixins into `LevelRenderer`, chunk section meshing from baked models, atlas capture, GL interop/composite, config, keybinds |
| `native/dlss/` | C++ JNI bridge to NVIDIA NGX (DLSS) |

Precision: all geometry is expressed relative to the camera's block position, so there is no
jitter far from the world origin. Motion vectors re-express the previous frame's view-projection
relative to the current anchor.

## Building

```sh
./gradlew build                     # full mod jar → fabric/build/libs/helios-<version>.jar
./gradlew -Phelios.coreOnly :core:test   # renderer core + shader compilation tests only
```

The core tests compile every shader to SPIR-V with shaderc, so GLSL errors fail the build without
needing a GPU. `./gradlew :fabric:runClient` starts a dev client.

## Status and limitations

This is an early (0.1) implementation. What has and hasn't been verified:

- ✅ Core renderer compiles; unit tests pass (geometry packing, jitter, reprojection math, all
  shaders compile to SPIR-V).
- ✅ The DLSS bridge compiles against the official NGX headers and its JNI symbols match the Java
  declarations.
- ⚠️ The Fabric module was written against Minecraft 1.21.1 Mojang mappings but not yet compiled or
  run in-game, and the renderer has not been run on RTX hardware. Expect a round of fixes on first
  launch.

Known limitations / next steps:

- Incompatible with Sodium and Iris (they replace the same rendering code).
- Entities are rasterized by vanilla; their ray traced shadows use their bounding boxes, and they do
  not appear in reflections.
- Fluids use simplified flat surfaces; no refraction, no caustics.
- Emissive blocks are sampled only by bounce rays; many small lights are noisy (ReSTIR would fix this).
- No PBR/LabPBR resource-pack support yet (normal/specular maps).
