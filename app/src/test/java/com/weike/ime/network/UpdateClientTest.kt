package com.weike.ime.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpdateClientTest {
    private val client = UpdateClient()

    private val validManifest = """
        {
          "versionCode": 34,
          "versionName": "1.5.4",
          "apkUrl": "https://bucket.oss-cn-beijing.aliyuncs.com/updates/lianai-1.5.4.apk",
          "sha256": "b870605c963d7c8caa05ed9e2c5e095475014d98dbeccd2cb4782e3659463447",
          "sizeBytes": 58490200,
          "notes": "修复若干问题"
        }
    """.trimIndent()

    @Test
    fun manifestParsesIntoUpdateInfo() {
        val info = client.parseUpdateInfo(validManifest)
        assertEquals(34L, info.versionCode)
        assertEquals("1.5.4", info.versionName)
        assertEquals("修复若干问题", info.notes)
        assertEquals(58490200L, info.sizeBytes)
    }

    @Test
    fun manifestWithoutVersionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            client.parseUpdateInfo("""{"apkUrl":"https://example.com/a.apk","sha256":"${"a".repeat(64)}"}""")
        }
    }

    @Test
    fun manifestWithoutChecksumIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            client.parseUpdateInfo("""{"versionCode":34,"versionName":"1.5.4","apkUrl":"https://example.com/a.apk"}""")
        }
    }

    @Test
    fun manifestWithPlainHttpApkUrlIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            client.parseUpdateInfo(
                """{"versionCode":34,"versionName":"1.5.4","apkUrl":"http://example.com/a.apk","sha256":"${"a".repeat(64)}"}"""
            )
        }
    }

    @Test
    fun progressFractionStaysWithinBounds() {
        assertEquals(0f, UpdateClient.DownloadProgress(0, 100).fraction)
        assertTrue(UpdateClient.DownloadProgress(50, 100).fraction > .49f)
        assertEquals(1f, UpdateClient.DownloadProgress(150, 100).fraction)
    }
}
