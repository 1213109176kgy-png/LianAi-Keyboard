package com.weike.ime

import android.content.Intent
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.weike.ime.knowledge.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(AndroidJUnit4::class)
class KnowledgeIntegrationTest {
    @Test fun chosenDefaultPersistsSelectsKeyboardAndExplicitNoneStillWorks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = java.util.UUID.randomUUID().toString()
        val directory = java.io.File(context.cacheDir, "knowledge-default-$token").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        try {
            val store = KnowledgeStore(isolated)
            assertEquals(SystemKnowledge.GOUTOU_ID, store.defaultDocumentId())
            store.setEnabled(SystemKnowledge.TONG_ID, false)
            store.setDefaultDocument(SystemKnowledge.TONG_ID)
            assertTrue(store.read().documents.single { it.id == SystemKnowledge.TONG_ID }.enabled)
            assertEquals(SystemKnowledge.TONG_ID, KnowledgeStore(isolated).defaultDocumentId())
            assertEquals(SystemKnowledge.TONG_ID, KnowledgeStore(isolated).selectedDocumentId())
            store.selectDocument(null)
            assertNull(KnowledgeStore(isolated).selectedDocumentId())
            assertEquals(SystemKnowledge.TONG_ID, KnowledgeStore(isolated).defaultDocumentId())
            store.setDefaultDocument(SystemKnowledge.GOUTOU_ID)
            assertEquals(SystemKnowledge.GOUTOU_ID, KnowledgeStore(isolated).selectedDocumentId())
            store.setEnabled(SystemKnowledge.GOUTOU_ID, false)
            assertNull(KnowledgeStore(isolated).selectedDocumentId())
            store.setDefaultDocument(SystemKnowledge.GOUTOU_ID)
            assertEquals(SystemKnowledge.GOUTOU_ID, KnowledgeStore(isolated).selectedDocumentId())
        } finally {
            isolated.getSharedPreferences("love_knowledge_selection_v1", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            directory.deleteRecursively()
        }
    }
    @Test fun markdownCacheReopensWholeDocumentSynchronouslyAndInvalidatesEditedContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Weike)
        val source = "# 缓存测试\n\n" + "实际沟通知识与话术。\n\n".repeat(1500) + "**完整正文末尾标记**"
        val parsed = KnowledgeMarkdownCache.prepare(context, source).get(15, java.util.concurrent.TimeUnit.SECONDS)
        assertSame(parsed, KnowledgeMarkdownCache.cached(context, source))
        assertSame(parsed, KnowledgeMarkdownCache.prepare(context, source).get())
        var secondary = false
        instrumentation.runOnMainSync {
            val views = KnowledgeViews(context, onDetailChanged = { secondary = it }) {}
            val root = views.build()
            fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
            repeat(3) {
                views.viewDocument(LoveDocument("cache-test", "缓存测试", source))
                val rendered = all(root).filterIsInstance<TextView>().single { it.contentDescription == "Markdown 阅读正文" }
                // Assert in the same UI callback: no worker/frame wait and not merely an opening excerpt.
                assertTrue(rendered.text.toString().endsWith("完整正文末尾标记"))
                assertFalse(rendered.text.toString().contains("正在排版"))
                assertTrue(secondary)
                views.requestLeave(); assertFalse(secondary)
            }
        }
        val edited = source + "\n\n新增正文"
        assertNull(KnowledgeMarkdownCache.cached(context, edited))
        val newParsed = KnowledgeMarkdownCache.prepare(context, edited).get(15, java.util.concurrent.TimeUnit.SECONDS)
        assertNotSame(parsed, newParsed)
        assertTrue(newParsed.toString().endsWith("新增正文"))
    }
    @Test fun screenshotFinalReplyIncludesSelectedKnowledgeAndNoneSkipsSecondCall() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer); requests += buffer.readUtf8()
            val content = org.json.JSONArray(listOf("结合资料回复一", "结合资料回复二", "结合资料回复三")).toString()
            val response = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject().put("message", org.json.JSONObject().put("content", content))))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val polisher = com.weike.ime.network.MimoTextPolisher(
            endpointProvider = { com.weike.ime.data.ModelEndpointConfig("https://example.invalid/v1", "test-only-key", "test-model") }, client = client)
        // Empty sourceMessage also covers the current official backend response contract.
        val image = com.weike.ime.network.VisionReplyResult("恋爱", "对方期待邀约", listOf("周末一起散步吧", "要不要喝咖啡", "一起看展吗"))
        val snapshot = KnowledgeSnapshot("dog", "狗头军师知识库", "v1", listOf(KnowledgePassage("dog", "邀约原则", "知识库唯一标记：低压力邀约并尊重拒绝")))
        val result = image.withKnowledge(snapshot, polisher).getOrThrow()
        assertEquals("结合资料回复一", result.replies.first())
        assertTrue(requests.single().contains("知识库唯一标记"))
        assertTrue(requests.single().contains("这是我方回复，不是对方原话"))
        assertTrue(image.knowledgeQuery().contains("一起看展吗"))
        assertEquals(image, image.withKnowledge(KnowledgeSnapshot(null, "", "", emptyList()), polisher).getOrThrow())
        assertEquals(1, requests.size)
        assertTrue(image.withKnowledge(snapshot.copy(failed = true), polisher).isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun markdownRendersHeadingsListsQuotesCodeTablesAndPreservesEditableSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Weike)
        val source = "# 一级标题\n\n**加粗内容**\n\n- 列表项目\n\n> 引用内容\n\n`代码内容`\n\n| 列一 | 列二 |\n| --- | --- |\n| 值一 | 值二 |"
        lateinit var views: KnowledgeViews
        lateinit var root: View
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        instrumentation.runOnMainSync {
            val rendered = KnowledgeMarkdown.create(context).toMarkdown(source)
            assertFalse(rendered.toString().contains("# 一级标题"))
            assertFalse(rendered.toString().contains("**加粗内容**"))
            assertTrue(rendered.toString().contains("加粗内容"))
            val names = rendered.getSpans(0, rendered.length, Any::class.java).map { it.javaClass.simpleName }
            assertTrue(names.any { it.contains("Heading") })
            assertTrue(names.any { it.contains("StrongEmphasis") })
            assertTrue(names.any { it.contains("BulletList") })
            assertTrue(names.any { it.contains("BlockQuote") })
            assertTrue(names.any { it.contains("Code") })
            assertTrue(names.any { it.contains("Table") })
            views = KnowledgeViews(context) {}; root = views.build()
            views.editDocument(LoveDocument("preview-test", "排版测试", source), readInitially = true)
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var finished = false
        while (!finished && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val preview = all(root).filterIsInstance<TextView>().single { it.contentDescription == "Markdown 阅读正文" }
                finished = preview.text.toString().contains("加粗内容")
            }
            if (!finished) Thread.sleep(30)
        }
        assertTrue("Rendered preview must finish", finished)
        instrumentation.runOnMainSync {
            all(root).filterIsInstance<TextView>().single { it.text.toString() == "编辑" }.performClick()
            val editor = all(root).filterIsInstance<android.widget.EditText>().single { it.contentDescription == "文档完整正文" }
            assertEquals(source, editor.text.toString())
            assertEquals(View.VISIBLE, editor.visibility)
            editor.append("\n\n## 用户新增标题")
            all(root).filterIsInstance<TextView>().single { it.text.toString() == "保存" }.performClick()
            val saved = KnowledgeStore(context).read().documents.single { it.id == "preview-test" }
            assertEquals(source + "\n\n## 用户新增标题", saved.body)
            views.viewDocument(saved)
        }
        val secondDeadline = android.os.SystemClock.uptimeMillis() + 5000
        finished = false
        while (!finished && android.os.SystemClock.uptimeMillis() < secondDeadline) {
            instrumentation.runOnMainSync {
                val preview = all(root).filterIsInstance<TextView>().single { it.contentDescription == "Markdown 阅读正文" }
                finished = preview.text.toString().contains("用户新增标题") && !preview.text.toString().contains("## 用户新增")
            }
            if (!finished) Thread.sleep(30)
        }
        assertTrue("User documents must remain editable and render saved Markdown", finished)
        KnowledgeStore(context).removeDocument("preview-test")
    }
    @Test fun defaultSelectionAndExplicitNoneSurviveNewSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".touchtest"))
        val store = KnowledgeStore(context)
        try {
            assertEquals(KnowledgeStore.DEFAULT_DOCUMENT_ID, store.selectedDocumentId())
            store.selectDocument(null)
            assertNull(KnowledgeStore(context).selectedDocumentId())
            store.saveDocument(LoveDocument("selection-test", "其他知识库", "约会参考"))
            store.selectDocument("selection-test")
            assertEquals("selection-test", KnowledgeStore(context).selectedDocumentId())
            store.saveDocument(LoveDocument("selection-test", "其他知识库", "约会参考", false))
            assertNull(store.selectedDocumentId())
            store.saveDocument(LoveDocument("selection-test", "其他知识库", "约会参考", true))
            assertNull(KnowledgeStore(context).selectedDocumentId())
        } finally { store.removeDocument("selection-test"); store.selectDocument(KnowledgeStore.DEFAULT_DOCUMENT_ID) }
    }
    @Test fun actualModelRequestContainsOnlySnapshotAndDefaultHasNoReference() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<org.json.JSONObject>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            requests += org.json.JSONObject(buffer.readUtf8())
            val content = org.json.JSONArray(listOf("回复一", "回复二", "回复三")).toString()
            val response = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject().put("message", org.json.JSONObject().put("content", content))))
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val polisher = com.weike.ime.network.MimoTextPolisher(
            endpointProvider = { com.weike.ime.data.ModelEndpointConfig("https://example.invalid/v1", "test-only-key", "test-model") }, client = client)
        val snapshot = KnowledgeSnapshot("A", "约会宝典", "v1", listOf(KnowledgePassage("doc", "邀约", "先确认时间A专属")))
        assertTrue(polisher.suggestRepliesWithKnowledge("约会怎么说", snapshot.prompt()).isSuccess)
        assertTrue(polisher.suggestPolishesWithKnowledge("周末约会", "朋友", snapshot.prompt()).isSuccess)
        assertTrue(polisher.suggestReplies("普通原话").isSuccess)
        assertTrue(requests[0].toString().contains("先确认时间A专属"))
        assertTrue(requests[1].toString().contains("先确认时间A专属"))
        assertFalse(requests[2].toString().contains("知识库"))
        assertFalse(requests[2].toString().contains("A专属"))
    }

    @Test fun requestGuardRejectsChangedSourceDocumentSessionRelationAndMaterial() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".touchtest"))
        val store = KnowledgeStore(context)
        store.saveDocument(LoveDocument("guard-doc", "约会", "约会先确认时间"))
        try { instrumentation.runOnMainSync {
            val service = com.weike.ime.ime.WeikeInputMethodService()
            android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java).apply { isAccessible = true }.invoke(service, context)
            fun set(name: String, value: Any?) { service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
            val prepare = service.javaClass.getDeclaredMethod("prepareKnowledge", String::class.java).apply { isAccessible = true }
            fun guard(): () -> Boolean {
                set("selectedKnowledgeDocumentId", "guard-doc"); set("replySource", "约会"); set("mode", com.weike.ime.ime.KeyboardMode.ASK)
                @Suppress("UNCHECKED_CAST")
                return (prepare.invoke(service, "约会") as Pair<KnowledgeSnapshot, () -> Boolean>).second
            }
            var valid = guard(); assertTrue(valid()); set("replySource", "新的原话"); assertFalse(valid())
            valid = guard(); set("selectedKnowledgeDocumentId", null); assertFalse(valid())
            valid = guard(); set("editorSessionId", 99L); assertFalse(valid())
            valid = guard(); set("polishRelation", "朋友"); assertFalse(valid())
            valid = guard(); store.saveDocument(LoveDocument("guard-doc", "约会", "约会新内容")); assertFalse(valid())
            valid = guard(); guard(); assertFalse(valid())
        } } finally { store.removeDocument("guard-doc") }
    }
    @Test fun independentPersistenceScopeRevisionAndDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".touchtest"))
        val store = KnowledgeStore(context)
        val a = "test-A"; val b = "test-B"
        try {
            store.saveDocument(LoveDocument(a, "邀约时间", "约会先确认时间和地点"))
            store.saveDocument(LoveDocument(b, "其他", "约会B文档专属内容"))
            val snapshot = store.snapshot(a, "约会")
            assertEquals(listOf(a), snapshot.passages.map { it.documentId })
            assertFalse(snapshot.prompt().contains("B文档"))
            assertEquals(snapshot, KnowledgeStore(context).snapshot(a, "约会"))
            assertTrue(store.isCurrent(snapshot))
            store.saveDocument(LoveDocument(a, "邀约时间", "约会先问对方是否方便"))
            assertFalse(store.isCurrent(snapshot))
            store.saveDocument(LoveDocument(a, "邀约时间", "约会先问对方是否方便", false))
            assertTrue(store.snapshot(a, "约会").passages.isEmpty())
            assertTrue(store.snapshot(null, "约会").passages.isEmpty())
            store.removeDocument(a)
            // A disappeared selection is a read failure, never an implicit opt-out.
            assertEquals(a, store.snapshot(a, "约会").documentId)
            assertTrue(store.snapshot(a, "约会").failed)
            assertFalse(store.snapshot(null, "约会").failed)
            assertTrue(store.read().documents.any { it.id == b })
        } finally { store.removeDocument(a); store.removeDocument(b) }
    }
    @Test fun defaultDocumentInstalledOnceAndLegacyFoldersFlattenWithoutDataLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".touchtest"))
        val directory = java.io.File(context.cacheDir, "knowledge-migration-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val legacy = """{"books":[{"id":"old-folder","name":"旧宝典"}],"documents":[{"id":"old-doc","bookId":"old-folder","title":"旧标题","body":"完整旧正文","enabled":false,"updatedAt":123}]}"""
            java.io.File(directory, "love_knowledge_v1.json").writeText(legacy)
            val store = KnowledgeStore(isolated)
            val first = store.read()
            assertEquals(3, first.documents.size)
            val original = first.documents.single { it.id == "old-doc" }
            assertEquals("完整旧正文", original.body); assertFalse(original.enabled); assertEquals(123L, original.updatedAt)
            val builtIn = first.documents.single { it.id == KnowledgeStore.DEFAULT_DOCUMENT_ID }
            assertEquals("狗头军师知识库", builtIn.title)
            assertEquals(isolated.assets.open(KnowledgeStore.DEFAULT_ASSET).bufferedReader().use { it.readText() }, builtIn.body)
            assertTrue(builtIn.isSystem)
            assertTrue(builtIn.body.length > 90000)
            assertFalse(builtIn.body.contains("来源项目"))
            assertFalse(builtIn.body.contains("Copyright"))
            val reference = store.snapshot(builtIn.id, "今天好累怎么回复")
            assertTrue(reference.passages.isNotEmpty())
            assertTrue(reference.passages.all { it.documentId == builtIn.id })
            assertEquals(1, KnowledgeStore(isolated).read().documents.count { it.id == builtIn.id })
            assertTrue(runCatching { store.saveDocument(builtIn.copy(body = "用户修改的正文")) }.isFailure)
            assertTrue(runCatching { store.removeDocument(builtIn.id) }.isFailure)
            store.setEnabled(builtIn.id, false)
            assertFalse(KnowledgeStore(isolated).read().documents.single { it.id == builtIn.id }.enabled)
            store.setEnabled(builtIn.id, true)
            assertEquals(builtIn.body, KnowledgeStore(isolated).read().documents.single { it.id == builtIn.id }.body)
            assertEquals(original, KnowledgeStore(isolated).read().documents.single { it.id == original.id })
        } finally { directory.deleteRecursively() }
    }
    @Test fun knowledgePageIsFlatListWithDefaultDocumentAndNoFolderCreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".touchtest"))
        val store = KnowledgeStore(context)
        store.saveDocument(LoveDocument("ui-doc", "话题准备测试", "约会之前准备轻松话题"))
        try { instrumentation.runOnMainSync {
            val themed = android.view.ContextThemeWrapper(context, R.style.Theme_Weike)
            val view = KnowledgeViews(themed) {}.build()
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1900)
            fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
            val labels = all(view).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.any { it == "狗头军师知识库" })
            assertTrue(labels.any { it == "童锦程知识库" })
            assertTrue(labels.any { it == "话题准备测试" })
            assertTrue(labels.any { it == "添加" })
            assertFalse(labels.any { it.contains("新建宝典") || it.contains("全部宝典") })
            assertFalse(labels.any { it.contains("约会之前准备轻松话题") })
            assertFalse(labels.any { Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}").containsMatchIn(it) })
        } } finally { store.removeDocument("ui-doc") }
    }

    @Test fun systemDetailIsReadOnlyAndReportsSecondaryNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Weike)
        var secondary = false
        instrumentation.runOnMainSync {
            val views = KnowledgeViews(context, onDetailChanged = { secondary = it }) {}
            val root = views.build()
            for (system in KnowledgeStore(context).read().documents.filter { it.isSystem }) {
            // Even calling the editing entry point must lead to read-only detail.
            views.editDocument(system)
            fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
            val nodes = all(root)
            val labels = nodes.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(secondary)
            assertTrue("查看" in labels)
            assertFalse(labels.any { it in setOf("编辑文档", "保存", "阅读", "编辑", "启用此文档") })
            assertTrue(nodes.none { it is android.widget.EditText })
            assertTrue(views.requestLeave())
            assertFalse(secondary)
            }
        }
    }

    @Test fun upgradingExistingSystemDocumentPreservesUserContentAndDisabledState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "knowledge-system-upgrade-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val old = org.json.JSONObject().put("schema", 2).put("defaultsImported", true).put("documents", org.json.JSONArray()
                .put(org.json.JSONObject().put("id", KnowledgeStore.DEFAULT_DOCUMENT_ID).put("title", "旧系统资料").put("body", "旧版本含来源说明").put("enabled", false).put("updatedAt", 7))
                .put(org.json.JSONObject().put("id", "my-doc").put("title", "我的资料").put("body", "我的原创正文").put("enabled", true).put("updatedAt", 9)))
            java.io.File(directory, "love_knowledge_v1.json").writeText(old.toString())
            val state = KnowledgeStore(isolated).read()
            assertEquals(3, state.documents.size)
            val system = state.documents.single { it.id == KnowledgeStore.DEFAULT_DOCUMENT_ID }
            assertFalse(system.enabled)
            assertEquals(context.assets.open(KnowledgeStore.DEFAULT_ASSET).bufferedReader().use { it.readText() }, system.body)
            assertEquals(LoveDocument("my-doc", "我的资料", "我的原创正文", true, 9), state.documents.single { !it.isSystem })
            assertEquals(state, KnowledgeStore(isolated).read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun schemaThreeAddsOfflineTongDocumentWithoutReplacingExistingDocumentsOrEnableStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "knowledge-tong-upgrade-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val dog = LoveDocument(SystemKnowledge.GOUTOU_ID, "狗头军师知识库", context.assets.open(KnowledgeStore.DEFAULT_ASSET).bufferedReader().use { it.readText() }, false, 21)
            val mine = LoveDocument("my-tong", "童锦程知识库", "用户同名文档仍可编辑", true, 22)
            val values = org.json.JSONArray()
            listOf(dog, mine).forEach { values.put(org.json.JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("enabled", it.enabled).put("updatedAt", it.updatedAt)) }
            java.io.File(directory, "love_knowledge_v1.json").writeText(org.json.JSONObject().put("schema", 3).put("systemAssetVersion", 2).put("defaultsImported", true).put("documents", values).toString())
            val store = KnowledgeStore(isolated)
            val state = store.read()
            assertEquals(3, state.documents.size)
            assertEquals(dog, state.documents.single { it.id == dog.id })
            assertEquals(mine, state.documents.single { it.id == mine.id })
            val tong = state.documents.single { it.id == SystemKnowledge.TONG_ID }
            assertTrue(tong.isSystem); assertTrue(tong.enabled); assertFalse(mine.isSystem)
            assertEquals(context.assets.open("knowledge/tong-jincheng.md").bufferedReader().use { it.readText() }, tong.body)
            assertFalse(Regex("https?://|来源|调研时间|角色扮演规则|Copyright|^---", RegexOption.MULTILINE).containsMatchIn(tong.body))
            val snapshot = store.snapshot(tong.id, "给台阶 邀约 真诚")
            assertTrue(snapshot.passages.isNotEmpty())
            assertTrue(snapshot.passages.all { it.documentId == tong.id })
            assertTrue(snapshot.prompt().contains("童锦程知识库"))
            assertFalse(snapshot.prompt().contains("狗头军师知识库"))
            assertTrue(runCatching { store.saveDocument(tong.copy(body = "不应保存")) }.isFailure)
            assertTrue(runCatching { store.removeDocument(tong.id) }.isFailure)
            store.setEnabled(tong.id, false)
            assertFalse(KnowledgeStore(isolated).read().documents.single { it.id == tong.id }.enabled)
            assertTrue(store.snapshot(tong.id, "邀约").failed)
            store.setEnabled(tong.id, true)
            store.saveDocument(mine.copy(body = "用户可以编辑同名文档"))
            assertEquals("用户可以编辑同名文档", store.read().documents.single { it.id == mine.id }.body)
            assertEquals(2, KnowledgeStore(isolated).read().documents.count { it.isSystem })
            assertEquals(1, KnowledgeStore(isolated).read().documents.count { it.id == tong.id })
        } finally { directory.deleteRecursively() }
    }
}
