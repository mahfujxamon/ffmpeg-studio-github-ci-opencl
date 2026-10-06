package com.example.data.engine

/**
 * Small native diagnostic based on the direct-dlopen approach used by Android
 * OpenCL consumers such as MNN. It deliberately does not ship any vendor
 * library; it only asks the device for libraries that already exist.
 */
object OpenClNativeProbe {
    private var loaded = false

    init {
        try {
            System.loadLibrary("opencl_runtime_probe")
            loaded = true
        } catch (_: Throwable) {
            loaded = false
        }
    }

    fun isLoaded(): Boolean = loaded

    fun configureKhronosLoader(): String = if (!loaded) {
        "native OpenCL probe library could not be loaded"
    } else {
        try {
            nativeConfigureKhronosLoader()
        } catch (t: Throwable) {
            "native OpenCL loader configuration exception: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun probe(): String = if (!loaded) {
        "native OpenCL probe library could not be loaded"
    } else {
        try {
            nativeProbe()
        } catch (t: Throwable) {
            "native OpenCL probe exception: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    @JvmStatic
    private external fun nativeConfigureKhronosLoader(): String

    @JvmStatic
    private external fun nativeProbe(): String
}
