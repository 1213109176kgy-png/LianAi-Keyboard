package com.weike.ime.network

import com.weike.ime.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Backend-driven update channel. A small JSON manifest describes the newest
 * APK (today the manifest can simply be a static file on OSS; the management
 * backend will serve the same contract later). Downloads report progress and
 * are verified by SHA-256 before the install intent is fired; Android itself
 * additionally rejects any update signed with a different key.
 */
class UpdateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val sizeBytes: Long,
        val notes: String
    )

    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
        val fraction: Float = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    suspend fun checkLatest(): Result<UpdateInfo> = runCatching {
        require(API_ENDPOINT.isNotBlank() && !API_ENDPOINT.contains(PLACEHOLDER_MARK)) { UPGRADE_NOT_CONFIGURED }
        val request = Request.Builder().url(secureUrl(API_ENDPOINT)).get().build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { "升级服务返回 HTTP ${response.code}" }
            parseUpdateInfo(response.body?.string().orEmpty())
        }
    }

    suspend fun downloadApk(
        info: UpdateInfo,
        destination: File,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destination.parentFile?.mkdirs()
            destination.delete()
            val partial = File(destination.parentFile, ".${destination.name}.part")
            partial.delete()
            val request = Request.Builder().url(secureUrl(info.apkUrl)).get().build()
            client.newCall(request).await { response ->
                check(response.isSuccessful) { "升级包下载失败：HTTP ${response.code}" }
                val body = response.body ?: error("升级包为空")
                val total = body.contentLength()
                FileOutputStream(partial).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            onProgress(DownloadProgress(downloaded, total))
                        }
                    }
                }
            }
            if (info.sizeBytes > 0) require(partial.length() == info.sizeBytes) { "升级包大小校验失败" }
            require(sha256(partial).equals(info.sha256, ignoreCase = true)) { "升级包 SHA-256 校验失败" }
            check(partial.renameTo(destination)) { "升级包保存失败" }
            destination
        }
    }

    internal fun parseUpdateInfo(body: String): UpdateInfo {
        val json = JSONObject(body)
        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName").trim()
        val apkUrl = json.optString("apkUrl").trim()
        val sha256 = json.optString("sha256").trim()
        require(versionCode > 0 && versionName.isNotBlank()) { "升级信息缺少版本号" }
        require(apkUrl.startsWith("http")) { "升级信息缺少安装包地址" }
        secureUrl(apkUrl)
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "升级信息缺少有效的 SHA-256 校验值" }
        return UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            sizeBytes = json.optLong("sizeBytes", 0L),
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        private const val PLACEHOLDER_MARK = "CHANGE-ME"
        const val UPGRADE_NOT_CONFIGURED = "升级服务地址尚未配置"
    }
}
