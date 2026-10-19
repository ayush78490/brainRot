package com.reeltracker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.reeltracker.data.ReelGraph
import com.reeltracker.data.ReelRepository
import com.reeltracker.detect.Detector
import com.reeltracker.detect.RawEvent
import com.reeltracker.detect.SignalMapper
import com.reeltracker.model.Decision
import com.reeltracker.sync.SyncGraph
import com.reeltracker.sync.SyncRepository
import com.reeltracker.sync.TodayTotals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Adapter. Android types live here and nowhere else: AccessibilityEvent -> UiSignal -> Detector,
 * then Decisions are acted on. No tree traversal. The only text read is the username and name on
 * Instagram's own-profile screen, and only while the app has armed a capture ([ProfileReader]).
 */
class ReelAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector = Detector()
    private lateinit var repo: ReelRepository
    private lateinit var sync: SyncRepository
    private lateinit var overlay: OverlayController

    /**
     * Decisions are applied strictly in order by one consumer. A Room call suspends, so launching
     * one coroutine per decision would let a record() run before the openSession() it depends on.
     */
    private val writes = Channel<Decision>(Channel.UNLIMITED)

    override fun onServiceConnected() {
        repo = ReelGraph.repository(this)
        overlay = OverlayController(this, repo.todayCount, scope)
        detector.reset()
        scope.launch(Dispatchers.IO) { applyWrites() }
        // Home-screen widget follows the count live, including the reset at midnight.
        scope.launch(Dispatchers.IO) {
            repo.todayCount.distinctUntilChanged().collect { ReelWidgetProvider.update(this@ReelAccessibilityService, it) }
        }
        sync = SyncGraph.repository(this)
        scope.launch(Dispatchers.IO) { pushToFriends() }
        scope.launch { watchForProfile() }
        ReelServiceState.connected = true
    }

    /**
     * Friends see today's totals within a couple of seconds. Totals, not increments, so a failed
     * or repeated send can't miscount. At most one send per [PUSH_EVERY_MS]; the newest total wins.
     */
    private suspend fun pushToFriends() {
        repo.todayStats
            .map { TodayTotals(LocalDate.now(), it.count, (it.scrollingMs / 1000).toInt()) }
            .distinctUntilChanged()
            .conflate()
            .collect {
                sync.push(it)
                delay(PUSH_EVERY_MS)
            }
    }

    /** While a capture is armed, check the screen every half second until Instagram's own profile is up. */
    private suspend fun watchForProfile() {
        ReelServiceState.captureArmed.collectLatest { armed ->
            if (!armed) return@collectLatest
            val deadline = SystemClock.elapsedRealtime() + CAPTURE_WINDOW_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val account = runCatching { ProfileReader.read(rootInActiveWindow) }.getOrNull()
                if (account != null) {
                    ReelServiceState.deliver(account)
                    // Back to Brainrot to confirm. Allowed from the background for accessibility services.
                    packageManager.getLaunchIntentForPackage(packageName)?.let {
                        startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                    return@collectLatest
                }
                delay(500)
            }
            ReelServiceState.disarmCapture()
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val type = e.eventType
        val pkg = e.packageName?.toString()

        var sourceId: String? = null
        var sourceClass: String? = null
        if (SignalMapper.needsSource(type, pkg)) {
            val src = e.source ?: return // §9: no source node, no guess
            sourceId = src.viewIdResourceName
            sourceClass = src.className?.toString()
        }

        val signal = SignalMapper.map(
            RawEvent(
                type = type,
                pkg = pkg,
                cls = e.className?.toString(),
                fromIndex = e.fromIndex,
                toIndex = e.toIndex,
                itemCount = e.itemCount,
                scrollDeltaY = e.scrollDeltaY,
                scrollY = e.scrollY,
                sourceId = sourceId,
                sourceClass = sourceClass,
                descHash = e.contentDescription?.hashCode() ?: 0,
                t = SystemClock.elapsedRealtime(),
            )
        ) ?: return

        val decisions = detector.accept(signal)
        ReelServiceState.recorder.record(signal, decisions)
        for (d in decisions) {
            when (d) {
                Decision.EnterReels -> overlay.show()
                Decision.ExitReels -> overlay.hide()
                else -> Unit
            }
            writes.trySend(d)
        }
    }

    private suspend fun applyWrites() {
        repo.closeOpenSessions() // anything left open by process death
        var session: Long? = null
        for (d in writes) when (d) {
            Decision.EnterReels -> session = repo.openSession()
            Decision.ExitReels -> session = session?.let { repo.closeSession(it); null }
            is Decision.ReelConfirmed -> session?.let { repo.record(it, d.key, d.strategyId) }
            Decision.Ignore -> Unit
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val PUSH_EVERY_MS = 2_000L
        const val CAPTURE_WINDOW_MS = 3 * 60_000L
    }

    override fun onDestroy() {
        ReelServiceState.connected = false
        if (::overlay.isInitialized) overlay.hide()
        writes.close()
        scope.cancel()
        super.onDestroy()
    }
}
