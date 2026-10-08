# OpenCL Cross-Device Runtime v11

This revision keeps the generated FFmpegKit native build as the source of truth
and improves Android OpenCL provider discovery for OEM-dependent devices.

## Runtime changes

- Native OpenCL discovery is ABI-aware (32/64-bit).
- Vendor OpenCL candidates are tested through the actual Android process linker.
- `clGetExtensionFunctionAddress("clIcdGetPlatformIDsKHR")` is checked before a
  provider is selected for the Khronos ICD loader.
- A non-ICD library is no longer forced into `OCL_ICD_FILENAMES`.
- Native vendor discovery runs before the FFmpeg OpenCL smoke test.
- The FFmpeg smoke test runs on a worker thread as a blocking health check, so
  Render cannot race the probe on slower OEM firmware.
- Multiple concurrent probe requests are coalesced into one result.
- No OpenCL filter is rewritten to a CPU filter.

## Device behavior

OpenCL remains optional. Devices exposing a compatible OpenCL implementation can
use the real GPU filter path; devices without one keep the normal CPU FFmpeg
path, while an explicitly requested OpenCL command reports the real capability
failure instead of silently substituting CPU filters.
