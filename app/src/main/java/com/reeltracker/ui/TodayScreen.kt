package com.reeltracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.reeltracker.service.BrainRot
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltracker.model.DayStats
import com.reeltracker.model.Sitting

enum class Tracking { On, Stalled, Off }

private val tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun TodayScreen(
    now: Long,
    stats: DayStats,
    lastEventAt: Long?,
    tracking: Tracking,
    is24h: Boolean,
    exportMessage: String?,
    onOpenAccessibility: () -> Unit,
    onExport: () -> Unit,
    onAddWidget: () -> Unit,
    friendsCard: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    val clock = Clock(is24h)
    val bars = WindowInsets.systemBars.asPaddingValues()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = bars.calculateTopPadding() + 16.dp,
            bottom = bars.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(now, clock, tracking, onOpenAccessibility) }
        if (tracking != Tracking.On) item { TrackingCard(tracking, onOpenAccessibility) }
        item { Hero(stats.count, lastEventAt, now, clock) }
        item { BrainCard(stats.count, onAddWidget) }
        item { friendsCard() }
        item { StatRow(stats) }
        item {
            Panel("By hour") {
                HourlyChart(stats.hourly, currentHour = java.time.LocalTime.now().hour, is24h = is24h)
            }
        }
        item { Sittings(stats, now, clock) }
        item { Footer(exportMessage, onExport) }
    }
}

@Composable
private fun Header(now: Long, clock: Clock, tracking: Tracking, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(clock.date(now), style = MaterialTheme.typography.titleMedium, color = p.textSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    clock.clock(now),
                    style = tnum.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
                    color = p.textPrimary,
                )
                clock.amPm(now)?.let {
                    Text(
                        " $it",
                        style = MaterialTheme.typography.titleMedium,
                        color = p.textSecondary,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
        }
        StatusPill(tracking, onClick, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun StatusPill(tracking: Tracking, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val (dot, label) = when (tracking) {
        Tracking.On -> p.good to "Tracking"
        Tracking.Stalled -> p.accent to "Not running"
        Tracking.Off -> p.accent to "Off"
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(p.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tracking == Tracking.On) PulsingDot(dot) else Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = p.textPrimary)
    }
}

@Composable
internal fun PulsingDot(color: Color) {
    val a = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.size(8.dp).alpha(a.value).clip(CircleShape).background(color))
}

@Composable
private fun TrackingCard(tracking: Tracking, onClick: () -> Unit) {
    val p = LocalPalette.current
    Panel(if (tracking == Tracking.Off) "Tracking is off" else "Tracking stopped") {
        Text(
            if (tracking == Tracking.Off) {
                "Turn on Brainrot in Accessibility settings. It reads which list scrolled and its position, nothing else."
            } else {
                "The service is enabled but isn't running. The system probably stopped it to save battery. Turn it off and on again in Accessibility settings, and set battery use for Brainrot to Unrestricted."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
        ) { Text("Open Accessibility settings") }
    }
}

@Composable
private fun Hero(count: Int, lastEventAt: Long?, now: Long, clock: Clock) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Text("Reels watched today", style = MaterialTheme.typography.titleMedium, color = p.textSecondary)
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "count",
        ) { n ->
            Text(
                "$n",
                style = TextStyle(
                    fontSize = 112.sp,
                    lineHeight = 112.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-4).sp,
                ),
                color = p.textPrimary,
            )
        }
        Text(
            lastEventAt?.let { "Last one counted ${clock.ago(it, now)}" } ?: "Nothing counted yet",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textMuted,
        )
    }
}

/**
 * Today's brain, the same sprite the overlay and the widget show, with the way to put that
 * widget on the home screen.
 */
@Composable
private fun BrainCard(count: Int, onAddWidget: () -> Unit) {
    val p = LocalPalette.current
    val stage = BrainRot.stage(count)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(targetState = stage, label = "brain") { s ->
            Image(
                painter = painterResource(BrainRot.sprite(s)),
                contentDescription = "Brain: ${BrainRot.label(s)}",
                modifier = Modifier.size(width = 76.dp, height = 64.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                BrainRot.label(stage),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = p.textPrimary,
            )
            Text(
                BrainRot.untilNext(count)?.let { "$it more until it gets worse" } ?: "It can't get any worse",
                style = MaterialTheme.typography.bodySmall,
                color = p.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onAddWidget,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text("Add to home screen", color = p.accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatRow(stats: DayStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("In Reels", if (stats.count == 0) "0 min" else duration(stats.scrollingMs), Modifier.weight(1f))
        StatTile("Sittings", "${stats.sittings.size}", Modifier.weight(1f))
        StatTile("Per reel", stats.secondsPerReel?.let { "${it}s" } ?: "–", Modifier.weight(1f))
    }
}

@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(p.card)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.textSecondary, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(value, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary, maxLines = 1)
    }
}

@Composable
private fun Sittings(stats: DayStats, now: Long, clock: Clock) {
    val p = LocalPalette.current
    Panel("When you scrolled", subtitle = "A break of 3 min or more starts a new sitting") {
        if (stats.sittings.isEmpty()) {
            Text("No reels yet today.", style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
            return@Panel
        }
        val maxReels = stats.sittings.maxOf { it.reels }
        val newestFirst = stats.sittings.asReversed()
        newestFirst.forEachIndexed { i, s ->
            val live = i == 0 && now - s.end < DayStats.SITTING_GAP_MS
            SittingRow(s, maxReels, live, clock)
            if (i != newestFirst.lastIndex) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(p.hairline))
            }
        }
    }
}

@Composable
private fun SittingRow(s: Sitting, maxReels: Int, live: Boolean, clock: Clock) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clock.range(s.start, s.end),
                    style = tnum.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    color = p.textPrimary,
                )
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.clip(CircleShape).background(p.cardRaised).padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulsingDot(p.accent)
                        Spacer(Modifier.width(5.dp))
                        Text("Now", style = MaterialTheme.typography.labelSmall, color = p.textPrimary)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(duration(s.durationMs), style = MaterialTheme.typography.bodySmall, color = p.textMuted)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.reels}", style = tnum.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary)
            Text(
                if (s.reels == 1) " reel" else " reels",
                style = MaterialTheme.typography.bodySmall,
                color = p.textMuted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    // Meter: share of the biggest sitting. Track is a lighter step of the same hue.
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.accentSoft.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(s.reels.toFloat() / maxReels).height(4.dp).clip(CircleShape).background(p.accent))
    }
}

@Composable
private fun Footer(exportMessage: String?, onExport: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onExport) {
            Text("Export debug capture", color = p.textSecondary)
        }
        exportMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = p.textMuted)
        }
        Spacer(Modifier.height(4.dp))
        Text("Counted on this phone. Only daily totals are shared, and only once you connect.", style = MaterialTheme.typography.bodySmall, color = p.textMuted)
    }
}

@Composable
internal fun Panel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .padding(18.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = p.textPrimary)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = p.textMuted)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}
