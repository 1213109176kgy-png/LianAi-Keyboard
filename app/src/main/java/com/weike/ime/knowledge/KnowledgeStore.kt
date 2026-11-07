package com.weike.ime.knowledge

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Flat local documents; old folder documents retain their IDs and contents. */
class KnowledgeStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "love_knowledge_v1.json"))
    private val selection = context.getSharedPreferences("love_knowledge_selection_v1", Context.MODE_PRIVATE)
    private var cached: State? = null
    private var cachedStamp: Triple<Long, Long, Long>? = null
    data class State(val documents: List<LoveDocument>, val version: String)
    companion object {
        const val DEFAULT_DOCUMENT_ID = SystemKnowledge.GOUTOU_ID
        const val DEFAULT_ASSET = "knowledge/goutoujunshi.md"
        private val lock = Any()
        private var writeGeneration = 0L
    }
    fun read(): State = synchronized(lock) {
        val stamp = Triple(file.baseFile.lastModified(), file.baseFile.length(), writeGeneration)
        if (stamp == cachedStamp) cached?.let { return@synchronized it }
        val raw = if (file.baseFile.exists()) file.openRead().use { it.readBytes().toString(Charsets.UTF_8) } else null
        val root = raw?.let(::JSONObject) ?: JSONObject()
        val values = root.optJSONArray("documents") ?: JSONArray()
        val documents = (0 until values.length()).map { values.getJSONObject(it).let { d ->
            LoveDocument(d.getString("id"), d.getString("title"), d.getString("body"), d.optBoolean("enabled", true), d.optLong("updatedAt", 0))
        } }.toMutableList()
        // Upgrade the app-owned document in place; preserve its enable state and
        // every user document. The system document is no longer editable/deletable.
        val revisions = root.optJSONObject("systemAssetVersions")
        var upgraded = false
        SystemKnowledge.entries.forEachIndexed { index, entry ->
            val previous = documents.firstOrNull { it.id == entry.id }
            val revision = revisions?.optInt(entry.id, 0)
                ?: if (entry.id == DEFAULT_DOCUMENT_ID) root.optInt("systemAssetVersion", 0) else 0
            if (previous == null || revision < entry.revision) {
                val body = context.assets.open(entry.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                documents.removeAll { it.id == entry.id }
                documents.add(index.coerceAtMost(documents.size), LoveDocument(entry.id, entry.title, body, previous?.enabled ?: true))
                upgraded = true
            }
        }
        if (upgraded || root.optInt("schema", 1) < 4) return@synchronized write(documents)
        state(documents, raw.orEmpty()).also { cached = it; cachedStamp = stamp }
    }
    private fun state(documents: List<LoveDocument>, raw: String) = State(documents,
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
    private fun write(documents: List<LoveDocument>): State {
        val values = JSONArray()
        documents.forEach { values.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("enabled", it.enabled).put("updatedAt", it.updatedAt)) }
        val revisions = JSONObject().apply { SystemKnowledge.entries.forEach { put(it.id, it.revision) } }
        val raw = JSONObject().put("schema", 4).put("defaultsImported", true).put("systemAssetVersions", revisions).put("documents", values).toString()
        val stream = file.startWrite()
        try {
            stream.write(raw.toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
        writeGeneration++
        return state(documents, raw).also {
            cached = it
            cachedStamp = Triple(file.baseFile.lastModified(), file.baseFile.length(), writeGeneration)
        }
    }
    fun saveDocument(doc: LoveDocument) = synchronized(lock) {
        require(!doc.isSystem) { "系统知识库仅支持查看和启用/停用" }
        require(doc.title.isNotBlank() && doc.title.length <= 120 && doc.body.isNotBlank() && doc.body.length <= 300000)
        write(read().documents.filterNot { it.id == doc.id } + doc.copy(updatedAt = System.currentTimeMillis()))
    }
    fun removeDocument(id: String) = synchronized(lock) {
        require(!SystemKnowledge.contains(id)) { "系统知识库不可删除" }
        write(read().documents.filterNot { it.id == id })
    }
    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val documents = read().documents
        require(documents.any { it.id == id }) { "知识库不存在" }
        write(documents.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }
    fun selectedDocumentId(): String? {
        val id = selection.getString("document", defaultDocumentId())?.takeIf { it.isNotEmpty() } ?: return null
        if (read().documents.any { it.id == id && it.enabled }) return id
        selectDocument(null)
        return null
    }
    fun defaultDocumentId(): String? {
        val id = selection.getString("default_document", DEFAULT_DOCUMENT_ID) ?: return null
        return id.takeIf { selected -> read().documents.any { it.id == selected } }
    }
    fun setDefaultDocument(id: String) = synchronized(lock) {
        val document = read().documents.firstOrNull { it.id == id } ?: error("知识库不存在")
        if (!document.enabled) setEnabled(id, true)
        // Set the baseline and current keyboard selection together. An explicit
        // subsequent keyboard choice (including none) still takes precedence.
        selection.edit().putString("default_document", id).putString("document", id).apply()
    }
    fun selectDocument(id: String?) { selection.edit().putString("document", id.orEmpty()).apply() }
    fun snapshot(documentId: String?, query: String): KnowledgeSnapshot {
        if (documentId == null) return KnowledgeSnapshot(null, "", "", emptyList())
        return try {
            val state = read()
            val document = state.documents.firstOrNull { it.id == documentId && it.enabled }
                ?: return KnowledgeSnapshot(documentId, "", state.version, emptyList(), true)
            KnowledgeSnapshot(document.id, document.title, state.version, KnowledgeRules.retrieve(state.documents, document.id, query))
        } catch (_: Exception) { KnowledgeSnapshot(documentId, "", "", emptyList(), true) }
    }
    fun isCurrent(snapshot: KnowledgeSnapshot): Boolean = snapshot.documentId == null ||
        (!snapshot.failed && runCatching { read().version == snapshot.version }.getOrDefault(false))
}
