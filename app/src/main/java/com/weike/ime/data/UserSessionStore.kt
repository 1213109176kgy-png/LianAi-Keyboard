package com.weike.ime.data

import android.content.Context
import com.weike.ime.network.AppUser
import org.json.JSONObject

/**
 * 本机登录态：token 与用户信息走 Keystore 加密存储。
 * 未登录时 [user] 为 null；正常使用输入法不依赖登录态。
 */
class UserSessionStore internal constructor(
    private val readSecret: (String) -> String?,
    private val writeSecret: (String, String) -> Unit
) {
    constructor(context: Context) : this(SecureSecretStore(context))
    private constructor(store: SecureSecretStore) : this(store::read, store::write)

    fun save(token: String, user: AppUser) = synchronized(SESSION_LOCK) {
        require(token.isNotBlank() && user.id.isNotBlank() && user.phone.isNotBlank()) { "登录信息不完整，请重新登录" }
        val encodedUser = JSONObject()
                .put("id", user.id)
                .put("phone", user.phone)
                .put("createdAt", user.createdAtMs)
                .put("lastLoginAt", user.lastLoginAtMs)
                .toString()
        // Revoke the old credential first and publish the new token last. A failed
        // write cannot authenticate a new token with missing or old user data.
        writeSecret(KEY_TOKEN, "")
        writeSecret(KEY_USER, encodedUser)
        writeSecret(KEY_TOKEN, token)
    }

    fun token(): String? = synchronized(SESSION_LOCK) { readSession()?.first }

    fun user(): AppUser? = synchronized(SESSION_LOCK) { readSession()?.second }

    private fun readSession(): Pair<String, AppUser>? {
        val token = readSecret(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val raw = readSecret(KEY_USER) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val id = (json.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
            val phone = (json.opt("phone") as? String)?.takeIf { it.isNotBlank() } ?: return null
            token to AppUser(
                id = id,
                phone = phone,
                createdAtMs = json.optLong("createdAt"),
                lastLoginAtMs = json.optLong("lastLoginAt")
            )
        }.getOrNull()
    }

    fun clear() = synchronized(SESSION_LOCK) {
        writeSecret(KEY_TOKEN, "")
        writeSecret(KEY_USER, "")
    }

    companion object {
        private val SESSION_LOCK = Any()
        private const val KEY_TOKEN = "app_auth_token"
        private const val KEY_USER = "app_auth_user"
    }
}
