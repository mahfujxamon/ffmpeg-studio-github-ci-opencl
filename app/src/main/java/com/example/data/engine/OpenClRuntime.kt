package com.example.data.engine

import android.system.Os
import java.io.File

/**
 * Process-wide OpenCL runtime bootstrap for Android.
 *
 * The app ships only the official Khronos ICD loader. It must never bundle a
 * vendor OpenCL implementation. At runtime we discover standard Android ICD
 * registration files and expose their referenced implementation libraries to
 * the Khronos loader through OCL_ICD_FILENAMES.
 */
object OpenClRuntime {

    const val OCL_ICD_FILENAMES = "OCL_ICD_FILENAMES"
    const val OCL_ICD_ENABLE_TRACE = "OCL_ICD_ENABLE_TRACE"

    private val standardVendorDirectories = listOf(
        "/system/vendor/Khronos/OpenCL/vendors",
        "/vendor/Khronos/OpenCL/vendors",
        "/odm/Khronos/OpenCL/vendors",
        "/system/etc/OpenCL/vendors",
        "/vendor/etc/OpenCL/vendors",
        "/odm/etc/OpenCL/vendors"
    )

    /**
     * Android devices are not required to expose a separate .icd registration
     * file to ordinary apps. In particular, the platform may expose the
     * vendor OpenCL entrypoint as libOpenCL.so directly. The Khronos loader
     * accepts library names as OCL_ICD_FILENAMES, so try the generic Android
     * OpenCL names as well as any discovered .icd entries.
     *
     * No GPU/vendor implementation is bundled by the app. These are only
     * runtime lookup candidates.
     */
    private val genericRuntimeIcdCandidates = listOf(
        "libOpenCL.so",
        "libOpenCL.so.1",
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/odm/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/vendor/lib/libOpenCL.so",
        "/odm/lib/libOpenCL.so"
    )

    data class Preparation(
        val configured: Boolean,
        val icdLibraries: List<String>,
        val searchedDirectories: List<String>,
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

    /**
     * Explicit process-start bootstrap entrypoint. NativeFfmpegEngine calls
     * this before any FFmpegKit/native OpenCL call. Keeping the entrypoint
     * here makes the startup contract explicit instead of relying on a file
     * replacement or an incidental ViewModel call.
     */
    @Synchronized
    fun startup(): Preparation = prepare()

    @Synchronized
    fun prepare(): Preparation {
        preparationCache?.let { return it }

        val searched = standardVendorDirectories
        val existing = System.getenv(OCL_ICD_FILENAMES)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (existing != null) {
            val result = Preparation(
                configured = true,
                icdLibraries = splitPathList(existing),
                searchedDirectories = searched,
                message = "OCL_ICD_FILENAMES was already provided by the process."
            )
            preparationCache = result
            return result
        }

        val discovered = discoverIcdLibraries()
        val candidates = linkedSetOf<String>().apply {
            addAll(discovered)
            addAll(genericRuntimeIcdCandidates)
        }.toList()

        if (candidates.isNotEmpty()) {
            val value = candidates.joinToString(File.pathSeparator)
            val configured = try {
                Os.setenv(OCL_ICD_FILENAMES, value, true)
                // Enable Khronos loader trace so failed ICD loads are visible
                // in the FFmpeg log instead of being reduced to -1001.
                Os.setenv(OCL_ICD_ENABLE_TRACE, "1", true)
                true
            } catch (_: Throwable) {
                false
            }

            val result = Preparation(
                configured = configured,
                icdLibraries = candidates,
                searchedDirectories = searched,
                message = if (configured) {
                    if (discovered.isNotEmpty()) {
                        "Configured discovered ICD(s) plus generic Android OpenCL runtime candidates."
                    } else {
                        "No .icd file found; configured generic Android OpenCL runtime candidates."
                    }
                } else {
                    "OpenCL runtime candidates found, but process environment setup failed."
                }
            )
            preparationCache = result
            return result
        }

        val result = Preparation(
            configured = false,
            icdLibraries = emptyList(),
            searchedDirectories = searched,
            message = "No OpenCL runtime candidates were discovered; Khronos loader default discovery remains enabled."
        )
        preparationCache = result
        return result
    }

    fun lastProbe(): ProbeResult? = probeCache

    fun setProbeResult(result: ProbeResult) {
        probeCache = result
    }

    private fun discoverIcdLibraries(): List<String> {
        val libraries = linkedSetOf<String>()

        for (directoryPath in standardVendorDirectories) {
            val directory = File(directoryPath)
            val entries = try {
                directory.listFiles { file ->
                    file.isFile && file.extension.equals("icd", ignoreCase = true)
                }?.sortedBy { it.name }
            } catch (_: SecurityException) {
                null
            }

            entries?.forEach { registrationFile ->
                val registration = try {
                    registrationFile.readLines()
                        .asSequence()
                        .map { it.trim() }
                        .firstOrNull { line ->
                            line.isNotEmpty() &&
                                !line.startsWith("#") &&
                                !line.startsWith(";")
                        }
                } catch (_: Throwable) {
                    null
                }

                if (!registration.isNullOrBlank()) {
                    libraries += registration.trim().trim('"', '\'')
                }
            }
        }

        return libraries.toList()
    }

    private fun splitPathList(value: String): List<String> =
        value.split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
