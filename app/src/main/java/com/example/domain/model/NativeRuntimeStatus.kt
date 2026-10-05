package com.example.domain.model

sealed interface NativeRuntimeStatus {
    data class Ready(
        val version: String,
        val abi: String,
        val hasHwAvc: Boolean,
        val hasBoxblur: Boolean = true,
        val buildVariant: String = "Full-GPL (Standard Filters Enabled)",
        val openClConfigured: Boolean = false,
        val openClIcdLibraries: List<String> = emptyList(),
        val openClProbeAvailable: Boolean? = null,
        val openClProbeDetail: String? = null
    ) : NativeRuntimeStatus

    data class Unavailable(
        val rootCause: String,
        val fullStackTrace: String
    ) : NativeRuntimeStatus
}
