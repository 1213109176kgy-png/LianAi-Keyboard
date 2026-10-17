package com.weike.ime.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AppUser(
    val id: String,
    val phone: String,
    val createdAtMs: Long,
    val lastLoginAtMs: Long
) {
    val maskedPhone: String
        get() = if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone
}

/**
 * 账号体系仅服务于订阅购买：平时使用输入法不需要登录，购买套餐时才引导。
 * Token 有效期 180 天，由后端签发。
 */
class UserAuthClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    data class AuthSession(val token: String, val user: AppUser)

    suspend fun login(phone: String, password: String): Result<AuthSession> =
        authRequest("login", phone, password)

    suspend fun register(phone: String, password: String): Result<AuthSession> =
        authRequest("register", phone, password)

    suspend fun profile(token: String): Result<AppUser> = runCatching {
        val request = Request.Builder()
            .url("$BASE/profile")
            .header(AUTH_HEADER, "Bearer $token")
            .get()
            .build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
            parseUser(JSONObject(response.body?.string().orEmpty()).getJSONObject("user"))
        }
    }

    suspend fun logout(token: String): Result<Unit> = runCatching {
        val request = Request.Builder()
            .url("$BASE/logout")
            .header(AUTH_HEADER, "Bearer $token")
            .post(EMPTY_BODY)
            .build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
        }
        Unit
    }

    fun parseUser(json: JSONObject): AppUser = AppUser(
        id = json.optString("id"),
        phone = json.optString("phone"),
        createdAtMs = json.optLong("createdAt"),
        lastLoginAtMs = json.optLong("lastLoginAt")
    )

    private suspend fun authRequest(action: String, phone: String, password: String): Result<AuthSession> = runCatching {
        val body = JSONObject()
            .put("phone", phone)
            .put("password", password)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$BASE/$action")
            .post(body)
            .build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
            val json = JSONObject(response.body?.string().orEmpty())
            AuthSession(token = json.getString("token"), user = parseUser(json.getJSONObject("user")))
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
        const val BASE = "https://lianai.bahuang.tech/api/app/auth"
        const val AUTH_HEADER = "Authorization"
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
