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

data class VisionReplyResult(
    val scenario: String,
    val analysis: String,
    val replies: List<String>
)

/** 截图识别回复：走后台视觉模型（登录态），后台自动按模型价格记账。 */
class VisionReplyClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    /** 功能开关（公开接口）。 */
    suspend fun enabled(): Result<Boolean> = runCatching {
        val request = Request.Builder().url("$BASE/business").get().build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
            JSONObject(response.body?.string().orEmpty()).optBoolean("visionReplyEnabled")
        }
    }

    suspend fun reply(token: String, imageBase64: String, mime: String): Result<VisionReplyResult> = runCatching {
        val body = JSONObject()
            .put("imageBase64", imageBase64)
            .put("mime", mime)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$BASE/vision/reply")
            .header(AUTH_HEADER, "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
            val json = JSONObject(response.body?.string().orEmpty())
            val replies = JSONArray().let { array ->
                buildList {
                    val source = json.optJSONArray("replies") ?: JSONArray()
                    for (index in 0 until source.length()) add(source.optString(index))
                }
            }
            VisionReplyResult(
                scenario = json.optString("scenario"),
                analysis = json.optString("analysis"),
                replies = replies
            )
        }
    }

    private fun responseError(response: Response): String = runCatching {
        JSONObject(response.body?.string().orEmpty()).optString("message").ifBlank { "请求失败（HTTP ${response.code}）" }
    }.getOrDefault("请求失败（HTTP ${response.code}）")

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
        const val BASE = "https://lianai.bahuang.tech/api/app"
        const val AUTH_HEADER = "Authorization"
    }
}
