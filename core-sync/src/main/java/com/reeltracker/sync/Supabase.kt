package com.reeltracker.sync

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A message a person can read: from a database `raise exception`, or a network failure. */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class Session(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String)

/**
 * The few Supabase endpoints Brainrot needs, over plain HTTP. Every install is an anonymous
 * Supabase user; the session is kept in [prefs] and refreshed before it expires.
 */
internal class Supabase(
    private val url: String,
    private val anonKey: String,
    private val prefs: SharedPreferences,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()
    private val lock = Mutex()

    val realtimeUrl: String
        get() = url.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

    val userId: String? get() = prefs.getString(K_USER, null)

    /** A valid session: the stored one, refreshed if close to expiry, or a new anonymous user. */
    suspend fun session(forceRefresh: Boolean = false): Session = lock.withLock {
        val stored = load()
        when {
            stored == null -> auth("/auth/v1/signup", JSONObject())
            forceRefresh || stored.expiresAt - now() < 60_000 ->
                auth("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", stored.refreshToken))
            else -> stored
        }
    }

    /** POST /rest/v1/rpc/[fn]. Returns the raw JSON body ("" for void functions). */
    suspend fun rpc(fn: String, args: JSONObject = JSONObject()): String =
        authed { token -> post("/rest/v1/rpc/$fn", args.toString(), token) }

    /** GET /rest/v1/[pathAndQuery], filtered by RLS to what this user may see. */
    suspend fun select(pathAndQuery: String): String =
        authed { token -> call(base("/rest/v1/$pathAndQuery", token).get().build()) }

    private suspend fun authed(block: (String) -> String): String = withContext(Dispatchers.IO) {
        try {
            block(session().accessToken)
        } catch (e: Unauthorized) {
            block(session(forceRefresh = true).accessToken)
        }
    }

    private suspend fun auth(path: String, body: JSONObject): Session = withContext(Dispatchers.IO) {
        val o = JSONObject(post(path, body.toString(), token = null))
        Session(
            accessToken = o.getString("access_token"),
            refreshToken = o.getString("refresh_token"),
            expiresAt = now() + o.getLong("expires_in") * 1000,
            userId = o.getJSONObject("user").getString("id"),
        ).also(::save)
    }

    private fun post(path: String, body: String, token: String?): String =
        call(base(path, token).post(body.toRequestBody(json)).build())

    private fun base(path: String, token: String?) = Request.Builder()
        .url(url + path)
        .header("apikey", anonKey)
        .header("Authorization", "Bearer ${token ?: anonKey}")

    private fun call(req: Request): String {
        val res = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw SyncException("No connection", e)
        }
        res.use {
            val body = it.body?.string().orEmpty()
            if (it.code == 401) throw Unauthorized()
            if (!it.isSuccessful) throw SyncException(errorMessage(body) ?: "Server error ${it.code}")
            return body
        }
    }

    /** PostgREST puts `raise exception` text in "message"; GoTrue uses "msg" or "error_description". */
    private fun errorMessage(body: String): String? = runCatching {
        val o = JSONObject(body)
        listOf("message", "msg", "error_description").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotEmpty() } }
    }.getOrNull()

    private fun load(): Session? {
        val access = prefs.getString(K_ACCESS, null) ?: return null
        return Session(
            accessToken = access,
            refreshToken = prefs.getString(K_REFRESH, null) ?: return null,
            expiresAt = prefs.getLong(K_EXPIRES, 0),
            userId = prefs.getString(K_USER, null) ?: return null,
        )
    }

    private fun save(s: Session) {
        prefs.edit()
            .putString(K_ACCESS, s.accessToken)
            .putString(K_REFRESH, s.refreshToken)
            .putLong(K_EXPIRES, s.expiresAt)
            .putString(K_USER, s.userId)
            .apply()
    }

    private class Unauthorized : IOException()

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val K_ACCESS = "auth.access"
        const val K_REFRESH = "auth.refresh"
        const val K_EXPIRES = "auth.expiresAt"
        const val K_USER = "auth.user"
    }
}
