package com.weike.ime.network

import android.content.Context
import com.weike.ime.data.UserSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Non-secret settings only. Provider credentials never cross this boundary. */
data class AppFeatureConfig(
    val enabled: Boolean = false,
    val modelId: String = "",
    val displayName: String = "",
    val provider: String = "",
    val model: String = ""
) {
    fun toJson() = JSONObject().put("enabled", enabled).put("modelId", modelId)
        .put("displayName", displayName).put("provider", provider).put("model", model)
}

data class AppRuntimeConfig(
    val visionReply: AppFeatureConfig = AppFeatureConfig(),
    val speechRecognition: AppFeatureConfig = AppFeatureConfig(),
    val replySuggestions: AppFeatureConfig = AppFeatureConfig(),
    val superSpeak: AppFeatureConfig = AppFeatureConfig(),
    val user: RuntimeUser? = null
) {
    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("features", JSONObject()
        .put("visionReply", visionReply.toJson()).put("speechRecognition", speechRecognition.toJson())
        .put("replySuggestions", replySuggestions.toJson()).put("superSpeak", superSpeak.toJson()))
        .put("user", user?.toJson() ?: JSONObject.NULL)

    companion object {
        fun parse(json: JSONObject): AppRuntimeConfig {
            require(json.optInt("schemaVersion") == 1) { "App 配置版本不支持，请升级 App" }
            val features = json.getJSONObject("features")
            fun feature(key: String): AppFeatureConfig {
                val value = features.optJSONObject(key) ?: return AppFeatureConfig()
                val modelId = value.optString("modelId")
                return AppFeatureConfig(value.optBoolean("enabled") && modelId.isNotBlank(), modelId,
                    value.optString("displayName"), value.optString("provider"), value.optString("model"))
            }
            val userJson = json.optJSONObject("user")
            val vip = userJson?.optJSONObject("vip")
            val user = userJson?.optString("id")?.takeIf { it.isNotBlank() }?.let {
                RuntimeUser(it, userJson.optString("phone"), vip?.optBoolean("active") == true,
                    vip?.optLong("expiresAt") ?: 0L, vip?.optString("planName").orEmpty(), vip?.optInt("dailyQuota") ?: 0)
            }
            return AppRuntimeConfig(feature("visionReply"), feature("speechRecognition"), feature("replySuggestions"), feature("superSpeak"), user)
        }
    }
}

data class RuntimeUser(val id: String, val phone: String, val vipActive: Boolean,
    val vipExpiresAt: Long, val vipPlanName: String, val dailyQuota: Int) {
    fun isVip(now: Long = System.currentTimeMillis()) = vipActive && vipExpiresAt > now
    fun toJson(): JSONObject = JSONObject().put("id", id).put("phone", phone).put("vip", JSONObject()
        .put("active", vipActive).put("expiresAt", vipExpiresAt).put("planName", vipPlanName).put("dailyQuota", dailyQuota))
}

class AppConfigurationClient(context: Context, private val tokenProvider: () -> String? = { UserSessionStore(context).token() }) {
    private val cache = context.applicationContext.getSharedPreferences("app_runtime_config", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    @Volatile var current: AppRuntimeConfig? = runCatching {
        AppRuntimeConfig.parse(JSONObject(cache.getString("config", null) ?: return@runCatching null))
    }.getOrNull()
        private set
    private var lastFetchAt = 0L
    private var currentToken: String? = null

    suspend fun refresh(force: Boolean = false): Result<AppRuntimeConfig> = mutex.withLock {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
        if (currentToken != token) {
            current = current?.copy(user = null)
            lastFetchAt = 0L
        }
        if (!force && currentToken == token && lastFetchAt > 0L && current != null && android.os.SystemClock.elapsedRealtime() - lastFetchAt < 15_000L) {
            return@withLock Result.success(current!!)
        }
        try {
            val request = Request.Builder().url("$BASE/config").header("Cache-Control", "no-cache")
                .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            val config = client.newCall(request).await { response ->
                check(response.isSuccessful) { "获取 App 配置失败（HTTP ${response.code}）" }
                AppRuntimeConfig.parse(JSONObject(response.body?.string().orEmpty()))
            }
            check(token == tokenProvider()?.takeIf { it.isNotBlank() }) { "登录账号已切换，请重试" }
            current = config
            currentToken = token
            lastFetchAt = android.os.SystemClock.elapsedRealtime()
            // 身份和会员状态仅驻留内存，磁盘缓存不能用来授予官方通道权限。
            cache.edit().putString("config", config.copy(user = null).toJson().toString()).apply()
            Result.success(config)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current = current?.copy(user = null)
            lastFetchAt = 0L
            Result.failure(error)
        }
    }

    companion object { const val BASE = "https://lianai.bahuang.tech/api/app" }
}

internal suspend fun <T> Call.await(transform: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
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
