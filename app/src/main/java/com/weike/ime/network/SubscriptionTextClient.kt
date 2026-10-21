package com.weike.ime.network

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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 订阅文本 AI 通道：登录态走后台中继，按套餐额度记账。
 * 服务端契约：POST /api/app/text/reply
 *   Headers: Authorization: Bearer <token>
 *   Body:    {"scene":"reply"|"polish","content":"...","relation":"通用"}
 *   200:     {"replies":["...","...","..."]}
 *   401 未登录 / 402·403 额度不足或套餐失效（message 说明）/ 404 通道未上线
 * 通道未上线（404）由调用方做会话级缓存，避免每次请求都白打一轮。
 */
class SubscriptionTextClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    suspend fun suggestions(
        token: String,
        scene: String,
        content: String,
        relation: String
    ): Result<List<String>> = runCatching {
        val body = JSONObject()
            .put("scene", scene)
            .put("content", content)
            .put("relation", relation)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("${VisionReplyClient.BASE}/text/reply")
            .header(VisionReplyClient.AUTH_HEADER, "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
            val json = JSONObject(response.body?.string().orEmpty())
            val replies = json.optJSONArray("replies") ?: JSONArray()
            buildList {
                for (index in 0 until replies.length()) add(replies.optString(index))
            }.filter(String::isNotBlank)
        }
    }

    private fun responseError(response: Response): String = runCatching {
        JSONObject(response.body?.string().orEmpty()).optString("message").ifBlank { defaultError(response) }
    }.getOrDefault(defaultError(response))

    private fun defaultError(response: Response) = when (response.code) {
        404 -> CHANNEL_NOT_LIVE
        401 -> "登录状态已过期"
        402, 403 -> "订阅套餐额度不足或已失效"
        else -> "订阅通道请求失败（HTTP ${response.code}）"
    }

    private suspend fun <T> Call.await(transform: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { runCatching { transform(it) } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    companion object {
        const val CHANNEL_NOT_LIVE = "__channel_not_live__"
    }
}
