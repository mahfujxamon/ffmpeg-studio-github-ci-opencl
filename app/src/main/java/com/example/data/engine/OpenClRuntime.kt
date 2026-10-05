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

    private val standardVendorDirectories = listOf(
        "/system/vendor/Khronos/OpenCL/vendors",
        "/vendor/Khronos/OpenCL/vendors",
        "/odm/Khronos/OpenCL/vendors",
        "/system/etc/OpenCL/vendors",
        "/vendor/etc/OpenCL/vendors",
        "/odm/etc/OpenCL/vendors"
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

        if (discovered.isNotEmpty()) {
            val value = discovered.joinToString(File.pathSeparator)
            val configured = try {
                Os.setenv(OCL_ICD_FILENAMES, value, true)
                true
            } catch (t: Throwable) {
                false
            }

            val result = Preparation(
                configured = configured,
                icdLibraries = discovered,
                searchedDirectories = searched,
                message = if (configured) {
                    "Configured ${discovered.size} Android OpenCL ICD implementation(s)."
                } else {
                    "Found ${discovered.size} ICD implementation(s), but process environment setup failed."
                }
            )
            preparationCache = result
            return result
        }

        val result = Preparation(
            configured = false,
            icdLibraries = emptyList(),
            searchedDirectories = searched,
            message = "No .icd registration file was discovered; Khronos loader default discovery remains enabled."
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
