package com.weike.ime.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
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

data class SubscriptionPlan(
    val id: String,
    val name: String,
    val description: String,
    val periodDays: Int,
    val priceFen: Long,
    val dailyQuota: Int,
    val sortOrder: Int
) {
    val priceYuan: String
        get() = if (priceFen == 0L) "免费" else String.format("%.2f", priceFen / 100.0).trimEnd('0').trimEnd('.')
}

data class SubscriptionStatus(
    val active: Boolean,
    val planName: String,
    val dailyQuota: Int,
    val expiresAt: Long,
    val daysLeft: Int
)

data class OrderResult(
    val orderId: String,
    val status: String,
    val payUrl: String?,
    val free: Boolean
)

data class PaymentOrder(val status: String, val payUrl: String?)

class SubscriptionApiException(val statusCode: Int, message: String) : IOException(message)

/** 订阅套餐：查询（公开）、下单/查单/订阅状态（需登录 token）。 */
class SubscriptionClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    suspend fun plans(): Result<List<SubscriptionPlan>> = networkResult {
        val request = Request.Builder().url("$BASE/plans").get().build()
        client.newCall(request).await { response ->
            ensureSuccess(response)
            val array = JSONObject(response.body?.string().orEmpty()).getJSONArray("plans")
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    add(
                        SubscriptionPlan(
                            id = json.optString("id"),
                            name = json.optString("name"),
                            description = json.optString("description"),
                            periodDays = json.optInt("periodDays"),
                            priceFen = json.optLong("priceFen"),
                            dailyQuota = json.optInt("dailyQuota"),
                            sortOrder = json.optInt("sortOrder")
                        )
                    )
                }
            }
        }
    }

    suspend fun createOrder(token: String, planId: String, requestId: String): Result<OrderResult> = networkResult {
        val body = JSONObject()
            .put("planId", planId)
            .put("channel", "alipay")
            .put("requestId", requestId)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$BASE/orders")
            .header(AUTH_HEADER, "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).await { response ->
            ensureSuccess(response)
            val json = JSONObject(response.body?.string().orEmpty())
            OrderResult(
                orderId = json.optString("orderId"),
                status = json.optString("status"),
                payUrl = json.optString("payUrl").ifBlank { null },
                free = json.optBoolean("free")
            )
        }
    }

    suspend fun order(token: String, orderId: String): Result<PaymentOrder> = networkResult {
        val request = Request.Builder()
            .url("$BASE/orders/$orderId")
            .header(AUTH_HEADER, "Bearer $token")
            .get().build()
        client.newCall(request).await { response ->
            ensureSuccess(response)
            val json = JSONObject(response.body?.string().orEmpty())
            PaymentOrder(json.getString("status"), json.optString("payUrl").ifBlank { null })
        }
    }

    suspend fun alipayEnabled(): Result<Boolean> = networkResult {
        val request = Request.Builder().url("$BASE/payment/channels").get().build()
        client.newCall(request).await { response ->
            ensureSuccess(response)
            val channels = JSONObject(response.body?.string().orEmpty()).getJSONArray("channels")
            (0 until channels.length()).any { channels.optString(it) == "alipay" }
        }
    }

    suspend fun subscription(token: String): Result<SubscriptionStatus> = networkResult {
        val request = Request.Builder()
            .url("$BASE/me/subscription")
            .header(AUTH_HEADER, "Bearer $token")
            .get()
            .build()
        client.newCall(request).await { response ->
            ensureSuccess(response)
            val json = JSONObject(response.body?.string().orEmpty())
            SubscriptionStatus(
                active = json.optBoolean("active"),
                planName = json.optString("planName"),
                dailyQuota = json.optInt("dailyQuota"),
                expiresAt = json.optLong("expiresAt"),
                daysLeft = json.optInt("daysLeft")
            )
        }
    }

    private fun ensureSuccess(response: Response) {
        if (!response.isSuccessful) throw SubscriptionApiException(response.code, responseError(response))
    }

    private suspend inline fun <T> networkResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
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
