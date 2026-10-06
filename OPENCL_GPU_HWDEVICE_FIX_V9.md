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
