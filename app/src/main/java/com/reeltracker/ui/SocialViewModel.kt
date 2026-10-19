package com.reeltracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reeltracker.data.ReelGraph
import com.reeltracker.model.IgAccount
import com.reeltracker.sync.DayTotal
import com.reeltracker.sync.Friend
import com.reeltracker.sync.Profile
import com.reeltracker.sync.SyncGraph
import com.reeltracker.sync.TodayTotals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Profile, friends and invites. All network calls go through [com.reeltracker.sync.SyncRepository]. */
class SocialViewModel(app: Application) : AndroidViewModel(app) {

    private val sync = SyncGraph.repository(app)
    private val reels = ReelGraph.repository(app)

    val profile: StateFlow<Profile?> = sync.profile
    val pendingInvite: StateFlow<String?> = sync.pendingInvite

    private val _friends = MutableStateFlow<List<Friend>?>(null)   // null until first load
    val friends: StateFlow<List<Friend>?> = _friends.asStateFlow()

    private val _history = MutableStateFlow<Map<String, List<DayTotal>>>(emptyMap())
    val history: StateFlow<Map<String, List<DayTotal>>> = _history.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-shot text for a snackbar-style line; the screen clears it once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _skipped = MutableStateFlow(sync.onboardingSkipped)
    val onboardingSkipped: StateFlow<Boolean> = _skipped.asStateFlow()

    /** An invite code found on the clipboard, waiting for a yes or no. */
    private val _clipboardOffer = MutableStateFlow<String?>(null)
    val clipboardOffer: StateFlow<String?> = _clipboardOffer.asStateFlow()

    /** Fires with the new friend after an invite is redeemed, so the UI can open Friends. */
    private val _justAdded = MutableStateFlow<Friend?>(null)
    val justAdded: StateFlow<Friend?> = _justAdded.asStateFlow()

    init {
        // A link tapped before the profile existed is redeemed as soon as it does.
        viewModelScope.launch {
            combine(profile, pendingInvite) { p, code -> if (p != null) code else null }
                .collect { code -> if (code != null) redeem(code) }
        }
    }

    fun skipOnboarding() {
        sync.onboardingSkipped = true
        _skipped.value = true
    }

    fun startOnboarding() {
        sync.onboardingSkipped = false
        _skipped.value = false
    }

    fun connect(account: IgAccount) = launchBusy {
        sync.connect(account)
        pushNow()
        refresh()
    }

    fun redeem(code: String) = launchBusy {
        try {
            val friend = sync.redeem(code)
            _message.value = "You and @${friend.username} are now friends"
            _justAdded.value = friend
            refresh()
        } finally {
            sync.clearPendingInvite() // a bad code must not be retried on every launch
        }
    }

    fun remove(friend: Friend) = launchBusy {
        sync.removeFriend(friend.id)
        _message.value = "Removed @${friend.username}"
        refresh()
    }

    fun loadHistory(friendId: String) {
        viewModelScope.launch {
            runCatching { sync.history(friendId) }.onSuccess { h -> _history.value = _history.value + (friendId to h) }
        }
    }

    /** Sends today's totals now, for when the service is off or hasn't changed since connecting. */
    fun pushNow() {
        if (profile.value == null) return
        viewModelScope.launch {
            val s = reels.todayStats.first()
            sync.push(TodayTotals(LocalDate.now(), s.count, (s.scrollingMs / 1000).toInt()))
        }
    }

    fun refresh() {
        if (profile.value == null) return
        viewModelScope.launch {
            runCatching { sync.friends() }
                .onSuccess { _friends.value = it }
                .onFailure { if (_friends.value == null) _message.value = it.message }
        }
    }

    /** Keeps [friends] live. Call while the UI is visible; cancelling the caller closes the socket. */
    suspend fun live() {
        sync.changes().conflate().collect {
            refresh()
            delay(300) // a burst of changes becomes one refetch
        }
    }

    /** Clipboard text from the window gaining focus; offers a code once, unless it's my own. */
    fun onClipboard(text: String?) {
        val code = com.reeltracker.sync.SyncRepository.findCode(text) ?: return
        if (code == profile.value?.inviteCode || sync.clipboardSeen(code) || code == pendingInvite.value) return
        _clipboardOffer.value = code
    }

    fun answerClipboard(accept: Boolean) {
        val code = _clipboardOffer.value ?: return
        sync.markClipboardSeen(code)
        _clipboardOffer.value = null
        if (accept) acceptInvite(code)
    }

    /** From a link or an accepted clipboard offer: redeem now, or after connecting. */
    fun acceptInvite(code: String) {
        sync.setPendingInvite(code)
        if (profile.value == null) startOnboarding()
    }

    fun messageShown() { _message.value = null }
    fun addedShown() { _justAdded.value = null }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                _message.value = e.message ?: "Something went wrong"
            } finally {
                _busy.value = false
            }
        }
    }
}
