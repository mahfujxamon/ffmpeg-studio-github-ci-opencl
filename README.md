# FFmpeg Studio — local exploded FFmpegKit AAR

This version stores the custom FFmpegKit AAR **unpacked** in Git.

## Put your custom AAR here

1. Take your real `ffmpeg-kit-custom.aar`.
2. Unzip it.
3. Copy every extracted item into:

   `app/ffmpeg-kit-custom/`

4. Commit/push the files.
5. GitHub Actions will automatically recreate `app/libs/ffmpeg-kit-custom.aar` and build the APK.

Required top-level entries:

- `AndroidManifest.xml`
- `classes.jar`

Keep native libraries under the same `jni/<abi>/` paths from the original AAR.

No FFmpegKit source rebuild is performed by this workflow.
