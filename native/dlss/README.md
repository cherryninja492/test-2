# helios_dlss — optional DLSS bridge

A small JNI library that lets Helios call NVIDIA DLSS Super Resolution (NGX, Vulkan). It is **not**
shipped in the mod jar: the DLSS SDK license requires you to obtain the SDK from NVIDIA yourself.
Without it Helios uses its built-in temporal upscaler (TAAU).

## Build

Requirements: CMake 3.20+, a C++17 compiler, a JDK (for JNI headers), Vulkan headers, and the
[NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS) (clone with Git LFS so the libraries are fetched).

```sh
cmake -B build -DDLSS_SDK_DIR=/path/to/DLSS
cmake --build build --config Release
```

`build/dist/` then contains `helios_dlss.dll` + `nvngx_dlss.dll` (Windows) or
`libhelios_dlss.so` + `libnvidia-ngx-dlss.so.*` (Linux).

## Install

Copy both files from `build/dist/` into `<minecraft>/config/helios/natives/` and set
`upscaler=DLSS` in `config/helios.properties` (the default). The log reports either
`Helios targets: ... via DLSS` or the reason DLSS was not used.

Requires an NVIDIA RTX GPU and a recent driver.
