# OpenCL Runtime v5

## Why v5 changes the runtime strategy

Research of current Android OpenCL consumers shows two recurring patterns:

1. Google MediaPipe-based Android applications declare optional vendor OpenCL native libraries with `<uses-native-library ... android:required="false"/>`. Android's documentation says this element makes non-NDK vendor libraries accessible to the app; for apps targeting Android 12/API 31+, these libraries are otherwise hidden from the app namespace.
2. MNN and other OpenCL consumers dynamically try common device-provided OpenCL library names such as `libOpenCL.so`, `libGLES_mali.so`, `libmali.so`, and `libPVROCL.so` instead of bundling a vendor driver.

This build follows that architecture without bundling vendor binaries.

## v5 behavior

- The APK declares common OpenCL vendor library names as optional native libraries.
- `OpenClRuntime` first honors real `.icd` registrations when available.
- When no `.icd` registration exists, it configures the Khronos loader with common runtime vendor-library names.
- OpenCL commands are no longer silently rewritten to CPU when the probe fails. The exact OpenCL FFmpeg command remains visible so the GPU path can be tested honestly.
- No Samsung/Exynos/Mali device-specific branch is used.
