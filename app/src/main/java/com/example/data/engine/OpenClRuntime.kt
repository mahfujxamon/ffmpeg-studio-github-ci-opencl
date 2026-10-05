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
                // 2. The Magic Trick: Create a custom .icd file in a directory we control
                // Using /data/data/APP_PACKAGE/cache is safe and fully accessible
                val cacheDir = File("/data/user/0/com.aistudio.ffmpegstudio.kxvq/cache")
                val vendorDir = File(cacheDir, "Khronos/OpenCL/vendors")
                if (!vendorDir.exists()) {
                    vendorDir.mkdirs()
                }

                // Write the active driver absolute path inside the .icd file
                val icdFile = File(vendorDir, "android_custom.icd")
                icdFile.writeText(activeDriverPath)

                // 3. Force Khronos to look ONLY at our custom folder
                Os.setenv("OCL_ICD_VENDORS", vendorDir.absolutePath, true)
                Os.setenv("OCL_ICD_ENABLE_TRACE", "1", true) // For debugging

                val result = Preparation(
                    configured = true,
                    icdLibraries = listOf(activeDriverPath),
                    message = "Successfully mapped OpenCL via dynamic .icd file: $activeDriverPath"
                )
                preparationCache = result
                return result

            } catch (e: Exception) {
                val result = Preparation(
                    configured = false,
                    icdLibraries = listOf(activeDriverPath),
                    message = "Found driver at $activeDriverPath but failed to configure ICD: ${e.message}"
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
