package com.reeltracker.service

import com.reeltracker.detect.SignalRecorder
import com.reeltracker.model.IgAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared between the service and the UI, which run in the same process. */
object ReelServiceState {
    val recorder = SignalRecorder(capacity = 2000)

    @Volatile
    var connected: Boolean = false
        internal set

    /**
     * While true, the service looks for Instagram's own-profile screen and reads the username
     * and name off it, once. The only time it reads any text. The app arms it; a capture disarms it.
     */
    internal val captureArmed = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = captureArmed.asStateFlow()

    private val _captured = MutableStateFlow<IgAccount?>(null)
    val captured: StateFlow<IgAccount?> = _captured.asStateFlow()

    fun armCapture() {
        _captured.value = null
        captureArmed.value = true
    }

    fun disarmCapture() {
        captureArmed.value = false
    }

    internal fun deliver(account: IgAccount) {
        _captured.value = account
        captureArmed.value = false
    }

    fun consumeCaptured() {
        _captured.value = null
    }
}
