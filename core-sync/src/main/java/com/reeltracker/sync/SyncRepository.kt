package com.reeltracker.sync

import android.content.Context
import android.util.Log
import com.reeltracker.model.IgAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

data class Profile(val id: String, val username: String, val fullName: String?, val inviteCode: String)

data class Friend(
    val id: String,
    val username: String,
    val fullName: String?,
    val day: LocalDate?,       // their most recent day with a row, in their local date
    val reels: Int,
    val seconds: Int,
    val updatedAt: Instant?,
) {
    /** Their count for [today]; a latest row from an earlier day means nothing yet today. */
    fun reelsOn(today: LocalDate) = if (day == today) reels else 0
    fun secondsOn(today: LocalDate) = if (day == today) seconds else 0
}

data class DayTotal(val day: LocalDate, val reels: Int, val seconds: Int)

/** Today's totals as the phone counted them. Sent whole, so resending is always safe. */
data class TodayTotals(val day: LocalDate, val reels: Int, val seconds: Int)

/**
 * Everything that leaves the phone goes through here. Nothing is sent until the person has
 * connected their Instagram account (that is what creates their profile).
 */
class SyncRepository internal constructor(private val api: Supabase, context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _pendingInvite = MutableStateFlow(prefs.getString(K_PENDING, null))
    /** An invite code from a link or the clipboard, waiting for the profile to exist. */
    val pendingInvite: StateFlow<String?> = _pendingInvite.asStateFlow()

    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotEmpty() && BuildConfig.SUPABASE_ANON_KEY.isNotEmpty()

    /** Creates or renames this install's profile from the account read off Instagram. */
    suspend fun connect(account: IgAccount): Profile {
        val o = JSONObject(
            api.rpc("upsert_profile", JSONObject().put("p_username", account.username).put("p_full_name", account.fullName ?: JSONObject.NULL))
        )
        val p = Profile(
            id = o.getString("id"),
            username = o.getString("ig_username"),
            fullName = o.optStringOrNull("ig_full_name"),
            inviteCode = o.getString("invite_code"),
        )
        saveProfile(p)
        return p
    }

    /** Re-reads the profile from the server, e.g. after a reinstall kept the prefs but lost nothing else. */
    suspend fun refreshProfile(): Profile? {
        val uid = api.userId ?: return _profile.value
        val rows = JSONArray(api.select("profiles?id=eq.$uid&select=id,ig_username,ig_full_name,invite_code"))
        val p = if (rows.length() == 0) null else rows.getJSONObject(0).let {
            Profile(it.getString("id"), it.getString("ig_username"), it.optStringOrNull("ig_full_name"), it.getString("invite_code"))
        }
        saveProfile(p)
        return p
    }

    /** Sends today's totals. No-op until connected; failures are logged and retried by the next change. */
    suspend fun push(t: TodayTotals) {
        if (_profile.value == null || !configured) return
        try {
            api.rpc(
                "push_stats",
                JSONObject().put("p_day", t.day.toString()).put("p_reels", t.reels).put("p_seconds", t.seconds.coerceIn(0, 86_400)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "push failed: ${e.message}")
        }
    }

    suspend fun redeem(code: String): Friend {
        val rows = JSONArray(api.rpc("redeem_invite", JSONObject().put("p_code", normalizeCode(code))))
        clearPendingInvite()
        val o = rows.getJSONObject(0)
        return Friend(o.getString("friend_id"), o.getString("ig_username"), o.optStringOrNull("ig_full_name"), null, 0, 0, null)
    }

    suspend fun removeFriend(id: String) {
        api.rpc("remove_friend", JSONObject().put("p_friend", id))
    }

    suspend fun friends(): List<Friend> {
        val rows = JSONArray(api.rpc("friends_stats"))
        return List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            Friend(
                id = o.getString("friend_id"),
                username = o.getString("ig_username"),
                fullName = o.optStringOrNull("ig_full_name"),
                day = o.optStringOrNull("day")?.let(LocalDate::parse),
                reels = o.optInt("reels"),
                seconds = o.optInt("seconds"),
                updatedAt = o.optStringOrNull("updated_at")?.let(::parseInstant),
            )
        }
    }

    /** A friend's last [days] days that have a row, newest first. */
    suspend fun history(friendId: String, days: Int = 7): List<DayTotal> {
        val rows = JSONArray(api.select("daily_stats?user_id=eq.$friendId&select=day,reels,seconds&order=day.desc&limit=$days"))
        return List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            DayTotal(LocalDate.parse(o.getString("day")), o.getInt("reels"), o.getInt("seconds"))
        }
    }

    /** Fires when my or a friend's numbers change, and on every (re)connect. Collect only while visible. */
    fun changes(): Flow<Unit> = dailyStatsChanges(api)

    fun setPendingInvite(code: String) {
        val c = normalizeCode(code)
        prefs.edit().putString(K_PENDING, c).apply()
        _pendingInvite.value = c
    }

    fun clearPendingInvite() {
        prefs.edit().remove(K_PENDING).apply()
        _pendingInvite.value = null
    }

    /** The clipboard is offered once per distinct code, never again after a yes or a no. */
    fun clipboardSeen(code: String): Boolean = prefs.getString(K_CLIP, null) == code
    fun markClipboardSeen(code: String) = prefs.edit().putString(K_CLIP, code).apply()

    var onboardingSkipped: Boolean
        get() = prefs.getBoolean(K_SKIPPED, false)
        set(v) = prefs.edit().putBoolean(K_SKIPPED, v).apply()

    private fun saveProfile(p: Profile?) {
        prefs.edit().apply {
            if (p == null) remove(K_PROFILE)
            else putString(
                K_PROFILE,
                JSONObject().put("id", p.id).put("u", p.username).put("n", p.fullName ?: JSONObject.NULL).put("c", p.inviteCode).toString(),
            )
        }.apply()
        _profile.value = p
    }

    private fun loadProfile(): Profile? = prefs.getString(K_PROFILE, null)?.let {
        runCatching {
            val o = JSONObject(it)
            Profile(o.getString("id"), o.getString("u"), o.optStringOrNull("n"), o.getString("c"))
        }.getOrNull()
    }

    companion object {
        const val SITE = "https://brain-rot-drab.vercel.app/"
        private const val TAG = "BrainrotSync"
        private const val PREFS = "sync"
        private const val K_PROFILE = "profile"
        private const val K_PENDING = "invite.pending"
        private const val K_CLIP = "invite.clipSeen"
        private const val K_SKIPPED = "onboarding.skipped"

        private val CODE = Regex("^[A-HJ-NP-Z2-9]{8}$")

        fun inviteLink(code: String) = "$SITE?invite=$code"

        fun normalizeCode(raw: String) = raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

        /** Finds an invite code in a link (`?invite=`, `brainrot://invite/`) or bare text. */
        fun findCode(text: String?): String? {
            if (text.isNullOrBlank()) return null
            Regex("""invite[=/]([A-Za-z0-9]{8})\b""").find(text)?.let { return normalizeCode(it.groupValues[1]) }
            return normalizeCode(text).takeIf { CODE.matches(it) && text.trim().length <= 12 }
        }

        private fun parseInstant(s: String): Instant? = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

object SyncGraph {
    @Volatile
    private var repo: SyncRepository? = null

    fun repository(context: Context): SyncRepository =
        repo ?: synchronized(this) {
            repo ?: run {
                val app = context.applicationContext
                SyncRepository(
                    Supabase(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, app.getSharedPreferences("sync.auth", Context.MODE_PRIVATE)),
                    app,
                )
            }.also { repo = it }
        }
}
