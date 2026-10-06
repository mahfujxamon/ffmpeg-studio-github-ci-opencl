# Custom FFmpegKit CI build

This project now builds a pinned FFmpegKit 8.1.9 LTS AAR in GitHub Actions instead of relying on the Maven binary.

The workflow:

1. Uses Ubuntu 24.04.
2. Installs the FFmpegKit host build dependencies, including `texinfo`/`makeinfo`.
3. Uses Java 21 and Android NDK r27c (`27.2.12479018`).
4. Clones `v8.1.9-lts-android` exactly.
5. Builds arm64-v8a with MediaCodec and GPL/full options.
6. Builds and statically links the official Khronos OpenCL ICD Loader, then configures FFmpeg with OpenCL support without bundling a vendor implementation.
7. Verifies ELF LOAD alignment is at least 16 KB.
8. Builds the Android app against the generated `app/libs/ffmpeg-kit.aar`.
9. Uploads the AAR, APK, and build logs as GitHub Actions artifacts.

The OpenCL loader is runtime-based: it attempts to load the device's permitted `libOpenCL.so` and resolves the OpenCL 1.2 core entry points dynamically. A vendor Samsung/Mali OpenCL binary is not committed or packaged.

## Important

The app currently uses the GPL FFmpegKit variant. Keep that only if the licensing of the application permits GPL components.

## Android OpenCL runtime

The app initializes `OCL_ICD_FILENAMES` before the first FFmpegKit native call. It scans standard Android Khronos ICD registration directories for `.icd` files and passes the referenced implementation libraries to the official Khronos loader. No Samsung/Mali vendor OpenCL binary is packaged. The app also runs a small FFmpeg-backed OpenCL device initialization probe so the UI can distinguish “configured” from “actually usable”.

The runtime probe only verifies OpenCL device initialization. It does not claim that every OpenCL filter or every vendor driver is bug-free.
