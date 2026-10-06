<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/57e0bf4e-c3eb-4ec1-97f9-996db0de8420

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.


## Custom OpenCL runtime

This build links the official Khronos OpenCL ICD Loader. At app startup, the runtime scans Android OpenCL ICD registration directories and sets `OCL_ICD_FILENAMES` from discovered `.icd` files before the first FFmpegKit native call. Vendor OpenCL implementations are not bundled.

The app performs a small FFmpeg-backed OpenCL initialization probe. A successful FFmpeg build alone is not treated as proof of GPU execution. When an OpenCL device is not confirmed, OpenCL filter commands are rewritten to supported CPU equivalents instead of failing the render.

Note: OpenCL is not an automatic replacement for every CPU filter. Commands must request an OpenCL-capable filter/hardware path to use GPU processing. When the command does not request OpenCL, the existing CPU filter path remains unchanged.

## Runtime fallback behavior

The execution engine probes the OpenCL device before a render that requests OpenCL. If the probe reports no usable OpenCL platform, commands using supported OpenCL filter equivalents are converted to the CPU filter path (for example `unsharp_opencl` -> `unsharp`, and the OpenCL hardware upload/download wrappers are removed). The MediaCodec encoder request is preserved when a compatible hardware encoder exists. When no compatible hardware H.264/HEVC MediaCodec encoder exists, the engine falls back to `libx264`/`libx265`.

This fallback does not pretend that OpenCL is working: the terminal log records the original command, the effective command, the probe result, and the fallback reason.

## Fast local-AAR workflow

Use `app/libs/ffmpeg-kit-custom.aar` as the single custom FFmpegKit input. Replace the placeholder with the real OpenCL + MediaCodec AAR before building. The GitHub Actions workflow is app-only and does not rebuild FFmpegKit.
