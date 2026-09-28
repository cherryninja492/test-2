# Changelog

Jars built by CI are named `helios-<version>+<commit>.jar`.

## 0.4.0
New
- Glass and ice refract (IOR 1.5 / 1.31) with Fresnel reflections and tint from stained glass;
  sunlight passes through them.
- Glossy metal blocks (iron, gold, copper, netherite, anvils, chains...) with tinted reflections.
- Polished blocks (diamond, emerald, lapis, amethyst, quartz, prismarine, polished stone...) get
  a glossy clear coat.
- Animated block textures (water, lava, fire, portals...) now animate: frames are copied from
  Minecraft's CPU-side sprite images as they are uploaded.

Fixes
- Entity shadows were missing in 0.3.0: every entity model was filtered out because Minecraft's
  render type description contains "affects_outline".
- Light-emitting blocks are no longer blown out (their own block light was added on top of their
  emission; emission lowered).
- New tonemapper (fitted ACES RRT+ODT) and lower exposure: less washed-out, no neon greens.
- Entities behind water and glass stay visible.
- Entity shadow range is configurable (`entityShadowRange`, default 40) to limit CPU cost.

## 0.3.0
- Entity shadows use the real animated models (mobs, your own player, items, minecarts) instead
  of boxes; vanilla's round blob shadows are hidden while Helios renders.
- Vanilla sky is kept (square sun and moon, stars, sunsets, clouds); the ray traced world is drawn
  over it.
- Liquids are meshed by Minecraft's own liquid renderer: sloped flowing water/lava and waterfalls
  look right.
- Water no longer looks like aerogel: far less scattering, coloured by the water texture and biome
  colour, and sunlight on submerged surfaces is dimmed by the water above.
- Torches and other lights are much brighter: surfaces also use Minecraft's block light level,
  on top of ray traced emission.

## 0.2.0
Image quality
- Sun/moon shadows are no longer blurred by the denoiser: direct light is kept sharp and only the
  bounced light is denoised. Stronger sun vs. sky balance so shadows read clearly.
- Less shimmer: blocks are textured with Minecraft's mipmaps at a distance, and temporal
  accumulation now keeps history while moving (it compared distances from the wrong camera).
- Water rewritten: see-through with depth-based absorption and scattering, Fresnel reflections
  without the pink/purple banding, proper look from underwater.
- Mobs and players cast (box-shaped) ray traced shadows (`entityShadows`).

Performance
- Vulkan and OpenGL are synchronized on the GPU (`GL_EXT_semaphore`) instead of the CPU waiting
  for each frame to finish, so CPU and GPU work overlap.
- Block geometry lives in GPU memory instead of being read over PCIe by every ray hit.
- Chunk meshing is time-budgeted per frame (`meshBudgetMs`).
- F3 shows Helios' GPU, render resolution, sync mode and per-pass GPU times.

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
