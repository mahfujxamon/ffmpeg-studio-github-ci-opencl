# OpenCL Runtime v4

## What changed

- Kept Khronos loader-only packaging; no vendor OpenCL library is bundled.
- Kept standard Android `.icd` discovery and added non-loading diagnostics for `libOpenCL*.so` candidates.
- Added capability-aware command preparation in `NativeFfmpegEngine`.
- If OpenCL is requested but the FFmpeg-backed probe is not successful, supported OpenCL filters are converted to CPU equivalents and `hwupload`/`hwdownload` plus OpenCL device options are removed.
- If no hardware H.264/HEVC MediaCodec encoder is available, `h264_mediacodec`/`hevc_mediacodec` fall back to `libx264`/`libx265`.
- MainViewModel now renders the effective command and logs the exact fallback decision.

## Current device result

The reported `CL_PLATFORM_NOT_FOUND_KHR (-1001)` means the shipped Khronos loader did not find a usable OpenCL platform. This version therefore prevents that condition from aborting a render when the command has a supported CPU equivalent. It does not manufacture an OpenCL platform or bundle a vendor driver.
