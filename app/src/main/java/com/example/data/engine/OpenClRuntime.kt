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
            "/vendor/lib64/egl/libGLES_mali.so",       // For MediaTek/Exynos
            "/system/vendor/lib64/egl/libGLES_mali.so",
            "/system/vendor/lib64/libOpenCL-pixel.so"  // For some custom ROMs/Pixels
        )

        var goldenDriverPath: String? = null

        // 1. The Magic RAM Pre-load Hack: Test and force-load into App Memory
        for (path in commonPaths) {
            if (File(path).exists()) {
                try {
                    // Try to forcefully load the library into the app's process via JVM
                    System.load(path)
                    
                    // If we reach here without crashing, the OS Linker allowed it!
                    goldenDriverPath = path
                    break
                } catch (e: UnsatisfiedLinkError) {
                    // Linker blocked it or wrong architecture, ignore and try next
                } catch (e: Exception) {
                    // Other reading errors, ignore and try next
                }
            }
        }

        if (goldenDriverPath != null) {
            try {
                // 2. Now that the driver is officially in our app's RAM,
                // tell Khronos loader exactly which file to ask for.
                Os.setenv("OCL_ICD_FILENAMES", goldenDriverPath, true)
                Os.setenv("OCL_ICD_ENABLE_TRACE", "1", true) // For debugging in logcat

                val result = Preparation(
                    configured = true,
                    icdLibraries = listOf(goldenDriverPath),
                    message = "Successfully pre-loaded and mapped OpenCL driver: $goldenDriverPath"
                )
                preparationCache = result
                return result

            } catch (e: Exception) {
                val result = Preparation(
                    configured = false,
                    icdLibraries = listOf(goldenDriverPath),
                    message = "Pre-loaded $goldenDriverPath but env setup failed: ${e.message}"
                )
                preparationCache = result
                return result
            }
        }

        // If OS blocked every single file or none exist
        val result = Preparation(
            configured = false,
            icdLibraries = emptyList(),
            message = "No valid, accessible native OpenCL driver found on this device."
        )
        preparationCache = result
        return result
    }

    fun lastProbe(): ProbeResult? = probeCache

    fun setProbeResult(result: ProbeResult) {
        probeCache = result
    }
}
