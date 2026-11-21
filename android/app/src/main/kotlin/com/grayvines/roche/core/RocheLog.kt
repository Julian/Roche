package com.grayvines.roche.core

import android.util.Log

/**
 * Lightweight diagnostic logger that forwards to [android.util.Log] on Android and degrades
 * silently under plain JVM unit tests where Android framework stubs throw.
 */
internal object RocheLog {
    private const val TAG_PREFIX = "Roche."

    fun debug(tag: String, message: String) {
        runCatching { Log.d(TAG_PREFIX + tag, message) }
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) {
                Log.w(TAG_PREFIX + tag, message, error)
            } else {
                Log.w(TAG_PREFIX + tag, message)
            }
        }
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) {
                Log.e(TAG_PREFIX + tag, message, error)
            } else {
                Log.e(TAG_PREFIX + tag, message)
            }
        }
    }
}
