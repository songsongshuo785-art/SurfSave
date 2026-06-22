package com.myAllVideoBrowser.v2ray

import android.util.Log

/**
 * This object is the JNI wrapper for the Go library `libgojni.so`.
 * It provides a 100% reproducible way to interact with the underlying Go/Xray core.
 *
 * 外部统一通过 start/stop/isRunning/measure 这些安全 wrapper 调用；
 * 原生 external 方法降级为 private，避免调用方绕过 nativeLoaded 检查导致 UnsatisfiedLinkError。
 */
object V2Ray {

    private const val TAG = "V2RayJNI"

    @Volatile
    private var nativeLoaded = false

    /**
     * 当原生库未加载却被调用时抛出，调用方可 catch 后降级（例如禁用代理）。
     */
    class NativeUnavailableException(message: String) : RuntimeException(message)

    // This block loads the native library once, when this V2Ray object is first used.
    // "gojni" corresponds to the filename "libgojni.so".
    init {
        try {
            System.loadLibrary("gojni")
            nativeLoaded = true
            Log.i(TAG, "Successfully loaded 'libgojni.so' native library.")
        } catch (e: UnsatisfiedLinkError) {
            // This error means the .so file was not found in the APK.
            // This is a critical failure.
            nativeLoaded = false
            Log.e(TAG, "CRITICAL: Failed to load native library 'libgojni.so'.", e)
        }
    }

    fun isNativeLoaded(): Boolean = nativeLoaded

    private fun requireNative() {
        if (!nativeLoaded) {
            throw NativeUnavailableException("libgojni.so not loaded; Xray proxy unavailable.")
        }
    }

    // --- Safe Kotlin wrappers (external 调用方必须经此入口) ---

    /**
     * Corresponds to: //export XrayRun
     * Starts the Xray core with the given JSON configuration.
     * @param config The full Xray JSON configuration as a String.
     * @return 0 on success, non-zero on failure.
     * @throws NativeUnavailableException if the native library is not loaded.
     */
    fun start(config: String): Long {
        requireNative()
        return XrayRun(config)
    }

    /**
     * Corresponds to: //export XrayStop
     * Stops the running Xray core.
     * @return 0 on success.
     * @throws NativeUnavailableException if the native library is not loaded.
     */
    fun stop(): Long {
        requireNative()
        return XrayStop()
    }

    /**
     * Corresponds to: //export XrayIsRunning
     * Checks if the Xray core is currently active.
     * Returns false directly when the native library is not loaded (query semantics).
     */
    fun isRunning(): Boolean {
        if (!nativeLoaded) return false
        return XrayIsRunning() != 0L
    }

    /**
     * Corresponds to: //export XrayMeasure
     * A utility function to measure something, like connection delay.
     * @param config The Xray JSON configuration.
     * @param url The URL to test against.
     * @return A measurement value, like latency in milliseconds.
     * @throws NativeUnavailableException if the native library is not loaded.
     */
    fun measure(config: String, url: String): Long {
        requireNative()
        return XrayMeasure(config, url)
    }

    // --- Native Function Declarations (private) ---
    // These declarations MUST match the 'export' names in your builder.go file.

    @JvmStatic
    private external fun XrayRun(config: String): Long

    @JvmStatic
    private external fun XrayStop(): Long

    @JvmStatic
    private external fun XrayIsRunning(): Long

    @JvmStatic
    private external fun XrayMeasure(config: String, url: String): Long
}
