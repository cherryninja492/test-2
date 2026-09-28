cmake_minimum_required(VERSION 3.20)
project(helios_dlss CXX)

# Builds the optional DLSS bridge for Helios.
#
#   cmake -B build -DDLSS_SDK_DIR=/path/to/DLSS   (clone of https://github.com/NVIDIA/DLSS)
#   cmake --build build --config Release
#
# Copy the resulting helios_dlss library AND the DLSS runtime (nvngx_dlss.dll /
# libnvidia-ngx-dlss.so.*) into <minecraft>/config/helios/natives/.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(DLSS_SDK_DIR "" CACHE PATH "Path to the NVIDIA DLSS SDK (contains include/ and lib/)")
if(NOT EXISTS "${DLSS_SDK_DIR}/include/nvsdk_ngx_vk.h")
    message(FATAL_ERROR "DLSS_SDK_DIR must point to the NVIDIA DLSS SDK (https://github.com/NVIDIA/DLSS)")
endif()

find_package(JNI REQUIRED)
find_package(Vulkan REQUIRED)

add_library(helios_dlss SHARED src/helios_dlss.cpp)
target_include_directories(helios_dlss PRIVATE ${JNI_INCLUDE_DIRS} "${DLSS_SDK_DIR}/include")
target_link_libraries(helios_dlss PRIVATE Vulkan::Headers)

if(WIN32)
    # Dynamic CRT variant of the NGX static library.
    target_link_libraries(helios_dlss PRIVATE "${DLSS_SDK_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_d.lib")
    set(DLSS_RUNTIME_GLOB "${DLSS_SDK_DIR}/lib/Windows_x86_64/rel/nvngx_dlss.dll")
else()
    target_link_libraries(helios_dlss PRIVATE "${DLSS_SDK_DIR}/lib/Linux_x86_64/libnvsdk_ngx.a" ${CMAKE_DL_LIBS})
    set(DLSS_RUNTIME_GLOB "${DLSS_SDK_DIR}/lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.*")
endif()

# Stage the library together with the DLSS runtime for easy copying.
file(GLOB DLSS_RUNTIME ${DLSS_RUNTIME_GLOB})
add_custom_command(TARGET helios_dlss POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_BINARY_DIR}/dist"
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:helios_dlss> ${DLSS_RUNTIME} "${CMAKE_BINARY_DIR}/dist/")
