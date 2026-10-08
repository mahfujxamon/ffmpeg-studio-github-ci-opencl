# OpenCL capability probe hard timeout (v15)

## v14 issue found in field testing

The v14 12-second timeout started only after `OpenClNativeProbe.probe()` returned.
The native probe itself performs synchronous `clGetPlatformIDs()` calls through
OEM OpenCL libraries, so a loader that blocks there could leave the UI at
`OpenCL: CONFIGURED / CHECKING` forever.

## v15 fix

- Runs `OpenClNativeProbe.probe()` on a daemon worker thread.
- Bounds the native probe to 5 seconds.
- If native enumeration does not return, the capability result becomes
  `OpenCL: UNAVAILABLE` with a diagnostic instead of hanging indefinitely.
- The existing 12-second FFmpegKit async smoke-test timeout remains in place
  for the FFmpeg/Khronos path after the native probe completes.
- No CPU substitution is introduced.
