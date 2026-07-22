package com.arkanefans.mnn_engine.runtime

internal object MnnNativeBridge {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("MNN")
        System.loadLibrary("mnn_engine_jni")
    }.exceptionOrNull()

    val loaded: Boolean
        get() = loadError == null

    fun version(): String {
        loadError?.let { error ->
            throw IllegalStateException("MNN native libraries are unavailable", error)
        }
        return nativeGetVersion()
    }

    fun loadFailureMessage(): String? = loadError?.message

    private external fun nativeGetVersion(): String
}
