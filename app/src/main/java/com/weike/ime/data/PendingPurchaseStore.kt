package com.weike.ime.data

import android.content.Context
import com.weike.ime.network.SubscriptionPlan
import org.json.JSONObject

data class PendingPurchase(
    val userId: String,
    val requestId: String,
    val plan: SubscriptionPlan,
    val orderId: String = "",
    val payUrl: String = ""
)

/** Persist before checkout so a process restart/retry keeps the same order. */
class PendingPurchaseStore(context: Context) {
    private val store = SecureSecretStore(context)

    fun save(purchase: PendingPurchase) {
        store.write(KEY, JSONObject().apply {
            put("userId", purchase.userId)
            put("requestId", purchase.requestId)
            put("orderId", purchase.orderId)
            put("payUrl", purchase.payUrl)
            put("plan", JSONObject().apply {
                put("id", purchase.plan.id); put("name", purchase.plan.name)
                put("description", purchase.plan.description); put("periodDays", purchase.plan.periodDays)
                put("priceFen", purchase.plan.priceFen); put("dailyQuota", purchase.plan.dailyQuota)
                put("sortOrder", purchase.plan.sortOrder)
            })
        }.toString())
    }

    fun read(userId: String?): PendingPurchase? = runCatching {
        if (userId.isNullOrBlank()) return null
        val json = JSONObject(store.read(KEY) ?: return null)
        if (json.optString("userId") != userId) return null
        val plan = json.getJSONObject("plan")
        PendingPurchase(userId, json.getString("requestId"), SubscriptionPlan(
            plan.getString("id"), plan.getString("name"), plan.optString("description"),
            plan.getInt("periodDays"), plan.getLong("priceFen"), plan.optInt("dailyQuota"), plan.optInt("sortOrder")
        ), json.optString("orderId"), json.optString("payUrl"))
    }.getOrNull()

    fun clear() = store.write(KEY, "")

    companion object { private const val KEY = "pending_alipay_purchase" }
}
