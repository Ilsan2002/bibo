package com.bibo.ui

import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MentorHttpException(val code: Int, message: String) : IOException(message)

/** Cancellation owns the whole response body read, not just receipt of response headers. */
internal class MentorNetwork(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(25)).callTimeout(Duration.ofSeconds(30)).build(),
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
) {
    private val transportLock = Any()
    @Volatile private var blocked = false
    fun cancelAll() = synchronized(transportLock) { client.dispatcher.cancelAll() }
    fun pause() = synchronized(transportLock) { blocked = true; client.dispatcher.cancelAll() }
    fun resume() = synchronized(transportLock) { blocked = false }
    suspend fun chat(key: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int, reasoningEnabled: Boolean): JSONObject {
        val body = JSONObject().put("model", "z-ai/glm-5.2").put("messages", messages).put("max_tokens", maxTokens)
        if (!reasoningEnabled) body.put("reasoning", JSONObject().put("enabled", false))
        if (tools != null) body.put("tools", tools).put("tool_choice", "auto")
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val text = request(request)
        return try { JSONObject(text).also { if (it.has("error")) throw MentorHttpException(200, "The provider reported an error.") } } catch (_: Exception) { throw MentorHttpException(200, "The provider returned an unreadable response.") }
    }
    internal suspend fun request(request: Request): String = suspendCancellableCoroutine { continuation ->
        synchronized(transportLock) {
        if (blocked) { continuation.resumeWithException(IOException("Mentor connection is paused.")); return@suspendCancellableCoroutine }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw MentorHttpException(it.code, "Mentor provider request failed (HTTP ${it.code}).")
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        })
        }
    }
}
