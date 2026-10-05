package com.example.data.engine

import android.system.Os
import java.io.File

/**
 * Process-wide OpenCL runtime bootstrap for Android.
 *
 * The app ships only the official Khronos ICD loader. It MUST NOT bundle a
 * vendor OpenCL implementation and it MUST NOT guess that a generic
 * libOpenCL.so name is an ICD implementation. On Android, that name can itself
 * be a loader/stub and forcing it through OCL_ICD_FILENAMES can hide the real
 * platform-provided ICD discovery path and produce CL_PLATFORM_NOT_FOUND_KHR.
 *
 * Therefore this class does two things only:
 *  1) expose any already-provided OCL_ICD_FILENAMES value, and
 *  2) discover real .icd registration files from standard Android locations.
 *
 * FFmpeg's own OpenCL probe remains the source of truth for actual availability.
 */
object OpenClRuntime {

    const val OCL_ICD_FILENAMES = "OCL_ICD_FILENAMES"
    const val OCL_ICD_VENDORS = "OCL_ICD_VENDORS"
    const val OCL_ICD_ENABLE_TRACE = "OCL_ICD_ENABLE_TRACE"

    private val standardIcdDirectories = listOf(
        "/system/vendor/Khronos/OpenCL/vendors",
        "/vendor/Khronos/OpenCL/vendors",
        "/odm/Khronos/OpenCL/vendors",
        "/system_ext/vendor/Khronos/OpenCL/vendors",
        "/product/Khronos/OpenCL/vendors",
        "/system/etc/OpenCL/vendors",
        "/vendor/etc/OpenCL/vendors",
        "/odm/etc/OpenCL/vendors",
        "/system_ext/etc/OpenCL/vendors",
        "/product/etc/OpenCL/vendors"
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
     * this before the first FFmpegKit/native OpenCL call.
     */
    @Synchronized
    fun startup(): Preparation = prepare()

    @Synchronized
    fun prepare(): Preparation {
        preparationCache?.let { return it }

        val searched = standardIcdDirectories.toList()
        val existingFilenames = System.getenv(OCL_ICD_FILENAMES)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (existingFilenames != null) {
            val result = Preparation(
                configured = true,
                icdLibraries = splitPathList(existingFilenames),
                searchedDirectories = searched,
                message = "OCL_ICD_FILENAMES is already provided by the process; preserving it."
            )
            preparationCache = result
            return result
        }

        val discovered = discoverIcdLibraries()
        if (discovered.isNotEmpty()) {
            val value = discovered.joinToString(File.pathSeparator)
            val configured = try {
                Os.setenv(OCL_ICD_FILENAMES, value, true)
                // Trace is diagnostic only. It is safe to ignore failure.
                try {
                    Os.setenv(OCL_ICD_ENABLE_TRACE, "1", true)
                } catch (_: Throwable) {
                    // Ignore optional tracing failure.
                }
                true
            } catch (_: Throwable) {
                false
            }

            val result = Preparation(
                configured = configured,
                icdLibraries = discovered,
                searchedDirectories = searched,
                message = if (configured) {
                    "Configured ${discovered.size} discovered OpenCL ICD registration(s)."
                } else {
                    "OpenCL ICD registrations were found, but process environment setup failed."
                }
            )
            preparationCache = result
            return result
        }

        // IMPORTANT: do not set OCL_ICD_FILENAMES to generic libOpenCL.so names.
        // A generic libOpenCL.so may be the loader/stub itself rather than a
        // vendor ICD. Leaving the variable unset allows the Khronos loader to
        // perform its platform/default Android discovery rules.
        val result = Preparation(
            configured = false,
            icdLibraries = emptyList(),
            searchedDirectories = searched,
            message = "No .icd registration file found; left Khronos loader default discovery untouched."
        )
        preparationCache = result
        return result
    }

    fun lastProbe(): ProbeResult? = probeCache

    fun setProbeResult(result: ProbeResult) {
        probeCache = result
    }

    fun clearProbe() {
        probeCache = null
    }

    private fun discoverIcdLibraries(): List<String> {
        val libraries = linkedSetOf<String>()

        for (directoryPath in standardIcdDirectories) {
            val directory = File(directoryPath)
            val entries = try {
                directory.listFiles { file ->
                    file.isFile && file.extension.equals("icd", ignoreCase = true)
                }?.sortedBy { it.name }
            } catch (_: Throwable) {
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
