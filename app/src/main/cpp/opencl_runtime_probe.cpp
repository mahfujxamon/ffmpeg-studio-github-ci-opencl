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
    return {
        "libOpenCL.so",
        "libOpenCL.so.1",
        "libOpenCL-pixel.so",
        "libOpenCL-car.so",
        "libGLES_mali.so",
        "libmali.so",
        "libPVROCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/odm/lib64/libOpenCL.so",
        "/vendor/lib64/egl/libGLES_mali.so",
        "/system/vendor/lib64/egl/libGLES_mali.so",
        "/system/lib64/egl/libGLES_mali.so",
        "/odm/lib64/egl/libGLES_mali.so",
        "/vendor/lib64/libGLES_mali.so",
        "/system/vendor/lib64/libGLES_mali.so",
        "/system/lib64/libGLES_mali.so",
        "/odm/lib64/libGLES_mali.so",
        "/vendor/lib64/libmali.so",
        "/system/vendor/lib64/libmali.so",
        "/system/lib64/libmali.so",
        "/odm/lib64/libmali.so",
        "/vendor/lib64/libPVROCL.so",
        "/system/vendor/lib64/libPVROCL.so",
        "/system/lib64/libPVROCL.so",
        "/odm/lib64/libPVROCL.so"
    };
}

static std::string runProbe() {
    std::ostringstream report;
    int usable = 0;

    for (const auto& path : candidates()) {
        dlerror();
        void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            const char* err = dlerror();
            report << path << " -> dlopen FAIL";
            if (err) report << " (" << err << ")";
            report << "\n";
            continue;
        }

        dlerror();
        auto getPlatforms = reinterpret_cast<ClGetPlatformIDs>(dlsym(h, "clGetPlatformIDs"));
        const char* symErr = dlerror();
        auto icdGetPlatforms = reinterpret_cast<ClIcdGetPlatformIDsKHR>(dlsym(h, "clIcdGetPlatformIDsKHR"));
        if (!getPlatforms || symErr) {
            report << path << " -> loaded, clGetPlatformIDs MISSING";
            if (symErr) report << " (" << symErr << ")";
            report << "\n";
            dlclose(h);
            continue;
        }

        cl_uint count = 0;
        cl_int rc = getPlatforms(0, nullptr, &count);
        report << path << " -> clGetPlatformIDs rc=" << rc << " platforms=" << count;
        report << " icd_khr_symbol=" << (icdGetPlatforms ? "yes" : "no");
        if (icdGetPlatforms) {
            cl_uint icdCount = 0;
            cl_int icdRc = icdGetPlatforms(0, nullptr, &icdCount);
            report << " icd_khr_rc=" << icdRc << " icd_khr_platforms=" << icdCount;
        }

        if (rc == CL_SUCCESS && count > 0) {
            std::vector<cl_platform_id> platforms(count);
            rc = getPlatforms(count, platforms.data(), nullptr);
            report << " enumerate_rc=" << rc;
            if (rc == CL_SUCCESS) {
                auto getPlatformInfo = reinterpret_cast<ClGetPlatformInfo>(dlsym(h, "clGetPlatformInfo"));
                auto getDeviceIds = reinterpret_cast<ClGetDeviceIDs>(dlsym(h, "clGetDeviceIDs"));
                auto getDeviceInfo = reinterpret_cast<ClGetDeviceInfo>(dlsym(h, "clGetDeviceInfo"));
                for (cl_uint i = 0; i < count && i < 4; ++i) {
                    if (getPlatformInfo) {
                        report << " [platform=" << getInfoString(getPlatformInfo, platforms[i], CL_PLATFORM_NAME)
                               << "; vendor=" << getInfoString(getPlatformInfo, platforms[i], CL_PLATFORM_VENDOR)
                               << "; version=" << getInfoString(getPlatformInfo, platforms[i], CL_PLATFORM_VERSION) << "]";
                    }
                    if (getDeviceIds) {
                        cl_uint dc = 0;
                        cl_int drc = getDeviceIds(platforms[i], CL_DEVICE_TYPE_ALL, 0, nullptr, &dc);
                        report << " devices_rc=" << drc << " devices=" << dc;
                        if (drc == CL_SUCCESS && dc > 0) {
                            std::vector<cl_device_id> devices(dc);
                            drc = getDeviceIds(platforms[i], CL_DEVICE_TYPE_ALL, dc, devices.data(), nullptr);
                            report << " device_enum_rc=" << drc;
                            if (drc == CL_SUCCESS && getDeviceInfo) {
                                report << " [device=" << getDeviceInfoString(getDeviceInfo, devices[0], CL_DEVICE_NAME)
                                       << "; vendor=" << getDeviceInfoString(getDeviceInfo, devices[0], CL_DEVICE_VENDOR) << "]";
                            }
                        }
                    }
                }
            }
            ++usable;
        }
        report << "\n";
        dlclose(h);
    }

    report << "SUMMARY usable_opencl_implementations=" << usable;
    return report.str();
}


static bool hasUsablePlatform(const std::string& path) {
    dlerror();
    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) return false;
    dlerror();
    auto getPlatforms = reinterpret_cast<ClGetPlatformIDs>(dlsym(h, "clGetPlatformIDs"));
    if (!getPlatforms) { dlclose(h); return false; }
    cl_uint count = 0;
    cl_int rc = getPlatforms(0, nullptr, &count);
    bool ok = (rc == CL_SUCCESS && count > 0);
    dlclose(h);
    return ok;
}

static std::string configureKhronosLoader() {
    // The direct probe has already established that these are real, working
    // device OpenCL implementations. Tell the Khronos ICD loader to use the
    // first one that actually enumerates a platform. No vendor .so is bundled.
    const std::vector<std::string> preferred = {
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "libOpenCL.so"
    };

    for (const auto& path : preferred) {
        if (!hasUsablePlatform(path)) continue;
        if (setenv("OCL_ICD_FILENAMES", path.c_str(), 1) != 0) {
            return "usable OpenCL provider found at " + path + ", but setenv(OCL_ICD_FILENAMES) failed";
        }
        setenv("OCL_ICD_ENABLE_TRACE", "1", 1);
        return "configured Khronos loader OCL_ICD_FILENAMES=" + path;
    }
    return "no usable OpenCL provider found for Khronos loader configuration";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_data_engine_OpenClNativeProbe_nativeProbe(JNIEnv* env, jclass) {
    // Configure the process environment from the same native library that is
    // already known to be loaded. This intentionally reuses the existing
    // nativeProbe JNI entry point so an incremental/stale APK cannot expose
    // a new JNI symbol mismatch.
    const std::string loader = configureKhronosLoader();
    const std::string report = loader + "\n" + runProbe();
    LOGD("%s", report.c_str());
    return env->NewStringUTF(report.c_str());
}
