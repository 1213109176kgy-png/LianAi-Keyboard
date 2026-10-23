package com.weike.ime.network

import com.weike.ime.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Backend-driven update channel. A small JSON manifest describes the newest
 * release (versionCode/versionName/notes). The app never downloads or installs
 * the APK itself any more — 检查更新 jumps to the download page in the system
 * browser instead, which avoids the unknown-source prompt and the ROM virus
 * warnings that in-app APK installs triggered. apkUrl/sha256/sizeBytes still
 * arrive in the manifest and are deliberately ignored.
 */
class UpdateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val notes: String
    )

    suspend fun checkLatest(): Result<UpdateInfo> = runCatching {
        require(API_ENDPOINT.isNotBlank() && !API_ENDPOINT.contains(PLACEHOLDER_MARK)) { UPGRADE_NOT_CONFIGURED }
        val request = Request.Builder().url(secureUrl(API_ENDPOINT)).get().build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { "升级服务返回 HTTP ${response.code}" }
            parseUpdateInfo(response.body?.string().orEmpty())
        }
    }

    internal fun parseUpdateInfo(body: String): UpdateInfo {
        val json = JSONObject(body)
        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName").trim()
        require(versionCode > 0 && versionName.isNotBlank()) { "升级信息缺少版本号" }
        return UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            notes = json.optString("notes").trim()
        )
    }

    /** HTTPS only; plain loopback is tolerated in debug builds for local testing. */
    private fun secureUrl(candidate: String): String {
        val url = candidate.toHttpUrlOrNull() ?: error("升级服务地址无效")
        val loopback = url.host == "127.0.0.1" || url.host == "localhost"
        require(url.isHttps || (BuildConfig.DEBUG && loopback)) { "升级服务地址必须使用 HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "升级服务地址不能包含凭据" }
        return url.toString()
    }

    private suspend fun <T> Call.await(transform: (okhttp3.Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    val result = response.use { runCatching { transform(it) } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    companion object {
        /**
         * TODO: 后台上线后替换为正式接口；临时方案：把这个 JSON 直接放到 OSS
         * （如 https://<bucket>.oss-cn-<region>.aliyuncs.com/updates/latest.json），
         * 后续后台只需返回相同结构即可，客户端无需发版。
         */
        const val API_ENDPOINT = "https://lianai.bahuang.tech/api/app/latest"

        /**
         * 下载落地页：新版本统一由系统浏览器打开这里完成下载安装。
         * 将来如需后台可配，可在 latest JSON 里加 downloadPageUrl 字段，
         * 客户端优先取该字段、为空时回落到此常量。
         */
        const val DOWNLOAD_PAGE_URL = "https://lianai.bahuang.tech/h5/"
        private const val PLACEHOLDER_MARK = "CHANGE-ME"
        const val UPGRADE_NOT_CONFIGURED = "升级服务地址尚未配置"
    }
}
