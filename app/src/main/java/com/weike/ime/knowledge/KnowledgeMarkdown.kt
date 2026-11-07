package com.weike.ime.knowledge

import android.content.Context
import android.graphics.Color
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import com.weike.ime.R

/** Native text rendering; documents cannot execute HTML or load remote scripts/images. */
object KnowledgeMarkdown {
    fun create(context: Context): Markwon = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                val accent = context.getColor(R.color.weike_accent)
                fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
                builder.linkColor(accent).blockQuoteColor(accent).blockQuoteWidth(dp(3))
                    .blockMargin(dp(18)).listItemColor(accent)
                    .codeBackgroundColor(Color.rgb(255, 237, 244))
                    .codeBlockBackgroundColor(Color.rgb(249, 244, 247))
                    .headingTextSizeMultipliers(floatArrayOf(1.65f, 1.4f, 1.2f, 1.1f, 1f, 1f))
                    .headingBreakColor(Color.rgb(245, 218, 228))
                    .thematicBreakColor(Color.rgb(245, 218, 228))
            }
        }).build()
}
