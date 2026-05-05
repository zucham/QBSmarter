package com.zucham.qbsmarter.ui.screens.solve

import com.zucham.qbsmarter.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 15s inspection countdown owned by the Solve VM. */
class InspectionTimer(private val scope: CoroutineScope) {

    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /**
     * Start a countdown of [durationMs] ms. [onTimeout] fires exactly once
     * when the timer reaches zero on its own (NOT when [cancel] is called
     * because the user kicked off the solve early).
     */
    fun start(
        durationMs: Long = DEFAULT_DURATION_MS,
        onTimeout: () -> Unit = {},
    ) {
        cancel()
        val end = currentTimeMillis() + durationMs
        _remainingMs.value = durationMs
        _running.value = true
        job = scope.launch {
            while (isActive) {
                val rem = (end - currentTimeMillis()).coerceAtLeast(0)
                _remainingMs.value = rem
                if (rem == 0L) {
                    _running.value = false
                    onTimeout()
                    break
                }
                delay(100L)
            }
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        _running.value = false
        _remainingMs.value = 0L
    }

    companion object { const val DEFAULT_DURATION_MS = 15_000L }
}
