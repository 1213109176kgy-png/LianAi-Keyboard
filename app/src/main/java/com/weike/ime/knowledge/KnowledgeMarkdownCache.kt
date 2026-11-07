package com.weike.ime.knowledge

import android.content.Context
import android.text.Spanned
import android.text.SpannedString
import android.util.LruCache
import com.weike.ime.R
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

/** Process-wide bounded cache; text edits and display configuration changes get new entries. */
object KnowledgeMarkdownCache {
    private val cache = object : LruCache<String, Spanned>(600_000) {
        override fun sizeOf(key: String, value: Spanned) = value.length.coerceAtLeast(1)
    }
    private val pending = mutableMapOf<String, CompletableFuture<Spanned>>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "knowledge-markdown-cache").apply { isDaemon = true }
    }
    private fun key(context: Context, source: String): String {
        val content = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${context.resources.configuration}:${context.getColor(R.color.weike_accent)}:$content"
    }
    fun cached(context: Context, source: String): Spanned? = cache.get(key(context, source))

    fun prepare(context: Context, source: String): CompletableFuture<Spanned> {
        val key = key(context, source)
        synchronized(pending) {
            cache.get(key)?.let { return CompletableFuture.completedFuture(it) }
            pending[key]?.let { return it }
            val future = CompletableFuture<Spanned>()
            pending[key] = future
            // Never retain an Activity through cached spans, parsers or queued work.
            val app = context.applicationContext
            worker.execute {
                try {
                    val parsed = SpannedString(KnowledgeMarkdown.create(app).toMarkdown(source))
                    cache.put(key, parsed)
                    future.complete(parsed)
                } catch (error: Exception) { future.completeExceptionally(error) }
                finally { synchronized(pending) { pending.remove(key) } }
            }
            return future
        }
    }
}
