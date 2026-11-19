package com.grayvines.roche.core

import kotlinx.coroutines.CancellationException

/**
 * Runs a suspending [block] and wraps non-cancellation failures in [Result], always rethrowing
 * [CancellationException] to preserve structured concurrency.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }
