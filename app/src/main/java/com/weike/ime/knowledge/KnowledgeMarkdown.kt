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
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.HardLineBreak
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead

/** Native text rendering; documents cannot execute HTML or load remote scripts/images. */
object KnowledgeMarkdown {
    fun create(context: Context): Markwon = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun beforeRender(node: Node) {
                expandTables(node)
            }
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

    /** Full-width labelled rows wrap naturally on phones and remain selectable.
     * Keep the stored Markdown untouched; only adapt its display tree.
     */
    private fun expandTables(parent: Node) {
        var node = parent.firstChild
        while (node != null) {
            val next = node.next
            if (node is TableBlock) {
                val headers = mutableListOf<String>()
                var section = node.firstChild
                while (section != null) {
                    var row = section.firstChild
                    while (row != null) {
                        var cell = row.firstChild
                        var column = 0
                        val paragraph = Paragraph()
                        while (cell != null) {
                            if (section is TableHead) {
                                headers += plainText(cell)
                            } else {
                                if (column > 0) paragraph.appendChild(HardLineBreak())
                                paragraph.appendChild(StrongEmphasis().apply {
                                    appendChild(Text(headers.getOrElse(column) { "第 ${column + 1} 列" } + "："))
                                })
                                while (cell.firstChild != null) paragraph.appendChild(cell.firstChild)
                            }
                            column++
                            cell = cell.next
                        }
                        if (section !is TableHead) node.insertBefore(paragraph)
                        row = row.next
                    }
                    section = section.next
                }
                node.unlink()
            } else expandTables(node)
            node = next
        }
    }

    private fun plainText(node: Node): String = buildString {
        if (node is Text) append(node.literal)
        var child = node.firstChild
        while (child != null) {
            append(plainText(child))
            child = child.next
        }
    }
}
