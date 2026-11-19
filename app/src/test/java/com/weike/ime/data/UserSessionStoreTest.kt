package com.weike.ime.data

import com.weike.ime.network.*
import org.junit.Test
import kotlin.test.*

class UserSessionStoreTest {
    private val tokenKey = "app_auth_token"
    private val userKey = "app_auth_user"
    private val user = AppUser("test-user", "13900000001", 1L, 2L)
    private class Vault {
        val values = mutableMapOf<String, String>()
        var failOn: String? = null
        fun read(key: String) = values[key]
        fun write(key: String, value: String) {
            if (key == failOn) error("Synthetic storage failure")
            if (value.isBlank()) values.remove(key) else values[key] = value
        }
        fun session() = UserSessionStore(::read, ::write)
    }
    @Test fun orphanTokenAndMalformedUserNeverAuthenticate() {
        for (raw in listOf(null, "", "broken-json", "{}", "[]",
            """{"id":"test-user"}""", """{"id":"","phone":"13900000001"}""",
            """{"id":null,"phone":"13900000001"}""", """{"id":"test-user","phone":" "}""")) {
            val vault = Vault()
            vault.values[tokenKey] = "orphan-session"
            if (raw != null) vault.values[userKey] = raw
            assertNull(vault.session().user(), "Invalid account must look logged out")
            assertNull(vault.session().token(), "Invalid account must not yield an official credential")
        }
    }
    @Test fun validLoginReloadAndLogoutStayConsistentAcrossInstances() {
        val vault = Vault()
        val app = vault.session()
        val keyboard = vault.session()
        app.save("synthetic-session", user)
        assertEquals(user, keyboard.user())
        assertEquals("synthetic-session", keyboard.token())
        app.clear()
        assertNull(keyboard.user())
        assertNull(keyboard.token())
    }
    @Test fun missingOrBlankTokenAlsoMakesAccountLoggedOut() {
        for (token in listOf(null, "", " ")) {
            val vault = Vault()
            vault.session().save("synthetic-session", user)
            if (token == null) vault.values.remove(tokenKey) else vault.values[tokenKey] = token
            assertNull(vault.session().user())
            assertNull(vault.session().token())
        }
    }
    @Test fun failedAccountWriteRevokesOldTokenAndCannotPublishNewLogin() {
        val vault = Vault()
        val session = vault.session()
        session.save("old-session", user)
        vault.failOn = userKey
        assertFailsWith<IllegalStateException> {
            session.save("new-session", user.copy(id = "new-user", phone = "13900000002"))
        }
        assertNull(session.token())
        assertNull(session.user())
    }
    @Test fun incompleteServerSessionIsRejectedBeforeChangingValidLogin() {
        val vault = Vault()
        val session = vault.session()
        session.save("current-session", user)
        for ((token, invalidUser) in listOf(" " to user, "new-session" to user.copy(id = ""),
            "new-session" to user.copy(phone = ""))) {
            assertFailsWith<IllegalArgumentException> { session.save(token, invalidUser) }
            assertEquals("current-session", session.token())
            assertEquals(user, session.user())
        }
    }
    @Test fun anonymousSessionCannotSelectAnyOfficialBusinessEvenWithCachedVip() {
        val vault = Vault()
        vault.values[tokenKey] = "orphan-session"
        val binding = AppFeatureConfig(true, "test-binding", model = "test-model")
        val config = AppRuntimeConfig(visionReply = binding, replySuggestions = binding, superSpeak = binding,
            user = RuntimeUser("test-user", "13900000001", true, 2000L, "VIP", 100))
        val local = ModelEndpointConfig("https://example.invalid/v1", "synthetic-custom-key", "test-model")
        for (feature in ModelFeature.entries) {
            assertFailsWith<ModelSetupRequiredException> {
                ModelRoutingPolicy.select(config, feature, ModelEndpointConfig(), vault.session().token(), 1000L)
            }
            val route = ModelRoutingPolicy.select(config, feature, local, vault.session().token(), 1000L)
            assertFalse(route.official)
            assertEquals(local, route.endpoint)
        }
    }
}
