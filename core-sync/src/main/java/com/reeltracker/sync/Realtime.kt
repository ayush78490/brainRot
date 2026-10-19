package com.reeltracker.sync

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Supabase Realtime (Phoenix protocol v1) reduced to one thing: tell me when a daily_stats row I
 * may see has changed. RLS on the server decides which rows that is (mine and my friends').
 *
 * Emits on every join too, so a reconnect makes the caller refetch whatever it missed.
 */
internal fun dailyStatsChanges(api: Supabase): Flow<Unit> = callbackFlow {
    val topic = "realtime:daily_stats"
    val ref = AtomicInteger(0)
    var token = api.session().accessToken

    fun msg(event: String, payload: JSONObject, t: String = topic) = JSONObject()
        .put("topic", t).put("event", event).put("payload", payload)
        .put("ref", ref.incrementAndGet().toString())
        .toString()

    val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            val config = JSONObject()
                .put("broadcast", JSONObject().put("ack", false).put("self", false))
                .put("presence", JSONObject().put("key", ""))
                .put("postgres_changes", JSONArray().put(
                    JSONObject().put("event", "*").put("schema", "public").put("table", "daily_stats")
                ))
                .put("private", false)
            ws.send(msg("phx_join", JSONObject().put("config", config).put("access_token", token)))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (o.optString("topic") != topic) return
            val payload = o.optJSONObject("payload")
            when (o.optString("event")) {
                "postgres_changes" -> trySend(Unit)
                "phx_reply" -> if (payload?.optString("status") == "ok") trySend(Unit)
                    else Log.w(TAG, "join refused: $text")
                "phx_error", "phx_close" -> close(SyncException("realtime ${o.optString("event")}"))
                "system" -> if (payload?.optString("status") == "error") Log.w(TAG, "system: $text")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            close(t)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            close(SyncException("realtime closed $code"))
        }
    }

    val ws = api.http.newWebSocket(Request.Builder().url(api.realtimeUrl).build(), listener)

    // Phoenix heartbeat, and hand over a refreshed token before the old one expires (1 h).
    launch {
        while (true) {
            delay(25_000)
            ws.send(msg("heartbeat", JSONObject(), t = "phoenix"))
            val fresh = api.session().accessToken
            if (fresh != token) {
                token = fresh
                ws.send(msg("access_token", JSONObject().put("access_token", fresh)))
            }
        }
    }

    awaitClose { ws.close(1000, null) }
}.retryWhen { cause, attempt ->
    Log.w(TAG, "realtime dropped (${cause.message}), retry #$attempt")
    delay((1_000L shl attempt.toInt().coerceAtMost(5)).coerceAtMost(30_000))
    true
}

private const val TAG = "BrainrotRealtime"
