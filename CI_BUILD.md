# Custom FFmpegKit CI build

This project now builds a pinned FFmpegKit 8.1.9 LTS AAR in GitHub Actions instead of relying on the Maven binary.

The workflow:

1. Uses Ubuntu 24.04.
2. Installs the FFmpegKit host build dependencies, including `texinfo`/`makeinfo`.
3. Uses Java 21 and Android NDK r27c (`27.2.12479018`).
4. Clones `v8.1.9-lts-android` exactly.
5. Builds arm64-v8a with MediaCodec and GPL/full options.
6. Applies the optional OpenCL runtime-dispatch patch without bundling a vendor OpenCL implementation.
7. Verifies ELF LOAD alignment is at least 16 KB.
8. Builds the Android app against the generated `app/libs/ffmpeg-kit.aar`.
9. Uploads the AAR, APK, and build logs as GitHub Actions artifacts.

The OpenCL loader is runtime-based: it attempts to load the device's permitted `libOpenCL.so` and resolves the OpenCL 1.2 core entry points dynamically. A vendor Samsung/Mali OpenCL binary is not committed or packaged.

## Important

The app currently uses the GPL FFmpegKit variant. Keep that only if the licensing of the application permits GPL components.
