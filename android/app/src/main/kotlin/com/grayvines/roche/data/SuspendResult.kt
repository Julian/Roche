package com.grayvines.roche.data

import kotlinx.coroutines.CancellationException

internal inline fun <R> runSuspendCatching(block: () -> R): Result<R> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
