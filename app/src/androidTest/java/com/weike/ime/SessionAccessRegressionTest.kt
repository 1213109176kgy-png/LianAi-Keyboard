package com.weike.ime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.weike.ime.data.*
import com.weike.ime.network.*
import com.weike.ime.speech.BackendAsrClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionAccessRegressionTest {
    private fun isolated(test: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "session-regression-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        try { test(context) }
        finally { names.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() } }
    }

    @Test fun realEncryptedOrphanAndCorruptAccountCannotAuthenticate() = isolated { context ->
        val secrets = SecureSecretStore(context)
        val sessions = UserSessionStore(context)
        secrets.write("app_auth_token", "synthetic-orphan-session")
        assertNull(sessions.user())
        assertNull(sessions.token())
        secrets.write("app_auth_user", "invalid-json")
        assertNull(sessions.user())
        assertNull(sessions.token())
        // Also exercise an undecipherable encrypted record, not just invalid JSON.
        context.getSharedPreferences("weike_secure_settings", Context.MODE_PRIVATE).edit()
            .putString("app_auth_user", "v1:invalid:invalid").commit()
        assertNull(sessions.user())
        assertNull(sessions.token())
        assertEquals("synthetic-orphan-session", secrets.read("app_auth_token"))
    }

    @Test fun realEncryptedLoginReloadLogoutDoesNotEraseCustomModelKeys() = isolated { context ->
        val secrets = SecureSecretStore(context)
        val keys = listOf("text_api_key", "vision_api_key", "asr_api_key")
        keys.forEach { secrets.write(it, "synthetic-custom-$it") }
        val app = UserSessionStore(context)
        val keyboard = UserSessionStore(context)
        val user = AppUser("synthetic-user", "13900000001", 1L, 2L)
        app.save("synthetic-active-session", user)
        assertEquals(user, keyboard.user())
        assertEquals("synthetic-active-session", keyboard.token())
        app.clear()
        assertNull(keyboard.user())
        assertNull(keyboard.token())
        keys.forEach { assertEquals("synthetic-custom-$it", secrets.read(it)) }
    }

    @Test fun anonymousOfficialTextVisionAndSpeechAreDeniedButCustomRoutesStillWork() = isolated { context ->
        runBlocking {
            SecureSecretStore(context).write("app_auth_token", "synthetic-orphan-session")
            val sessions = UserSessionStore(context)
            val configuration = AppConfigurationClient(context) { sessions.token() }
            var settings = CloudApiSettings()
            val router = ModelAccessRouter(configuration, { sessions.token() }, { settings })
            for (feature in ModelFeature.entries) {
                try { router.route(feature); fail("Anonymous official $feature route must be denied") }
                catch (expected: ModelSetupRequiredException) { assertTrue(expected.message.orEmpty().contains("模型配置")) }
            }
            val local = ModelEndpointConfig("https://example.invalid/v1", "synthetic-custom-key", "test-model")
            settings = settings.copy(reply = local, superSpeak = local, vision = local)
            for (feature in ModelFeature.entries) {
                val route = router.route(feature)
                assertFalse(route.official)
                assertEquals(local, route.endpoint)
            }
            val speech = BackendAsrClient(configuration) { sessions.token() }.transcribe(ByteArray(32))
            assertTrue(speech.isFailure)
            assertTrue(speech.exceptionOrNull()?.message.orEmpty().contains("登录"))
        }
    }
}
