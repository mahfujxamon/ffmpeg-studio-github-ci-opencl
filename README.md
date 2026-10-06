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

The app performs a small FFmpeg-backed OpenCL initialization probe and reports whether an OpenCL device could actually be initialized. A successful FFmpeg build alone is not treated as proof of GPU execution.

OpenCL commands are normalized at the native-engine boundary. When a command contains an OpenCL-capable filter such as `unsharp_opencl`, the engine automatically adds the required global options `-init_hw_device opencl=ocl:0.0` and `-filter_hw_device ocl` unless they are already present. This prevents the `hwupload` "A hardware device reference is required" failure when a user enters only the filtergraph. Existing options are preserved and never duplicated.

Note: OpenCL is not an automatic replacement for every CPU filter. Commands must request an OpenCL-capable filter/hardware path to use GPU processing. When the command does not request OpenCL, the existing CPU filter path remains unchanged.


### v10-4 runtime hardening

The native engine now applies the OpenCL device options both during command preparation and defensively again immediately before FFmpegKit execution. OpenCL detection also ignores ordinary filenames containing the word `opencl`, so those paths do not accidentally switch a render into GPU mode.


### v10 status cleanup

The OpenCL hardware-filter smoke test can end with FFmpeg return code 0 and no
output path because it writes to the null muxer. The UI now reports that as
success rather than `Execution failed with return code 0`.
