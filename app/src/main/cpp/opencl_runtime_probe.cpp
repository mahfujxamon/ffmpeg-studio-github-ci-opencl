#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <sstream>
#include <cstdint>
#include <cstdlib>

#define LOG_TAG "OpenClRuntimeProbe"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using cl_int = int32_t;
using cl_uint = uint32_t;
using cl_platform_id = void*;
using cl_device_id = void*;

using ClGetPlatformIDs = cl_int (*)(cl_uint, cl_platform_id*, cl_uint*);
using ClGetPlatformInfo = cl_int (*)(cl_platform_id, cl_uint, size_t, void*, size_t*);
using ClGetExtensionFunctionAddress = void* (*)(const char*);
using ClGetDeviceIDs = cl_int (*)(cl_platform_id, uint64_t, cl_uint, cl_device_id*, cl_uint*);
using ClGetDeviceInfo = cl_int (*)(cl_device_id, uint32_t, size_t, void*, size_t*);
using ClIcdGetPlatformIDsKHR = cl_int (*)(cl_uint, cl_platform_id*, cl_uint*);

// OpenCL enum values used only for diagnostics.
static constexpr cl_int CL_SUCCESS = 0;
static constexpr cl_int CL_DEVICE_NOT_FOUND = -1;
static constexpr uint64_t CL_DEVICE_TYPE_ALL = 0xFFFFFFFFu;
static constexpr uint32_t CL_PLATFORM_PROFILE = 0x0900;
static constexpr uint32_t CL_PLATFORM_VERSION = 0x0901;
static constexpr uint32_t CL_PLATFORM_NAME = 0x0902;
static constexpr uint32_t CL_PLATFORM_VENDOR = 0x0903;
static constexpr uint32_t CL_DEVICE_NAME = 0x102B;
static constexpr uint32_t CL_DEVICE_VENDOR = 0x102C;

static std::string getInfoString(ClGetPlatformInfo fn, cl_platform_id p, uint32_t param) {
    if (!fn || !p) return {};
    size_t n = 0;
    if (fn(p, param, 0, nullptr, &n) != CL_SUCCESS || n == 0 || n > 16384) return {};
    std::string out(n, '\0');
    if (fn(p, param, n, out.data(), nullptr) != CL_SUCCESS) return {};
    if (!out.empty() && out.back() == '\0') out.pop_back();
    return out;
}

static std::string getDeviceInfoString(ClGetDeviceInfo fn, cl_device_id d, uint32_t param) {
    if (!fn || !d) return {};
    size_t n = 0;
    if (fn(d, param, 0, nullptr, &n) != CL_SUCCESS || n == 0 || n > 16384) return {};
    std::string out(n, '\0');
    if (fn(d, param, n, out.data(), nullptr) != CL_SUCCESS) return {};
    if (!out.empty() && out.back() == '\0') out.pop_back();
    return out;
}

static std::vector<std::string> candidates() {
    // Android application processes are ABI-specific. A 64-bit process cannot
    // load a 32-bit vendor OpenCL implementation and vice versa. Keep both
    // name- and path-based candidates, but order the active process ABI first.
    const bool is64 = sizeof(void*) == 8;
    std::vector<std::string> out;

    auto add = [&](const char* value) {
        if (!value || !*value) return;
        for (const auto& existing : out) {
            if (existing == value) return;
        }
        out.emplace_back(value);
    };

    // Generic SONAME first: Android's linker namespace may resolve the public
    // vendor library by name even when an absolute /vendor path is rejected.
    add("libOpenCL.so");
    add("libOpenCL.so.1");
    add("libOpenCL-pixel.so");
    add("libOpenCL-car.so");

    if (is64) {
        add("/vendor/lib64/libOpenCL.so");
        add("/system/vendor/lib64/libOpenCL.so");
        add("/odm/lib64/libOpenCL.so");
        add("/product/lib64/libOpenCL.so");
        add("/vendor/lib64/egl/libGLES_mali.so");
        add("/system/vendor/lib64/egl/libGLES_mali.so");
        add("/odm/lib64/egl/libGLES_mali.so");
        add("/vendor/lib64/libGLES_mali.so");
        add("/system/vendor/lib64/libGLES_mali.so");
        add("/odm/lib64/libGLES_mali.so");
        add("/vendor/lib64/libmali.so");
        add("/system/vendor/lib64/libmali.so");
        add("/odm/lib64/libmali.so");
        add("/vendor/lib64/libPVROCL.so");
        add("/system/vendor/lib64/libPVROCL.so");
        add("/odm/lib64/libPVROCL.so");
    } else {
        add("/vendor/lib/libOpenCL.so");
        add("/system/vendor/lib/libOpenCL.so");
        add("/odm/lib/libOpenCL.so");
        add("/product/lib/libOpenCL.so");
        add("/vendor/lib/egl/libGLES_mali.so");
        add("/system/vendor/lib/egl/libGLES_mali.so");
        add("/odm/lib/egl/libGLES_mali.so");
        add("/vendor/lib/libGLES_mali.so");
        add("/system/vendor/lib/libGLES_mali.so");
        add("/odm/lib/libGLES_mali.so");
        add("/vendor/lib/libmali.so");
        add("/system/vendor/lib/libmali.so");
        add("/odm/lib/libmali.so");
        add("/vendor/lib/libPVROCL.so");
        add("/system/vendor/lib/libPVROCL.so");
        add("/odm/lib/libPVROCL.so");
    }

    // Some OEMs expose a generic libOpenCL name only through the linker; the
    // Android app manifest already declares these as optional native libraries.
    return out;
}

static bool hasSymbol(void* handle, const char* symbol) {
    if (!handle || !symbol || !*symbol) return false;
    dlerror();
    void* address = dlsym(handle, symbol);
    const char* error = dlerror();
    return address != nullptr && error == nullptr;
}

static bool inspectIcdProvider(const std::string& path, bool* isIcd) {
    if (isIcd) *isIcd = false;
    dlerror();
    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) return false;

    // Never call an OpenCL entry point during discovery. Some OEM drivers
    // block inside clGetPlatformIDs(). The real FFmpeg smoke test below is
    // the authoritative runtime capability check.
    const bool hasPlatformIds = hasSymbol(h, "clGetPlatformIDs");
    const bool hasDirectIcd = hasSymbol(h, "clIcdGetPlatformIDsKHR");
    if (isIcd) *isIcd = hasDirectIcd;
    dlclose(h);
    return hasPlatformIds;
}

static std::vector<std::string> directProviderCandidates() {
    const bool is64 = sizeof(void*) == 8;
    std::vector<std::string> out;
    auto add = [&](const char* value) {
        if (!value || !*value) return;
        for (const auto& existing : out) if (existing == value) return;
        out.emplace_back(value);
    };

    // Absolute vendor paths only. Never use generic libOpenCL.so here because
    // Android may resolve that name to another loader.
    if (is64) {
        add("/system/vendor/lib64/libOpenCL.so");
        add("/vendor/lib64/libOpenCL.so");
        add("/odm/lib64/libOpenCL.so");
        add("/product/lib64/libOpenCL.so");
        add("/system/vendor/lib64/libGLES_mali.so");
        add("/vendor/lib64/libGLES_mali.so");
        add("/odm/lib64/libGLES_mali.so");
        add("/system/vendor/lib64/egl/libGLES_mali.so");
        add("/vendor/lib64/egl/libGLES_mali.so");
        add("/odm/lib64/egl/libGLES_mali.so");
        add("/system/vendor/lib64/libmali.so");
        add("/vendor/lib64/libmali.so");
        add("/odm/lib64/libmali.so");
        add("/system/vendor/lib64/libPVROCL.so");
        add("/vendor/lib64/libPVROCL.so");
        add("/odm/lib64/libPVROCL.so");
    } else {
        add("/system/vendor/lib/libOpenCL.so");
        add("/vendor/lib/libOpenCL.so");
        add("/odm/lib/libOpenCL.so");
        add("/product/lib/libOpenCL.so");
        add("/system/vendor/lib/libGLES_mali.so");
        add("/vendor/lib/libGLES_mali.so");
        add("/odm/lib/libGLES_mali.so");
        add("/system/vendor/lib/egl/libGLES_mali.so");
        add("/vendor/lib/egl/libGLES_mali.so");
        add("/odm/lib/egl/libGLES_mali.so");
        add("/system/vendor/lib/libmali.so");
        add("/vendor/lib/libmali.so");
        add("/odm/lib/libmali.so");
        add("/system/vendor/lib/libPVROCL.so");
        add("/vendor/lib/libPVROCL.so");
        add("/odm/lib/libPVROCL.so");
    }
    return out;
}

static std::string configureKhronosLoader() {
    std::ostringstream report;
    const char* existing = std::getenv("OCL_ICD_FILENAMES");
    if (existing && *existing) {
        return std::string("preserving existing OCL_ICD_FILENAMES=") + existing;
    }

    // Standard ICD discovery: symbol inspection only.
    for (const auto& path : candidates()) {
        bool isIcd = false;
        if (!inspectIcdProvider(path, &isIcd) || !isIcd) continue;
        if (setenv("OCL_ICD_FILENAMES", path.c_str(), 1) != 0) {
            return "ICD provider detected at " + path + " but setenv failed";
        }
        setenv("OCL_ICD_ENABLE_TRACE", "1", 1);
        report << "configured Khronos loader OCL_ICD_FILENAMES=" << path
               << " mode=standard-icd-symbol";
        return report.str();
    }

    // Android direct provider fallback. Narzo/MediaTek Mali exposes ordinary
    // clGetPlatformIDs but no clIcdGetPlatformIDsKHR. Select by symbols only;
    // FFmpeg performs the real GPU capability check afterward.
    for (const auto& path : directProviderCandidates()) {
        bool isIcd = false;
        if (!inspectIcdProvider(path, &isIcd) || isIcd) continue;
        if (setenv("OCL_ICD_FILENAMES", path.c_str(), 1) != 0) {
            return "Android direct OpenCL provider detected at " + path + " but setenv failed";
        }
        setenv("OCL_ICD_ENABLE_TRACE", "1", 1);
        report << "configured Khronos loader OCL_ICD_FILENAMES=" << path
               << " mode=android-direct-symbol";
        return report.str();
    }

    return "no OpenCL provider selected by symbol-only discovery; leaving Android/default loader discovery unchanged";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_data_engine_OpenClNativeProbe_nativeProbe(JNIEnv* env, jclass) {
    // Configure the process environment from the same native library that is
    // already known to be loaded. This intentionally reuses the existing
    // nativeProbe JNI entry point so an incremental/stale APK cannot expose
    // a new JNI symbol mismatch.
    const std::string loader = configureKhronosLoader();
    // This JNI probe only selects the provider. Never enumerate OpenCL here;
    // FFmpeg's bounded smoke test is the authoritative capability check.
    LOGD("%s", loader.c_str());
    return env->NewStringUTF(loader.c_str());
}
