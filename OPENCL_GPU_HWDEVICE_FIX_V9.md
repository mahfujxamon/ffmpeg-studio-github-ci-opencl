# OpenCL GPU hw-device propagation v10

## What this fixes

The Android FFmpegKit tree carries its own vendored `fftools_*` source. The
runtime was reaching a real OpenCL-capable device, but `hwupload` still failed
with:

`A hardware device reference is required to upload frames to.`

The v9 patch moves hardware-device selection before filter initialization and
assigns the selected `AVBufferRef` to the actual parsed filter contexts before
`avfilter_graph_segment_init()` can call `hwupload_init()`.

The assignment is intentionally done for every parsed filter, matching the
legacy FFmpegKit behavior, rather than relying only on `AVFILTER_FLAG_HWDEVICE`.
This makes the diagnosis observable and avoids ambiguity if a carried filter
flag differs from the upstream copy.

## Expected diagnostic lines

A successful build/runtime should contain lines similar to:

`[FFMPEGKIT-HWDEVICE] explicit filter device: ocl`

`[FFMPEGKIT-HWDEVICE] attached device to filter Parsed_hwupload_...`

`[FFMPEGKIT-HWDEVICE] attached selected device to N filter(s).`

After that, the `hwupload` error should disappear. The next expected blocker,
if any, will be a real OpenCL hardware-frame/format constraint or kernel/runtime
problem rather than missing filter-device propagation.


## v10 app-level fix

The FFmpeg OpenCL GPU smoke test uses `-f null -`, so there is intentionally no
output file path. The UI previously treated `success=true` + `returnCode=0` +
`outputPath=null` as an execution failure. v10 reports that case as a successful
FFmpeg test instead of adding a false `[ERROR]` line. Normal file renders still
validate the output file and publish it to MediaStore.

## v10-4 app-side hardening

The native Android engine now treats the OpenCL device binding as part of command normalization, not as a responsibility of each UI command template. Any command using an OpenCL-capable filter receives:

`-init_hw_device opencl=ocl:0.0 -filter_hw_device ocl`

when those options are missing. The same normalization is applied defensively immediately before `FFmpegKit.executeAsync()`. Existing OpenCL options are preserved without duplication, and ordinary filenames containing `opencl` are not considered OpenCL requests.


## Cross-device vendor discovery (v11)

The Android runtime path now performs direct native provider discovery before the FFmpeg OpenCL smoke test. It selects the first device-supplied OpenCL implementation that successfully enumerates a platform, using ABI-aware 64-bit/32-bit library names and vendor paths. Render execution waits for this asynchronous probe to finish, preventing slower MediaTek/Realme devices from starting an OpenCL command while the capability result is still unresolved.

## v11 cross-device OpenCL runtime

The native Android probe now verifies the OpenCL `cl_khr_icd` entry point through
`clGetExtensionFunctionAddress` before setting `OCL_ICD_FILENAMES`. A library that
only exposes direct `clGetPlatformIDs` is not forced into the Khronos ICD loader.
The app also serializes the native + FFmpeg OpenCL capability check on a worker
thread so an OpenCL render cannot start while the probe is unresolved.
