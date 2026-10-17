package com.weike.ime.data

import android.content.Context
import com.weike.ime.network.AppUser
import org.json.JSONObject

/**
 * 本机登录态：token 与用户信息走 Keystore 加密存储。
 * 未登录时 [user] 为 null；正常使用输入法不依赖登录态。
 */
class UserSessionStore(context: Context) {
    private val store = SecureSecretStore(context)

    fun save(token: String, user: AppUser) {
        store.write(KEY_TOKEN, token)
        store.write(
            KEY_USER,
            JSONObject()
                .put("id", user.id)
                .put("phone", user.phone)
                .put("createdAt", user.createdAtMs)
                .put("lastLoginAt", user.lastLoginAtMs)
                .toString()
        )
    }

    fun token(): String? = store.read(KEY_TOKEN)?.takeIf { it.isNotBlank() }

    fun user(): AppUser? {
        val token = token() ?: return null
        val raw = store.read(KEY_USER) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            AppUser(
                id = json.optString("id").ifBlank { token },
                phone = json.optString("phone"),
                createdAtMs = json.optLong("createdAt"),
                lastLoginAtMs = json.optLong("lastLoginAt")
            )
        }.getOrNull()?.takeIf { it.phone.isNotBlank() }
    }

    fun clear() {
        store.write(KEY_TOKEN, "")
        store.write(KEY_USER, "")
    }

    companion object {
        private const val KEY_TOKEN = "app_auth_token"
        private const val KEY_USER = "app_auth_user"
    }
}
