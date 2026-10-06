# Local custom FFmpegKit AAR setup

The app now uses **only** the local file:

`app/libs/ffmpeg-kit-custom.aar`

The ZIP contains a tiny placeholder AAR so the slot/path already exists.

## Before building

Replace:

`app/libs/ffmpeg-kit-custom.aar`

with the real custom FFmpegKit AAR produced by the OpenCL + MediaCodec build.

Keep the filename exactly:

`ffmpeg-kit-custom.aar`

After replacement, run the normal Android/AI Studio Gradle build. GitHub Actions also builds the app directly from this local AAR and does **not** rebuild FFmpegKit.

## Important

The placeholder AAR is intentionally not a usable FFmpegKit implementation. It only reserves the correct filename and location. A real custom FFmpegKit AAR must replace it before `assembleDebug`.
