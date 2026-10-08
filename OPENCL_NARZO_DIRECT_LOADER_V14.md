# OpenCL Narzo / Mali-G57 direct-provider compatibility (v14)

## Problem

Some Android Mali OpenCL implementations expose a usable `clGetPlatformIDs()`
but do not expose Khronos ICD entry points (`clIcdGetPlatformIDsKHR` /
`CL_PLATFORM_ICD_SUFFIX_KHR`). The stock Khronos loader rejects these providers
with `CL_PLATFORM_NOT_FOUND_KHR (-1001)`.

On Narzo 50 / Mali-G57, `/system/vendor/lib64/libOpenCL.so` directly enumerates
an ARM OpenCL 3.0 platform and Mali-G57 device, so the GPU implementation is
usable; only the loader contract is incompatible.

## v14 fix

1. Native Android probe first checks standard ICD-compatible providers.
2. If none exist, it checks direct providers with `clGetPlatformIDs()`.
3. Absolute Android vendor paths are preferred over generic `libOpenCL.so`.
4. A usable direct provider is exported through `OCL_ICD_FILENAMES` with
   `mode=android-direct-legacy`.
5. The CI-patched Khronos loader accepts the direct provider through ordinary
   `clGetPlatformIDs()` and supplies a private `LEGACY` suffix without requiring
   `CL_PLATFORM_ICD_SUFFIX_KHR`.
6. No CPU fallback and no vendor GPU library is bundled.
