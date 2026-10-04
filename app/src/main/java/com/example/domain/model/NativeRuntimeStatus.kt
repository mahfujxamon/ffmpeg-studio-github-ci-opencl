package com.example.domain.model

sealed interface NativeRuntimeStatus {
    data class Ready(
        val version: String,
        val abi: String,
        val hasHwAvc: Boolean,
        val hasBoxblur: Boolean = true,
        val buildVariant: String = "Full-GPL (Standard Filters Enabled)"
    ) : NativeRuntimeStatus

    data class Unavailable(
        val rootCause: String,
        val fullStackTrace: String
    ) : NativeRuntimeStatus
}
