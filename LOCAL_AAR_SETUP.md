# Local custom FFmpegKit AAR — exploded layout

Do **not** upload the `.aar` file for the normal workflow.

Instead, unzip your real `ffmpeg-kit-custom.aar` and copy **all of its contents** into:

`app/ffmpeg-kit-custom/`

The directory must have `AndroidManifest.xml` and `classes.jar` at its top level. If the AAR contains `jni/`, `res/`, `assets/`, `libs/`, etc., keep those directories exactly as they appear after unzipping.

Example:

```text
app/
└── ffmpeg-kit-custom/
    ├── AndroidManifest.xml
    ├── classes.jar
    ├── R.txt
    ├── proguard.txt
    ├── jni/
    │   └── arm64-v8a/
    │       └── *.so
    ├── res/
    └── assets/
```

The GitHub Actions workflow automatically recreates `app/libs/ffmpeg-kit-custom.aar` from this directory before Gradle runs.

This avoids relying on GitHub Actions being able to inspect or preserve a binary AAR upload as expected.
