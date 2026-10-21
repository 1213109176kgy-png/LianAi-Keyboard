package com.weike.ime.speech

import android.util.Base64
import com.weike.ime.network.AppConfigurationClient
import com.weike.ime.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Official ASR uses the model selected by the server, never an APK provider key. */
class BackendAsrClient(
    private val configuration: AppConfigurationClient,
    private val tokenProvider: () -> String?
) : AsrClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    override suspend fun transcribe(pcm: ByteArray): Result<String> = try {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: error("请先打开恋爱键盘登录，再使用默认语音识别")
        val config = configuration.refresh().getOrElse {
            throw it
        }
        check(config.speechRecognition.enabled) { "语音识别暂未开放，请稍后再试" }
        val audio = withContext(Dispatchers.Default) {
            Base64.encodeToString(MimoAsrClient.wav(pcm), Base64.NO_WRAP)
        }
        val body = JSONObject().put("audioBase64", audio).put("format", "wav").toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("${AppConfigurationClient.BASE}/speech/transcribe")
            .header("Authorization", "Bearer $token").post(body).build()
        Result.success(client.newCall(request).await { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            check(response.isSuccessful) {
                if (response.code == 401) "登录已过期，请重新打开恋爱键盘登录"
                else json.optString("message").ifBlank { "语音识别失败（HTTP ${response.code}）" }
            }
            val text = json.optString("text").trim().takeIf { it.isNotBlank() } ?: error("没有识别到语音，请重试")
            android.util.Log.i("BackendAsr", "Transcription succeeded; modelId=${json.optString("modelId")}; displayName=${json.optString("displayName")}")
            text
        })
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    override suspend fun testConnection(): Result<Unit> = transcribe(ByteArray(AudioRecorder.SAMPLE_RATE * 2)).map { Unit }
}
