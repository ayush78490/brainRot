package com.reeltracker.detect

import com.reeltracker.model.Decision
import com.reeltracker.model.UiSignal

/**
 * OUTSIDE <-> REELS state machine. Pure: no Android, no I/O, no clocks of its own.
 *
 * Accuracy policy (design section 9): whenever we can't be sure we're in Reels, exit. A missed
 * reel makes the number low; a phantom one makes it wrong forever.
 *
 * Returns a list because one signal can mean two things: the first pager scroll after arriving
 * from somewhere other than the Reels tab both enters Reels and confirms a reel.
 */
class Detector(
    private val strategies: List<DetectionStrategy> = listOf(IndexStrategy()),
) {
    var inReels = false
        private set

    private var pinned: DetectionStrategy? = null
    private val recent = ArrayDeque<UiSignal>(RECENT)

    /** The last [RECENT] signals, for dumping on enter/exit. */
    fun recentSignals(): List<UiSignal> = recent.toList()

    fun reset() {
        inReels = false
        pinned = null
        recent.clear()
        strategies.forEach { it.reset() }
    }

    fun accept(signal: UiSignal): List<Decision> {
        if (recent.size == RECENT) recent.removeFirst()
        recent.addLast(signal)

        return when (signal) {
            is UiSignal.Foreground ->
                if (signal.pkg != ReelsContext.PACKAGE && !ReelsContext.isOverlay(signal.pkg, signal.cls)) exit()
                else NONE

            is UiSignal.WindowState ->
                if (!ReelsContext.isHostWindow(signal.cls)) exit() else NONE

            is UiSignal.Selected -> when {
                ReelsContext.isReelsTab(signal.sourceId) -> enter()
                ReelsContext.isOtherTab(signal.sourceId) -> exit()
                else -> NONE
            }

            is UiSignal.Scrolled -> onScroll(signal)
        }
    }

    private fun onScroll(s: UiSignal.Scrolled): List<Decision> {
        if (!ReelsContext.isPager(s.sourceId)) return NONE

        val out = ArrayList<Decision>(2)
        if (!inReels) out += enter()

        val strategy = pinned
            ?: strategies.firstOrNull { it.supports(SignalProbe(recent.toList())) }?.also { pinned = it }
            ?: return out + exit()

        when (val d = strategy.accept(s)) {
            Decision.ExitReels -> out += exit()
            Decision.Ignore -> Unit
            else -> out += d
        }
        return out
    }

    private fun enter(): List<Decision> {
        if (inReels) return NONE
        inReels = true
        pinned = null // re-picked on the first pager scroll of this session
        return listOf(Decision.EnterReels)
    }

    private fun exit(): List<Decision> {
        if (!inReels) return NONE
        inReels = false
        pinned = null
        return listOf(Decision.ExitReels)
    }

    private companion object {
        const val RECENT = 40
        val NONE = emptyList<Decision>()
    }
}
