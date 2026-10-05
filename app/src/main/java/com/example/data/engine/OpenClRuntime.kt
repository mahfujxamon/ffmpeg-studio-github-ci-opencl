package com.example.data.engine

import android.system.Os
import java.io.File

object OpenClRuntime {

    data class Preparation(
        val configured: Boolean,
        val icdLibraries: List<String>,
        val message: String
    )

    data class ProbeResult(
        val available: Boolean,
        val detail: String
    )

    @Volatile
    private var preparationCache: Preparation? = null

    @Volatile
    private var probeCache: ProbeResult? = null

    @Synchronized
    fun startup(): Preparation = prepare()

    @Synchronized
    fun prepare(): Preparation {
        preparationCache?.let { return it }

        // Common OEM OpenCL driver paths on Android
        val commonPaths = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/vendor/lib64/egl/libGLES_mali.so",
            "/system/vendor/lib64/egl/libGLES_mali.so"
        )

        var activeDriverPath: String? = null

        // 1. Find the REAL driver that actually exists on this device
        for (path in commonPaths) {
            if (File(path).exists()) {
                activeDriverPath = path
                break
            }
        }

        if (activeDriverPath != null) {
            try {
                // 2. Create a fake libOpenCL.so using a Symlink in a safe app directory
                val appFilesDir = File("/data/user/0/com.aistudio.ffmpegstudio.kxvq/files")
                val oclDir = File(appFilesDir, "ocl_driver")
                if (!oclDir.exists()) {
                    oclDir.mkdirs()
                }

                // Delete any old symlink to avoid stale references
                val symlinkDriver = File(oclDir, "libOpenCL.so")
                if (symlinkDriver.exists()) {
                    symlinkDriver.delete()
                }

                // Attempt to create the symlink to the real driver
                try {
                    Os.symlink(activeDriverPath, symlinkDriver.absolutePath)
                } catch (e: Exception) {
                    // Ignore symlink failure (Android 10+ might block this based on SELinux)
                }

                // 3. Fallback: If symlink exists, use it. Otherwise, use the direct absolute path
                val loadPath = if (symlinkDriver.exists()) symlinkDriver.absolutePath else activeDriverPath

                // 4. Force Khronos to bypass default .icd discovery and load our exact file
                Os.setenv("OCL_ICD_FILENAMES", loadPath, true)
                Os.setenv("OCL_ICD_ENABLE_TRACE", "1", true) // For debugging in logcat

                val result = Preparation(
                    configured = true,
                    icdLibraries = listOf(loadPath),
                    message = "Successfully mapped OpenCL via symlink/direct path: $loadPath"
                )
                preparationCache = result
                return result

            } catch (e: Exception) {
                val result = Preparation(
                    configured = false,
                    icdLibraries = listOf(activeDriverPath),
                    message = "Failed to configure OpenCL driver at $activeDriverPath: ${e.message}"
                )
                preparationCache = result
                return result
            }
        }

        // If no driver exists on the device at all
        val result = Preparation(
            configured = false,
            icdLibraries = emptyList(),
            message = "No native OpenCL driver found on this device."
        )
        preparationCache = result
        return result
    }

    fun lastProbe(): ProbeResult? = probeCache

    fun setProbeResult(result: ProbeResult) {
        probeCache = result
    }
}
