package com.example.data.engine

import android.system.Os
import java.io.File

/**
 * Process-wide OpenCL runtime bootstrap for Android.
 *
 * The app does not ship a vendor OpenCL implementation. Instead it exposes
 * optional vendor libraries through Android's native-library namespace and
 * lets the Khronos ICD loader try common implementation names at runtime.
 * This mirrors the strategy used by real Android GPU/OpenCL consumers:
 * dynamically use the device-provided OpenCL implementation rather than copy
 * the vendor driver into the APK.
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

    /**
     * Common Android OpenCL implementation SONAMEs used by real applications.
     * These are runtime candidates only; no device/SoC is assumed and no
     * vendor binary is bundled by the app.
     *
     * We intentionally prefer concrete implementation names before the generic
     * libOpenCL.so name because on some systems libOpenCL.so is itself a loader.
     */
    private val runtimeVendorLibraryNames = listOf(
        "libGLES_mali.so",
        "libmali.so",
        "libPVROCL.so",
        "libOpenCL-pixel.so",
        "libOpenCL-car.so",
        "libOpenCL.so"
    )

    data class Preparation(
        val configured: Boolean,
        val icdLibraries: List<String>,
        val searchedDirectories: List<String>,
        val libraryCandidates: List<String>,
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

    /** Explicit process-start bootstrap before any FFmpeg/OpenCL call. */
    @Synchronized
    fun startup(): Preparation = prepare()

    @Synchronized
    fun prepare(): Preparation {
        preparationCache?.let { return it }

        val searched = standardIcdDirectories.toList()
        val vendorCandidates = runtimeVendorLibraryNames.toList()
        val existingFilenames = System.getenv(OCL_ICD_FILENAMES)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (existingFilenames != null) {
            val result = Preparation(
                configured = true,
                icdLibraries = splitPathList(existingFilenames),
                searchedDirectories = searched,
                libraryCandidates = vendorCandidates,
                message = "OCL_ICD_FILENAMES is already provided by the process; preserving it."
            )
            preparationCache = result
            return result
        }

        val discoveredIcds = discoverIcdLibraries()
        if (discoveredIcds.isNotEmpty()) {
            val configured = setEnvironment(
                OCL_ICD_FILENAMES,
                discoveredIcds.joinToString(File.pathSeparator)
            )

            val result = Preparation(
                configured = configured,
                icdLibraries = discoveredIcds,
                searchedDirectories = searched,
                libraryCandidates = vendorCandidates,
                message = if (configured) {
                    "Configured ${discoveredIcds.size} device OpenCL ICD registration(s) from system directories."
                } else {
                    "System OpenCL ICD registrations were found, but process environment setup failed."
                }
            )
            preparationCache = result
            return result
        }

        // Do NOT force a list of arbitrary Android shared libraries into
        // OCL_ICD_FILENAMES. Khronos explicitly expects that variable to name
        // ICDs. On some MediaTek/Realme firmware libOpenCL.so is itself the
        // system loader or libGLES_mali.so is a direct OpenCL implementation,
        // not an ICD. Forcing those names can make the loader recurse, block, or
        // return no platform. The native probe below selects an actual ICD when
        // one exists; otherwise we preserve the platform's normal discovery.
        val result = Preparation(
            configured = true,
            icdLibraries = emptyList(),
            searchedDirectories = searched,
            libraryCandidates = vendorCandidates,
            message = "No .icd registration found; preserving Android/default OpenCL loader discovery. Native probe will select an ICD only when it is actually ICD-compatible."
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

    private fun setEnvironment(name: String, value: String): Boolean {
        return try {
            Os.setenv(name, value, true)
            try {
                Os.setenv(OCL_ICD_ENABLE_TRACE, "1", true)
            } catch (_: Throwable) {
                // Diagnostic tracing is optional.
            }
            true
        } catch (_: Throwable) {
            false
        }
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
