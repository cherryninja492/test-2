// JNI bridge between Helios (dev.helios.core.dlss.DlssBridge) and NVIDIA NGX DLSS Super Resolution
// for Vulkan. All Vulkan handles are created by Helios through LWJGL and passed in as integers.

#include <jni.h>
#include <vulkan/vulkan.h>

#include <nvsdk_ngx_defs.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_params.h>
#include <nvsdk_ngx_vk.h>

#include <cstdio>
#include <string>
#include <vector>

namespace {

// Arbitrary project GUID for NGX telemetry/whitelisting of a custom engine.
constexpr const char* kProjectId = "6b1a9e5c-3d4f-4b8a-9c2e-5f7d1e0a4c3b";
constexpr const char* kEngineVersion = "0.1.0";

struct Context {
    VkDevice device = VK_NULL_HANDLE;
    NVSDK_NGX_Parameter* params = nullptr;
};

std::string g_lastError;

void setError(const char* what, NVSDK_NGX_Result result) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%s failed (NVSDK_NGX_Result 0x%08x)", what, static_cast<unsigned>(result));
    g_lastError = buf;
}

std::wstring toWide(JNIEnv* env, jstring str) {
    const jchar* chars = env->GetStringChars(str, nullptr);
    jsize len = env->GetStringLength(str);
    std::wstring out(chars, chars + len);
    env->ReleaseStringChars(str, chars);
    return out;
}

jobjectArray toJavaStrings(JNIEnv* env, unsigned count, const char* const* names) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(count), stringClass, nullptr);
    for (unsigned i = 0; i < count; i++) {
        env->SetObjectArrayElement(array, static_cast<jsize>(i), env->NewStringUTF(names[i]));
    }
    return array;
}

NVSDK_NGX_Resource_VK imageResource(const jlong* r, bool readWrite) {
    VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(r[1]), reinterpret_cast<VkImage>(r[0]), range,
        static_cast<VkFormat>(r[2]), static_cast<unsigned>(r[3]), static_cast<unsigned>(r[4]), readWrite);
}

Context* ctxOf(jlong handle) { return reinterpret_cast<Context*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeRequiredInstanceExtensions(JNIEnv* env, jclass) {
    unsigned instanceCount = 0, deviceCount = 0;
    const char** instanceExts = nullptr;
    const char** deviceExts = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_RequiredExtensions(&instanceCount, &instanceExts, &deviceCount, &deviceExts);
    if (NVSDK_NGX_FAILED(r)) {
        setError("NVSDK_NGX_VULKAN_RequiredExtensions", r);
        return nullptr;
    }
    return toJavaStrings(env, instanceCount, instanceExts);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeRequiredDeviceExtensions(JNIEnv* env, jclass) {
    unsigned instanceCount = 0, deviceCount = 0;
    const char** instanceExts = nullptr;
    const char** deviceExts = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_RequiredExtensions(&instanceCount, &instanceExts, &deviceCount, &deviceExts);
    if (NVSDK_NGX_FAILED(r)) {
        setError("NVSDK_NGX_VULKAN_RequiredExtensions", r);
        return nullptr;
    }
    return toJavaStrings(env, deviceCount, deviceExts);
}

JNIEXPORT jlong JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeInit(JNIEnv* env, jclass, jstring appDataPath, jstring runtimeSearchPath,
                                                 jlong instance, jlong physicalDevice, jlong device,
                                                 jlong getInstanceProcAddr, jlong getDeviceProcAddr) {
    std::wstring dataPath = toWide(env, appDataPath);
    std::wstring searchPath = toWide(env, runtimeSearchPath);
    const wchar_t* paths[] = {searchPath.c_str()};

    NVSDK_NGX_FeatureCommonInfo featureInfo{};
    featureInfo.PathListInfo.Path = paths;
    featureInfo.PathListInfo.Length = 1;
    featureInfo.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_OFF;

    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(
        kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, kEngineVersion, dataPath.c_str(),
        reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkPhysicalDevice>(physicalDevice),
        reinterpret_cast<VkDevice>(device),
        reinterpret_cast<PFN_vkGetInstanceProcAddr>(getInstanceProcAddr),
        reinterpret_cast<PFN_vkGetDeviceProcAddr>(getDeviceProcAddr), &featureInfo);
    if (NVSDK_NGX_FAILED(r)) {
        setError("NVSDK_NGX_VULKAN_Init_with_ProjectID", r);
        return 0;
    }

    auto* ctx = new Context();
    ctx->device = reinterpret_cast<VkDevice>(device);
    r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&ctx->params);
    if (NVSDK_NGX_FAILED(r)) {
        setError("NVSDK_NGX_VULKAN_GetCapabilityParameters", r);
        NVSDK_NGX_VULKAN_Shutdown1(ctx->device);
        delete ctx;
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeIsSuperSamplingAvailable(JNIEnv*, jclass, jlong handle) {
    Context* ctx = ctxOf(handle);
    int available = 0;
    NVSDK_NGX_Result r = ctx->params->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    if (NVSDK_NGX_FAILED(r) || !available) {
        int driverUpdate = 0;
        ctx->params->Get(NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &driverUpdate);
        g_lastError = driverUpdate ? "DLSS requires a newer NVIDIA driver" : "DLSS is not available on this GPU";
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeOptimalRenderSize(JNIEnv* env, jclass, jlong handle, jint displayWidth,
                                                              jint displayHeight, jint perfQuality) {
    Context* ctx = ctxOf(handle);
    unsigned renderW = 0, renderH = 0, maxW = 0, maxH = 0, minW = 0, minH = 0;
    float sharpness = 0.0f;
    NVSDK_NGX_Result r = NGX_DLSS_GET_OPTIMAL_SETTINGS(
        ctx->params, static_cast<unsigned>(displayWidth), static_cast<unsigned>(displayHeight),
        static_cast<NVSDK_NGX_PerfQuality_Value>(perfQuality), &renderW, &renderH, &maxW, &maxH, &minW, &minH,
        &sharpness);
    if (NVSDK_NGX_FAILED(r) || renderW == 0 || renderH == 0) {
        setError("NGX_DLSS_GET_OPTIMAL_SETTINGS", r);
        return nullptr;
    }
    jint size[2] = {static_cast<jint>(renderW), static_cast<jint>(renderH)};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, size);
    return out;
}

JNIEXPORT jlong JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeCreateFeature(JNIEnv*, jclass, jlong handle, jlong commandBuffer,
                                                          jint renderWidth, jint renderHeight, jint displayWidth,
                                                          jint displayHeight, jint perfQuality) {
    Context* ctx = ctxOf(handle);
    NVSDK_NGX_DLSS_Create_Params create{};
    create.Feature.InWidth = static_cast<unsigned>(renderWidth);
    create.Feature.InHeight = static_cast<unsigned>(renderHeight);
    create.Feature.InTargetWidth = static_cast<unsigned>(displayWidth);
    create.Feature.InTargetHeight = static_cast<unsigned>(displayHeight);
    create.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(perfQuality);
    // Helios feeds linear HDR colour, render-resolution motion vectors and has no exposure texture.
    create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR | NVSDK_NGX_DLSS_Feature_Flags_MVLowRes |
                                  NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;

    NVSDK_NGX_Handle* feature = nullptr;
    NVSDK_NGX_Result r = NGX_VULKAN_CREATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(commandBuffer), 1, 1, &feature,
                                                    ctx->params, &create);
    if (NVSDK_NGX_FAILED(r)) {
        setError("NGX_VULKAN_CREATE_DLSS_EXT", r);
        return 0;
    }
    return reinterpret_cast<jlong>(feature);
}

JNIEXPORT jboolean JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeEvaluate(JNIEnv* env, jclass, jlong handle, jlong feature,
                                                     jlong commandBuffer, jlongArray resources, jfloat jitterX,
                                                     jfloat jitterY, jint renderWidth, jint renderHeight,
                                                     jboolean reset, jfloat motionScaleX, jfloat motionScaleY) {
    Context* ctx = ctxOf(handle);
    jlong r[20];
    env->GetLongArrayRegion(resources, 0, 20, r);

    NVSDK_NGX_Resource_VK color = imageResource(r + 0, false);
    NVSDK_NGX_Resource_VK depth = imageResource(r + 5, false);
    NVSDK_NGX_Resource_VK motion = imageResource(r + 10, false);
    NVSDK_NGX_Resource_VK output = imageResource(r + 15, true);

    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
    eval.Feature.pInColor = &color;
    eval.Feature.pInOutput = &output;
    eval.Feature.InSharpness = 0.0f;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &motion;
    // Helios jitters the sample position by +jitter pixels (y down); NGX expects the same convention.
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InRenderSubrectDimensions.Width = static_cast<unsigned>(renderWidth);
    eval.InRenderSubrectDimensions.Height = static_cast<unsigned>(renderHeight);
    eval.InReset = reset ? 1 : 0;
    eval.InMVScaleX = motionScaleX;
    eval.InMVScaleY = motionScaleY;

    NVSDK_NGX_Result res = NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(commandBuffer),
                                                        reinterpret_cast<NVSDK_NGX_Handle*>(feature), ctx->params,
                                                        &eval);
    if (NVSDK_NGX_FAILED(res)) {
        setError("NGX_VULKAN_EVALUATE_DLSS_EXT", res);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeReleaseFeature(JNIEnv*, jclass, jlong, jlong feature) {
    if (feature != 0) NVSDK_NGX_VULKAN_ReleaseFeature(reinterpret_cast<NVSDK_NGX_Handle*>(feature));
}

JNIEXPORT void JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeShutdown(JNIEnv*, jclass, jlong handle) {
    Context* ctx = ctxOf(handle);
    if (ctx == nullptr) return;
    if (ctx->params != nullptr) NVSDK_NGX_VULKAN_DestroyParameters(ctx->params);
    NVSDK_NGX_VULKAN_Shutdown1(ctx->device);
    delete ctx;
}

JNIEXPORT jstring JNICALL
Java_dev_helios_core_dlss_DlssBridge_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_lastError.c_str());
}

}  // extern "C"
