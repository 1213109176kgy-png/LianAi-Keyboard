package com.weike.ime.knowledge

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.weike.ime.R
import com.weike.ime.ui.ManagementLayout
import java.util.UUID

/** Marketing-style document list, with no folder creation step. */
class KnowledgeViews(private val context: Context, private val onDetailChanged: (Boolean) -> Unit = {}, private val importFile: () -> Unit) {
    private val store = KnowledgeStore(context)
    private lateinit var root: LinearLayout
    private var query = ""
    private var leaveEditor: ((() -> Unit) -> Unit)? = null
    private val markdown by lazy { KnowledgeMarkdown.create(context) }
    private var previewVersion = 0
    private val ink get() = context.getColor(R.color.weike_text)
    private val muted get() = context.getColor(R.color.weike_muted)
    private val accent get() = context.getColor(R.color.weike_accent)
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 22) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun action(value: String, block: (View) -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 14f; setTextColor(accent)
        backgroundTintList = null; background = shape(Color.WHITE); stateListAnimator = null
        setPadding(dp(14), 0, dp(14), 0); setOnClickListener(block)
    }
    private fun header(title: String, back: (() -> Unit)? = null, right: String, onAction: (View) -> Unit) {
        val row = FrameLayout(context)
        val height = dp(ManagementLayout.HEADER_HEIGHT_DP)
        row.addView(text(title, ManagementLayout.TITLE_SP, ink, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, height).apply { setMargins(dp(70), 0, dp(70), 0) })
        back?.let { block -> row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lucide_chevron_left); setColorFilter(ink)
            val padding = dp(ManagementLayout.BACK_PADDING_DP)
            contentDescription = "返回文档列表"; setPadding(padding, padding, padding, padding); setOnClickListener { block() }
        }, FrameLayout.LayoutParams(dp(ManagementLayout.BACK_WIDTH_DP), height, Gravity.START or Gravity.CENTER_VERTICAL)) }
        if (right.isNotEmpty()) row.addView(text(right, ManagementLayout.TITLE_SP, accent).apply {
            gravity = Gravity.CENTER; contentDescription = right; isFocusable = true; setOnClickListener(onAction)
        }, FrameLayout.LayoutParams(dp(70), height, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(row, LinearLayout.LayoutParams(-1, height))
    }
    fun build(): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ManagementLayout.PAGE_HORIZONTAL_DP), dp(ManagementLayout.PAGE_TOP_DP), dp(ManagementLayout.PAGE_HORIZONTAL_DP), dp(ManagementLayout.PAGE_BOTTOM_DP))
        }
        refresh(); return root
    }
    private fun safe(action: () -> Unit) {
        try { action() } catch (_: Exception) { Toast.makeText(context, "文档读取或保存失败，原资料已保留，请重试", Toast.LENGTH_LONG).show() }
    }
    fun requestLeave(after: () -> Unit = {}): Boolean {
        val leave = leaveEditor ?: return false
        leave(after); return true
    }
    private fun hideIme() {
        root.findFocus()?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
    }
    fun refresh() { safe {
        previewVersion++
        leaveEditor = null
        onDetailChanged(false)
        val documents = store.read().documents
        // Prepare the system document while the user is still on the list.
        documents.filter { it.isSystem }.forEach { KnowledgeMarkdownCache.prepare(context, it.body) }
        root.removeAllViews()
        header("恋爱知识库", right = "添加") { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add("新建文档").setOnMenuItemClickListener { editDocument(null); true }
                menu.add("导入本机文档").setOnMenuItemClickListener { importFile(); true }
                show()
            }
        }
        val search = EditText(context).apply {
            hint = "搜索文档"; textSize = 14f; setTextColor(ink); setHintTextColor(muted)
            background = shape(Color.WHITE); isSingleLine = true; setPadding(dp(16), 0, dp(16), 0)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0); compoundDrawablePadding = dp(8)
            setText(query)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        val count = text("", 12f, muted).apply { setPadding(dp(4), dp(16), 0, dp(12)) }
        root.addView(count)
        val host = FrameLayout(context).apply { background = shape(Color.WHITE, 24); clipToOutline = true }
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val list = ListView(context).apply {
            divider = android.graphics.drawable.ColorDrawable(context.getColor(R.color.weike_key)); dividerHeight = dp(1)
            isVerticalScrollBarEnabled = false; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val empty = text("暂无文档\n点击右上角添加文档或导入本机文件", 14f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(30), dp(20), dp(30)) }
        host.addView(list, FrameLayout.LayoutParams(-1, -1)); host.addView(empty, FrameLayout.LayoutParams(-1, -1))
        var visible = documents
        val adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val doc = visible[position]
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(88); setPadding(dp(16), dp(12), dp(8), dp(12))
                    addView(ImageView(context).apply { setImageResource(R.drawable.ic_lucide_file_text); setColorFilter(accent); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(34), dp(42)))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(doc.title, 16f, ink, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(text("${if (doc.isSystem) "系统知识库 · " else ""}${if (doc.id == store.defaultDocumentId()) "默认 · " else ""}${doc.body.length} 字 · ${if (doc.enabled) "已启用" else "已停用"}", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                    addView(action("⋯") { anchor ->
                        PopupMenu(context, anchor).apply {
                            if (!doc.isSystem) menu.add("编辑文档").setOnMenuItemClickListener { editDocument(doc); true }
                            menu.add(if (doc.enabled) "停用" else "启用").setOnMenuItemClickListener { safe { store.setEnabled(doc.id, !doc.enabled); refresh() }; true }
                            menu.add("设为默认").apply {
                                isCheckable = true; isChecked = doc.id == store.defaultDocumentId()
                                setOnMenuItemClickListener {
                                    safe {
                                        store.setDefaultDocument(doc.id); refresh()
                                        Toast.makeText(context, if (doc.enabled) "已设为默认知识库" else "已设为默认知识库并启用", Toast.LENGTH_SHORT).show()
                                    }; true
                                }
                            }
                            if (!doc.isSystem) menu.add("删除文档").setOnMenuItemClickListener { deleteDocument(doc); true }
                            show()
                        }
                    }.apply { contentDescription = "${doc.title}的更多操作" }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    setOnClickListener { editDocument(doc, readInitially = true) }
                }
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> editDocument(visible[position], readInitially = true) }
        fun filter() {
            visible = documents.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
            count.text = "全部文档 · ${visible.size} 份"
            list.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (query.isBlank()) "暂无文档\n点击右上角添加文档或导入本机文件" else "没有匹配的文档"
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        filter()
    } }
    private fun deleteDocument(doc: LoveDocument) {
        AlertDialog.Builder(context).setTitle("删除文档？").setMessage("将永久删除「${doc.title}」，此操作无法撤销。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> safe { store.removeDocument(doc.id); refresh() } }.show()
    }
    private fun renderMarkdown(rendered: TextView, source: String) {
        val version = ++previewVersion
        KnowledgeMarkdownCache.cached(context, source)?.let {
            markdown.setParsedMarkdown(rendered, it)
            return
        }
        // Cold process: show readable local content immediately rather than a loading page.
        val opening = if (source.length > 1600) source.take(1600).substringBeforeLast("\n\n", source.take(1600)) else source
        markdown.setMarkdown(rendered, opening)
        KnowledgeMarkdownCache.prepare(context, source).whenComplete { parsed, error ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (version == previewVersion) {
                    if (error == null) markdown.setParsedMarkdown(rendered, parsed)
                    else rendered.text = source // Keep the complete local document readable on render failure.
                }
            }
        }
    }
    fun viewDocument(doc: LoveDocument) { safe {
        hideIme(); root.removeAllViews(); onDetailChanged(true)
        leaveEditor = { after -> refresh(); after() }
        header("查看", back = { requestLeave() }, right = if (doc.isSystem) "" else "编辑") { editDocument(doc) }
        val rendered = text("", 15f).apply {
            contentDescription = "Markdown 阅读正文"; setTextIsSelectable(true)
            setPadding(dp(18), dp(18), dp(18), dp(28)); setLineSpacing(dp(5).toFloat(), 1.12f)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true; background = shape(Color.WHITE, 24); clipToOutline = true
            addView(rendered, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        renderMarkdown(rendered, doc.body)
    } }
    fun editDocument(doc: LoveDocument?, importedTitle: String = "", importedBody: String = "", readInitially: Boolean = false) { safe {
        if (doc != null && (doc.isSystem || readInitially)) { viewDocument(doc); return@safe }
        previewVersion++
        hideIme(); root.removeAllViews(); onDetailChanged(true)
        val initialTitle = doc?.title ?: importedTitle
        val initialBody = doc?.body ?: importedBody
        val title = EditText(context).apply { hint = "文档标题"; setText(initialTitle); textSize = 17f; setTextColor(ink); isSingleLine = true; background = null; setPadding(0, dp(8), 0, dp(8)) }
        val body = EditText(context).apply {
            hint = "支持 Markdown：标题、加粗、列表、引用、表格"; textSize = 14f; setTextColor(ink); setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP; background = null; setPadding(0, dp(8), 0, dp(8)); isVerticalScrollBarEnabled = true
            contentDescription = "文档完整正文"; setText(initialBody)
        }
        val enabled = CheckBox(context).apply { text = "启用此文档"; isChecked = doc?.enabled ?: true; setTextColor(muted) }
        leaveEditor = { after ->
            fun leave() { hideIme(); refresh(); after() }
            val dirty = title.text.toString() != initialTitle || body.text.toString() != initialBody || enabled.isChecked != (doc?.enabled ?: true) || (doc == null && importedBody.isNotBlank())
            if (!dirty) leave() else AlertDialog.Builder(context).setTitle("放弃未保存的修改？")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃") { _, _ -> leave() }.show()
        }
        header(if (doc == null) "新建文档" else "编辑文档", back = { requestLeave() }, right = "保存") { saveDocument(doc, title, body, enabled) }
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE, 24); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        root.addView(form, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        form.addView(title)
        form.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        form.addView(enabled)
    } }
    private fun saveDocument(doc: LoveDocument?, title: EditText, body: EditText, enabled: CheckBox) {
        val t = title.text.toString().trim(); val b = body.text.toString()
        if (t.isBlank() || t.length > 120) { title.error = "请输入1–120字标题"; return }
        if (b.isBlank() || b.length > 300000) { body.error = "请输入正文（最多30万字）"; return }
        safe {
            store.saveDocument(LoveDocument(doc?.id ?: UUID.randomUUID().toString(), t, b, enabled.isChecked))
            hideIme(); refresh(); Toast.makeText(context, "文档已保存", Toast.LENGTH_SHORT).show()
        }
    }
}
