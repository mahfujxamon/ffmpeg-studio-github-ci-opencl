# OpenCL Runtime v7 — direct vendor-library probe

The previous v5/v6 approach put guessed names such as `libGLES_mali.so` into
`OCL_ICD_FILENAMES`. That is not a reliable generic ICD strategy. The Khronos
loader expects ICD implementation libraries there; an Android GPU library may
export OpenCL functions without being a Khronos ICD.

v7 therefore makes a decisive diagnostic step based on the direct `dlopen` /
`dlsym` pattern used by Android OpenCL consumers such as MNN:

* Do not alter `OCL_ICD_FILENAMES` when there is no real `.icd` registration.
* Ship only a tiny app-owned diagnostic JNI library (`libopencl_runtime_probe.so`).
* Try common Android OpenCL implementation SONAMEs and absolute vendor paths.
* For each library that can be loaded, resolve `clGetPlatformIDs` and call it.
* When a platform is available, query platform/vendor/version and first-device
  name/vendor.
* This does not bundle or modify any vendor driver.
* FFmpeg's real OpenCL command remains unchanged; this diagnostic only tells us
  which native library can actually provide an OpenCL platform.

The next implementation decision should be driven by this probe result. If a
vendor library such as `libGLES_mali.so` reports a real platform, we can build a
small direct-forwarding OpenCL bridge for FFmpeg instead of continuing to guess
ICD files. If all candidates fail, the device does not expose a usable OpenCL
implementation to the app namespace and we must stop treating the generic
SONAME list as evidence of OpenCL availability.
