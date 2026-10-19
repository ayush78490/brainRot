package com.reeltracker.ui

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.reeltracker.service.ReelAccessibilityService
import com.reeltracker.service.ReelServiceState
import com.reeltracker.service.ReelWidgetProvider
import com.reeltracker.sync.SyncRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: TodayViewModel by viewModels()
    private val social: SocialViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)

        // Friends' numbers stay live while the app is on screen, and only then.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                social.profile.collectLatest { if (it != null) social.live() }
            }
        }

        setContent {
            ReelTheme {
                val context = LocalContext.current
                val stats by vm.stats.collectAsStateWithLifecycle()
                val last by vm.lastEventAt.collectAsStateWithLifecycle()
                val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()

                val profile by social.profile.collectAsStateWithLifecycle()
                val friends by social.friends.collectAsStateWithLifecycle()
                val history by social.history.collectAsStateWithLifecycle()
                val busy by social.busy.collectAsStateWithLifecycle()
                val message by social.message.collectAsStateWithLifecycle()
                val skipped by social.onboardingSkipped.collectAsStateWithLifecycle()
                val pendingInvite by social.pendingInvite.collectAsStateWithLifecycle()
                val clipboardOffer by social.clipboardOffer.collectAsStateWithLifecycle()
                val justAdded by social.justAdded.collectAsStateWithLifecycle()
                val captured by ReelServiceState.captured.collectAsStateWithLifecycle()
                val capturing by ReelServiceState.capturing.collectAsStateWithLifecycle()

                var tracking by remember { mutableStateOf(trackingState(context)) }
                var is24h by remember { mutableStateOf(DateFormat.is24HourFormat(context)) }
                LifecycleResumeEffect(Unit) {
                    tracking = trackingState(context)
                    is24h = DateFormat.is24HourFormat(context)
                    // Catches the widget up if the service was stopped while counts changed.
                    ReelWidgetProvider.refresh(context)
                    social.pushNow()
                    social.refresh()
                    onPauseOrDispose { }
                }

                // "today", "friends" or "friend:<id>"
                var screen by rememberSaveable { mutableStateOf("today") }
                val now = rememberNow()

                LaunchedEffect(profile) { if (profile != null) ReelServiceState.consumeCaptured() }
                LaunchedEffect(justAdded) {
                    if (justAdded != null) {
                        screen = "friends"
                        social.addedShown()
                    }
                }
                LaunchedEffect(message) {
                    if (message != null) {
                        if (screen == "today" && profile != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        delay(5_000)
                        social.messageShown()
                    }
                }

                val me = profile
                val onboarding = me == null && (!skipped || captured != null)
                when {
                    onboarding -> OnboardingScreen(
                        step = when {
                            tracking != Tracking.On -> ConnectStep.EnableService
                            captured != null -> ConnectStep.Confirm
                            capturing -> ConnectStep.Waiting
                            else -> ConnectStep.Explain
                        },
                        captured = captured,
                        invitedCode = pendingInvite,
                        busy = busy,
                        message = message,
                        onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOpenInstagram = { openInstagramProfile() },
                        onConfirm = { captured?.let(social::connect) },
                        onRetry = {
                            ReelServiceState.consumeCaptured()
                            openInstagramProfile()
                        },
                        onSkip = {
                            ReelServiceState.disarmCapture()
                            ReelServiceState.consumeCaptured()
                            social.skipOnboarding()
                        },
                    )

                    me != null && screen == "friends" -> {
                        BackHandler { screen = "today" }
                        FriendsScreen(
                            profile = me,
                            friends = friends,
                            myCount = stats.count,
                            now = now,
                            busy = busy,
                            message = message,
                            onBack = { screen = "today" },
                            onInvite = { shareInvite(me.inviteCode) },
                            onCopyCode = { copyCode(me.inviteCode) },
                            onRedeem = social::redeem,
                            onOpenFriend = { screen = "friend:${it.id}" },
                        )
                    }

                    me != null && screen.startsWith("friend:") -> {
                        val id = screen.removePrefix("friend:")
                        val friend = friends?.firstOrNull { it.id == id }
                        BackHandler { screen = "friends" }
                        if (friend == null) {
                            // Removed, or they unfriended us.
                            LaunchedEffect(Unit) { screen = "friends" }
                        } else {
                            LaunchedEffect(friend.id, friend.updatedAt) { social.loadHistory(friend.id) }
                            FriendScreen(
                                friend = friend,
                                history = history[friend.id],
                                now = now,
                                clock = Clock(is24h),
                                onBack = { screen = "friends" },
                                onRemove = {
                                    social.remove(friend)
                                    screen = "friends"
                                },
                            )
                        }
                    }

                    else -> TodayScreen(
                        now = now,
                        stats = stats,
                        lastEventAt = last,
                        tracking = tracking,
                        is24h = is24h,
                        exportMessage = exportMessage,
                        onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onExport = vm::exportCapture,
                        onAddWidget = { requestWidget(context) },
                        friendsCard = {
                            FriendsCard(
                                profile = me,
                                friends = friends,
                                myCount = stats.count,
                                now = now,
                                onOpen = { screen = "friends" },
                                onConnect = social::startOnboarding,
                            )
                        },
                    )
                }

                clipboardOffer?.let { code -> ClipboardInviteDialog(code, social::answerClipboard) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** https://brain-rot-drab.vercel.app/?invite=CODE (App Link) or brainrot://invite/CODE. */
    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        SyncRepository.findCode(intent.data?.toString())?.let(social::acceptInvite)
    }

    /**
     * The website copies the invite link when the APK is downloaded; a fresh install can't get it
     * any other way. Android only lets the focused app read the clipboard, hence here.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        if (clip.itemCount > 0) social.onClipboard(clip.getItemAt(0).coerceToText(this)?.toString())
    }

    private fun openInstagramProfile() {
        val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
        if (launch == null) {
            Toast.makeText(this, "Instagram isn't installed", Toast.LENGTH_LONG).show()
            return
        }
        ReelServiceState.armCapture()
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shareInvite(code: String) {
        val text = "I'm counting how many reels I watch with Brainrot. Add me so we can see each other's brain rot: " +
            SyncRepository.inviteLink(code)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Invite a friend"))
    }

    private fun copyCode(code: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Brainrot invite", SyncRepository.inviteLink(code)))
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}

/** Wall clock, ticking on each second boundary. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000 - value % 1_000)
        }
    }
    return now
}

/**
 * Asks the launcher to place the widget; it shows its own "Add to home screen?" prompt.
 * Launchers that cannot pin (some older or third-party ones) get the manual steps instead.
 */
private fun requestWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, ReelWidgetProvider::class.java)
    val pinned = manager.isRequestPinAppWidgetSupported &&
        manager.requestPinAppWidget(provider, null, null)
    if (!pinned) {
        Toast.makeText(
            context,
            "Long-press your home screen, tap Widgets, and add Brainrot.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun trackingState(context: Context): Tracking {
    val me = ComponentName(context, ReelAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')
        ?.any { ComponentName.unflattenFromString(it) == me } == true
    return when {
        !enabled -> Tracking.Off
        ReelServiceState.connected -> Tracking.On
        else -> Tracking.Stalled
    }
}
