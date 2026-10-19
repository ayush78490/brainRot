package com.reeltracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltracker.model.IgAccount
import com.reeltracker.service.BrainRot
import com.reeltracker.sync.DayTotal
import com.reeltracker.sync.Friend
import com.reeltracker.sync.Profile
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val tnum = TextStyle(fontFeatureSettings = "tnum")

/** A friend is "live" if their numbers moved in the last two minutes. */
private const val LIVE_MS = 2 * 60_000L

// ---------------------------------------------------------------- Onboarding

enum class ConnectStep { EnableService, Explain, Waiting, Confirm }

@Composable
fun OnboardingScreen(
    step: ConnectStep,
    captured: IgAccount?,
    invitedCode: String?,
    busy: Boolean,
    message: String?,
    onOpenAccessibility: () -> Unit,
    onOpenInstagram: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    val p = LocalPalette.current
    val bars = WindowInsets.systemBars.asPaddingValues()
    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .padding(start = 24.dp, end = 24.dp, top = bars.calculateTopPadding() + 40.dp, bottom = bars.calculateBottomPadding() + 24.dp),
    ) {
        Image(
            painter = painterResource(BrainRot.sprite(if (step == ConnectStep.Confirm) 1 else 0)),
            contentDescription = null,
            modifier = Modifier.size(width = 96.dp, height = 80.dp),
        )
        Spacer(Modifier.height(20.dp))
        invitedCode?.let {
            Text(
                "A friend invited you. Connect to accept.",
                style = MaterialTheme.typography.labelLarge,
                color = p.accent,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            when (step) {
                ConnectStep.Confirm -> "Is this you?"
                else -> "Connect your Instagram"
            },
            style = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
            color = p.textPrimary,
        )
        Spacer(Modifier.height(12.dp))

        when (step) {
            ConnectStep.EnableService -> {
                Body("Brainrot reads your username off your own Instagram profile, so it needs its accessibility service on first. It's the same one that counts your reels.")
                Spacer(Modifier.height(24.dp))
                Primary("Open Accessibility settings", onOpenAccessibility)
            }
            ConnectStep.Explain -> {
                Body("So friends can find you and see how rotten your brain is, Brainrot needs your Instagram username.")
                Spacer(Modifier.height(16.dp))
                Bullet("It reads your username and name off your own profile screen, once.")
                Bullet("Your friends see your username, name, and how many reels you watched each day, and nothing else.")
                Bullet("No Instagram login, no password, and nothing is posted.")
                Spacer(Modifier.height(24.dp))
                Primary("Open my Instagram profile", onOpenInstagram)
            }
            ConnectStep.Waiting -> {
                Body("In Instagram, tap the Profile tab (your picture, bottom right). Brainrot reads it and comes back here.")
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = p.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Waiting for your profile…", color = p.textSecondary)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onOpenInstagram) { Text("Open Instagram again", color = p.textPrimary) }
            }
            ConnectStep.Confirm -> {
                val a = captured!!
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.card).padding(18.dp)
                ) {
                    Text("@${a.username}", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary)
                    a.fullName?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = p.textSecondary) }
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Primary(if (busy) "Connecting…" else "That's me", onConfirm, enabled = !busy)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onRetry, enabled = !busy) { Text("Try again", color = p.textSecondary) }
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = p.accent)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Not now. Just count my reels.", color = p.textMuted)
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = LocalPalette.current.textSecondary)
}

@Composable
private fun Bullet(text: String) {
    val p = LocalPalette.current
    Row(Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(p.accent))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
    }
}

@Composable
private fun Primary(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = LocalPalette.current.accent, contentColor = Color.White),
    ) { Text(label) }
}

// ---------------------------------------------------------------- Card on the Today screen

@Composable
fun FriendsCard(
    profile: Profile?,
    friends: List<Friend>?,
    myCount: Int,
    now: Long,
    onOpen: () -> Unit,
    onConnect: () -> Unit,
) {
    val p = LocalPalette.current
    if (profile == null) {
        Panel("Friends", subtitle = "Compare brains with your friends") {
            Text(
                "Connect your Instagram to get an invite link. Friends see each other's daily reel counts, live.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
            )
            Spacer(Modifier.height(14.dp))
            Primary("Connect Instagram", onConnect)
        }
        return
    }
    val today = LocalDate.now()
    Column(Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onOpen)) {
        Panel("Friends", subtitle = if (friends.isNullOrEmpty()) "No friends yet. Tap to invite someone." else "Today, live") {
            val rows = leaderboard(profile, friends.orEmpty(), myCount, today).take(4)
            rows.forEachIndexed { i, r ->
                LeaderRow(i + 1, r, now, compact = true)
                if (i != rows.lastIndex) Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("See all and invite →", style = MaterialTheme.typography.labelLarge, color = p.accent)
        }
    }
}

private data class Entry(val id: String?, val username: String, val fullName: String?, val reels: Int, val updatedAt: Long?)

private fun leaderboard(me: Profile, friends: List<Friend>, myCount: Int, today: LocalDate): List<Entry> =
    (friends.map { Entry(it.id, it.username, it.fullName, it.reelsOn(today), it.updatedAt?.toEpochMilli()) } +
        Entry(null, me.username, "You", myCount, null))
        .sortedByDescending { it.reels }

@Composable
private fun LeaderRow(rank: Int, e: Entry, now: Long, compact: Boolean, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val live = e.updatedAt != null && now - e.updatedAt < LIVE_MS
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick) else Modifier)
            .padding(vertical = if (compact) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = tnum.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = p.textMuted, modifier = Modifier.width(22.dp))
        Image(
            painter = painterResource(BrainRot.sprite(BrainRot.stage(e.reels))),
            contentDescription = BrainRot.label(BrainRot.stage(e.reels)),
            modifier = Modifier.size(width = if (compact) 38.dp else 48.dp, height = if (compact) 32.dp else 40.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (e.id == null) "You" else "@${e.username}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = p.textPrimary,
                    maxLines = 1,
                )
                if (live) {
                    Spacer(Modifier.width(6.dp))
                    PulsingDot(p.accent)
                }
            }
            if (!compact) {
                Text(
                    if (e.id == null) "@${e.username}" else (e.fullName ?: BrainRot.label(BrainRot.stage(e.reels))),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textMuted,
                    maxLines = 1,
                )
            }
        }
        Text("${e.reels}", style = tnum.copy(fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary)
    }
}

// ---------------------------------------------------------------- Friends screen

@Composable
fun FriendsScreen(
    profile: Profile,
    friends: List<Friend>?,
    myCount: Int,
    now: Long,
    busy: Boolean,
    message: String?,
    onBack: () -> Unit,
    onInvite: () -> Unit,
    onCopyCode: () -> Unit,
    onRedeem: (String) -> Unit,
    onOpenFriend: (Friend) -> Unit,
) {
    val p = LocalPalette.current
    val bars = WindowInsets.systemBars.asPaddingValues()
    var code by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now()

    LazyColumn(
        Modifier.fillMaxSize().background(p.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = bars.calculateTopPadding() + 8.dp, bottom = bars.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopBar("Friends", onBack) }
        item {
            Panel("Your invite code", subtitle = "Anyone who enters it becomes your friend, both ways") {
                Text(
                    profile.inviteCode,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                    color = p.textPrimary,
                    modifier = Modifier.clickable(onClick = onCopyCode),
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Primary("Invite a friend", onInvite)
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = onCopyCode) { Text("Copy code", color = p.textPrimary) }
                }
            }
        }
        item {
            Panel("Got a code?") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(8) },
                        placeholder = { Text("ABCD2345", color = p.textMuted) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, letterSpacing = 2.sp, color = p.textPrimary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (code.length == 8) { onRedeem(code); code = "" } }),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Primary(if (busy) "…" else "Add", { onRedeem(code); code = "" }, enabled = code.length == 8 && !busy)
                }
                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                }
            }
        }
        item {
            Panel("Today", subtitle = "Updates live while they scroll") {
                when {
                    friends == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading…", color = p.textMuted)
                    }
                    else -> {
                        val rows = leaderboard(profile, friends, myCount, today)
                        rows.forEachIndexed { i, r ->
                            LeaderRow(i + 1, r, now, compact = false, onClick = r.id?.let { id -> { friends.first { it.id == id }.let(onOpenFriend) } })
                        }
                        if (friends.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("No friends yet. Send your invite link.", style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(p.card).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("←", style = TextStyle(fontSize = 20.sp), color = p.textPrimary) }
        Spacer(Modifier.width(14.dp))
        Text(title, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary)
    }
}

// ---------------------------------------------------------------- One friend

private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM")

@Composable
internal fun FriendScreen(
    friend: Friend,
    history: List<DayTotal>?,
    now: Long,
    clock: Clock,
    onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalPalette.current
    val bars = WindowInsets.systemBars.asPaddingValues()
    val today = LocalDate.now()
    val count = friend.reelsOn(today)
    val stage = BrainRot.stage(count)
    var confirm by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(p.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = bars.calculateTopPadding() + 8.dp, bottom = bars.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopBar("@${friend.username}", onBack) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(BrainRot.sprite(stage)),
                    contentDescription = "Brain: ${BrainRot.label(stage)}",
                    modifier = Modifier.size(width = 150.dp, height = 126.dp),
                )
                friend.fullName?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = p.textSecondary) }
                Text(
                    "$count",
                    style = TextStyle(fontSize = 96.sp, lineHeight = 96.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-3).sp),
                    color = p.textPrimary,
                )
                Text("reels today · ${BrainRot.label(stage)}", style = MaterialTheme.typography.titleMedium, color = p.textSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    friend.updatedAt?.let { "Updated ${clock.ago(it.toEpochMilli(), now)}" } ?: "Hasn't scrolled since connecting",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("In Reels today", friend.secondsOn(today).let { if (it == 0) "0 min" else duration(it * 1000L) }, Modifier.weight(1f))
                StatTile("Until next stage", BrainRot.untilNext(count)?.toString() ?: "–", Modifier.weight(1f))
            }
        }
        item {
            Panel("Last 7 days") {
                when {
                    history == null -> Text("Loading…", color = p.textMuted)
                    history.isEmpty() -> Text("No days yet.", color = p.textMuted)
                    else -> {
                        val max = history.maxOf { it.reels }.coerceAtLeast(1)
                        history.forEachIndexed { i, d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (d.day == today) "Today" else dayFormat.format(d.day),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = p.textPrimary,
                                    modifier = Modifier.width(96.dp),
                                )
                                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(p.accentSoft.copy(alpha = 0.45f))) {
                                    Box(Modifier.fillMaxWidth(d.reels.toFloat() / max).height(6.dp).clip(CircleShape).background(p.accent))
                                }
                                Text(
                                    "${d.reels}",
                                    style = tnum.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                                    color = p.textPrimary,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(52.dp),
                                )
                            }
                            if (i != history.lastIndex) Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove friend", color = p.textMuted)
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove @${friend.username}?") },
            text = { Text("You'll stop seeing each other's counts. They can add you again with your code.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remove", color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ClipboardInviteDialog(code: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Add a friend?") },
        text = { Text("You copied a Brainrot invite ($code). Add them as a friend?") },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Add friend", color = LocalPalette.current.accent) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("No") } },
    )
}
