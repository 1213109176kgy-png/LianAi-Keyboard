package com.weike.ime

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupWindow
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weike.ime.data.AppContainer
import com.weike.ime.data.AppSettingsRepository
import com.weike.ime.data.AsrProtocol
import com.weike.ime.data.ChineseKeyboardLayout
import com.weike.ime.data.CloudProvider
import com.weike.ime.data.DictionaryPackManager
import com.weike.ime.data.HapticStrength
import com.weike.ime.data.HistoryRetention
import com.weike.ime.data.InputHistory
import com.weike.ime.data.InputHistoryType
import com.weike.ime.data.KeyboardModePreference
import com.weike.ime.data.KeyboardStartupMode
import com.weike.ime.data.KeyboardLogoConfig
import com.weike.ime.data.KeyboardLogoStyle
import com.weike.ime.data.KeyboardTheme
import com.weike.ime.data.LexiconTerm
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.PunctuationPreference
import com.weike.ime.data.TypingDictionaryEntry
import com.weike.ime.data.UsageStats
import com.weike.ime.data.WritingStyle
import com.weike.ime.data.asrProtocol
import com.weike.ime.ime.WeikeInputMethodService
import com.weike.ime.network.MimoTextPolisher
import com.weike.ime.network.MimoApiConfig
import com.weike.ime.network.ModelCatalog
import com.weike.ime.speech.RoutedAsrClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class Page {
        ACCOUNT, PROFILE, SETTINGS, CLOUD, ABOUT, USAGE_GUIDE,
        LAYOUT, KEY_EFFECTS, AUXILIARY_INPUT, TOOLBAR, KEYBOARD_MANAGEMENT,
        CLIPBOARD_SETTINGS, LOCAL_DATA, OPTIMIZE_INPUT, PERMISSION_MANAGEMENT, TEST
    }

    private class SegmentedControl(
        context: android.content.Context,
        private val options: List<String>,
        initial: Int,
        private val onSelected: (Int) -> Unit
    ) : FrameLayout(context) {
        private val density = resources.displayMetrics.density
        private val selector = View(context)
        private val labels = mutableListOf<TextView>()
        private var selected = initial.coerceIn(0, options.lastIndex)

        init {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.weike_key))
                cornerRadius = 15 * density
            }
            selector.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(255, 107, 157))
                cornerRadius = 12 * density
            }
            addView(selector)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            options.forEachIndexed { index, label ->
                row.addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setOnClickListener { setSelected(index, true) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                labels += row.getChildAt(index) as TextView
            }
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            post { render(false) }
        }

        fun setSelected(index: Int, notify: Boolean = false) {
            val next = index.coerceIn(0, options.lastIndex)
            if (next == selected && !notify) return
            selected = next
            render(true)
            if (notify) onSelected(next)
        }

        private fun render(animated: Boolean) {
            if (width == 0 || options.isEmpty()) return
            val inset = (4 * density).toInt()
            val cell = width / options.size
            selector.layoutParams = FrameLayout.LayoutParams(cell - inset * 2, height - inset * 2).apply {
                leftMargin = inset
                topMargin = inset
            }
            val targetX = (selected * cell).toFloat()
            if (animated) selector.animate().translationX(targetX).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            else selector.translationX = targetX
            labels.forEachIndexed { index, label ->
                label.setTextColor(if (index == selected) Color.WHITE else ContextCompat.getColor(context, R.color.weike_muted))
                label.typeface = if (index == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    private class CloudProviderDropdown(
        context: android.content.Context,
        initial: CloudProvider,
        private val choices: List<CloudProvider>,
        private val onSelected: (CloudProvider) -> Unit
    ) : LinearLayout(context) {
        private var selected = initial
        private val icon = ImageView(context)
        private val label = TextView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), 0, dp(context, 12), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.weike_key))
                cornerRadius = dp(context, 14).toFloat()
            }
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            addView(icon, LayoutParams(dp(context, 22), dp(context, 22)))
            label.textSize = 16f
            label.gravity = Gravity.CENTER_VERTICAL
            label.setPadding(dp(context, 10), 0, dp(context, 6), 0)
            addView(label, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_lucide_chevron_down)
                setColorFilter(ContextCompat.getColor(context, R.color.weike_muted))
                contentDescription = "展开模型厂商"
            }, LayoutParams(dp(context, 22), dp(context, 22)))
            isClickable = true
            setOnClickListener { showMenu() }
            render()
        }

        fun setSelected(value: CloudProvider, notify: Boolean = false) {
            if (selected == value && !notify) return
            selected = value
            render()
            if (notify) onSelected(value)
        }

        private fun render() {
            icon.setProviderIcon(selected)
            label.text = selected.displayName
            label.setTextColor(ContextCompat.getColor(context, R.color.weike_text))
            label.typeface = Typeface.DEFAULT_BOLD
        }

        private fun showMenu() {
            val content = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.weike_panel))
                    cornerRadius = dp(context, 16).toFloat()
                }
            }
            val search = EditText(context).apply {
                hint = "搜索模型厂商"
                setSingleLine(true)
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.weike_text))
                setHintTextColor(ContextCompat.getColor(context, R.color.weike_muted))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.weike_key))
                    cornerRadius = dp(context, 11).toFloat()
                }
                setPadding(dp(context, 12), 0, dp(context, 12), 0)
            }
            val rows = LinearLayout(context).apply { orientation = VERTICAL }
            val rowScroller = ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(rows, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            content.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 46)))
            content.addView(rowScroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(context, 8) })
            val popupHeight = minOf(
                dp(context, 460),
                (context.resources.displayMetrics.heightPixels * .68f).toInt()
            )
            val popup = PopupWindow(content, width.coerceAtLeast(dp(context, 260)), popupHeight, true).apply {
                isOutsideTouchable = true
                elevation = dp(context, 10).toFloat()
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            fun renderRows(query: String) {
                rows.removeAllViews()
                choices.filter { it.displayName.contains(query.trim(), ignoreCase = true) }.forEachIndexed { index, provider ->
                    rows.addView(providerRow(provider, popup), LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 54)).apply {
                        if (index > 0) topMargin = dp(context, 5)
                    })
                }
                if (rows.childCount == 0) {
                    rows.addView(TextView(context).apply {
                        text = "没有匹配的模型厂商"
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(context, R.color.weike_muted))
                    }, LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 54)))
                }
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = renderRows(value?.toString().orEmpty())
                override fun afterTextChanged(value: Editable?) = Unit
            })
            renderRows("")
            popup.showAsDropDown(this, 0, dp(context, 7))
        }

        private fun providerRow(provider: CloudProvider, popup: PopupWindow) = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (provider == selected) Color.rgb(255, 229, 236) else ContextCompat.getColor(context, R.color.weike_key))
                cornerRadius = dp(context, 11).toFloat()
            }
            addView(ImageView(context).apply {
                setProviderIcon(provider)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LayoutParams(dp(context, 25), dp(context, 25)))
            addView(TextView(context).apply {
                text = provider.displayName
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(context, R.color.weike_text))
                setPadding(dp(context, 11), 0, 0, 0)
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            setOnClickListener {
                setSelected(provider, true)
                popup.dismiss()
            }
        }

        private fun dp(context: android.content.Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        private fun ImageView.setProviderIcon(provider: CloudProvider) {
            clearColorFilter()
            val resourceName = provider.iconResourceName
            if (resourceName == null) {
                setImageResource(if (provider == CloudProvider.CUSTOM) R.drawable.ic_lucide_settings_2 else R.drawable.ic_xiaomi_mimo)
                if (provider == CloudProvider.CUSTOM) setColorFilter(ContextCompat.getColor(context, R.color.weike_muted))
                return
            }
            val resourceId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
            if (resourceId != 0) setImageResource(resourceId) else {
                setImageResource(R.drawable.ic_lucide_settings_2)
                setColorFilter(ContextCompat.getColor(context, R.color.weike_muted))
            }
        }
    }

    /** A compact slider drawn with the same rounded track language as segmented controls. */
    private class VolumeSlider(
        context: android.content.Context,
        initialValue: Float,
        private val onValueChanged: (Float, Boolean) -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var value = initialValue.coerceIn(0f, 1f)
        private val track = RectF()

        init {
            contentDescription = "按键音量"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
            isClickable = true
        }

        fun setValue(nextValue: Float) {
            value = nextValue.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = 2 * density
            val trackHeight = 18 * density
            val top = (height - trackHeight) / 2f
            track.set(side, top, width - side, top + trackHeight)
            paint.color = ContextCompat.getColor(context, R.color.weike_key)
            canvas.drawRoundRect(track, trackHeight / 2f, trackHeight / 2f, paint)

            if (value > 0f) {
                val fill = RectF(track.left, track.top, track.left + track.width() * value, track.bottom)
                paint.color = Color.rgb(255, 107, 157)
                canvas.drawRoundRect(fill, trackHeight / 2f, trackHeight / 2f, paint)
            }

            val thumbX = track.left + track.width() * value
            paint.setShadowLayer(2 * density, 0f, density, 0x33000000)
            setLayerType(LAYER_TYPE_SOFTWARE, paint)
            paint.color = ContextCompat.getColor(context, R.color.weike_panel)
            canvas.drawCircle(thumbX, track.centerY(), 13 * density, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = ContextCompat.getColor(context, R.color.weike_muted)
            canvas.drawCircle(thumbX, track.centerY(), 13 * density, paint)
            paint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val side = 2 * density
                    value = ((event.x - side) / (width - side * 2)).coerceIn(0f, 1f)
                    invalidate()
                    onValueChanged(value, event.actionMasked == MotionEvent.ACTION_UP)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class BlueToggle(
        context: android.content.Context,
        initial: Boolean,
        private val onChanged: (Boolean) -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var checked = initial
        private var thumbProgress = if (initial) 1f else 0f

        init {
            layoutParams = LinearLayout.LayoutParams((52 * density).toInt(), (32 * density).toInt())
            isClickable = true
            contentDescription = "开关"
        }

        fun setChecked(next: Boolean, notify: Boolean = false) {
            if (checked == next) return
            checked = next
            ValueAnimator.ofFloat(thumbProgress, if (next) 1f else 0f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    thumbProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            if (notify) onChanged(next)
        }

        override fun onDraw(canvas: Canvas) {
            val track = RectF(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = if (checked) Color.rgb(255, 107, 157) else ContextCompat.getColor(context, R.color.weike_key)
            canvas.drawRoundRect(track, height / 2f, height / 2f, paint)
            paint.color = ContextCompat.getColor(context, R.color.weike_panel)
            val radius = height / 2f - 4.5f * density
            val centerX = height / 2f + (width - height) * thumbProgress
            canvas.drawCircle(centerX, height / 2f, radius, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                setChecked(!checked, notify = true)
                performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** Live miniature of the keyboard used by display-related settings. */
    private class KeyboardSettingsPreview(context: android.content.Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var theme = KeyboardTheme.DARK
        private var candidateLevel = 0

        init {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (142 * density).toInt())
        }

        fun setTheme(value: KeyboardTheme) {
            theme = value
            invalidate()
        }

        fun setCandidateLevel(value: Int) {
            candidateLevel = value.coerceIn(-3, 3)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            when (theme) {
                KeyboardTheme.DARK -> drawKeyboard(canvas, true)
                KeyboardTheme.LIGHT -> drawKeyboard(canvas, false)
                KeyboardTheme.SYSTEM -> {
                    canvas.save()
                    canvas.clipRect(0f, 0f, width / 2f, height.toFloat())
                    drawKeyboard(canvas, false)
                    canvas.restore()
                    canvas.save()
                    canvas.clipRect(width / 2f, 0f, width.toFloat(), height.toFloat())
                    drawKeyboard(canvas, true)
                    canvas.restore()
                    paint.color = Color.argb(35, 255, 255, 255)
                    canvas.drawRect(width / 2f - density / 2f, 0f, width / 2f + density / 2f, height.toFloat(), paint)
                }
            }
        }

        private fun drawKeyboard(canvas: Canvas, dark: Boolean) {
            val surface = if (dark) Color.rgb(38, 39, 42) else Color.rgb(242, 244, 247)
            val key = if (dark) Color.rgb(76, 78, 83) else Color.WHITE
            val text = if (dark) Color.rgb(247, 248, 249) else Color.rgb(28, 31, 35)
            val muted = if (dark) Color.rgb(181, 184, 190) else Color.rgb(100, 106, 114)
            val margin = 10 * density
            val candidateHeight = 42 * density
            paint.color = surface
            canvas.drawRoundRect(rect, 20 * density, 20 * density, paint)

            paint.color = if (dark) Color.rgb(52, 54, 58) else Color.rgb(231, 235, 240)
            canvas.drawRoundRect(RectF(margin, margin, width - margin, margin + candidateHeight), 12 * density, 12 * density, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = (11 + candidateLevel * 1.15f) * density
            paint.color = text
            canvas.drawText("今天", margin + 12 * density, margin + candidateHeight * .63f, paint)
            paint.color = muted
            canvas.drawText("候选词", width * .39f, margin + candidateHeight * .63f, paint)
            canvas.drawText("输入法", width * .69f, margin + candidateHeight * .63f, paint)

            val rows = intArrayOf(8, 7, 5)
            val keyTop = margin + candidateHeight + 9 * density
            val availableHeight = height - keyTop - margin
            rows.forEachIndexed { row, count ->
                val gap = 5 * density
                val keyHeight = (availableHeight - gap * (rows.size - 1)) / rows.size
                val rowWidth = width - margin * 2 - gap * (count - 1)
                val keyWidth = rowWidth / count
                val top = keyTop + row * (keyHeight + gap)
                repeat(count) { index ->
                    val left = margin + index * (keyWidth + gap)
                    paint.color = key
                    canvas.drawRoundRect(RectF(left, top, left + keyWidth, top + keyHeight), 7 * density, 7 * density, paint)
                    if (row < 2) {
                        paint.textAlign = Paint.Align.CENTER
                        paint.typeface = Typeface.DEFAULT
                        paint.textSize = 8 * density
                        paint.color = text
                        canvas.drawText(('A'.code + (row * 8 + index) % 26).toChar().toString(), left + keyWidth / 2f, top + keyHeight * .64f, paint)
                    }
                }
            }
        }
    }

    private lateinit var container: AppContainer
    private lateinit var pageHost: FrameLayout
    private var page = Page.ACCOUNT
    private var cloudReturnPage = Page.SETTINGS
    private val bottomNavItems = mutableMapOf<Page, LinearLayout>()
    private val pageScrollPositions = mutableMapOf<Page, Int>()

    private var statMinutes: TextView? = null
    private var statWords: TextView? = null
    private var statSaved: TextView? = null
    private var statSpeed: TextView? = null
    private var historyList: LinearLayout? = null
    private var lexiconList: LinearLayout? = null
    private var typingDictionaryList: LinearLayout? = null
    private var overridesList: LinearLayout? = null
    private var keyboardModesList: LinearLayout? = null
    private var nineKeySymbolsList: LinearLayout? = null
    private var microphoneStatus: TextView? = null
    private var latestStats = UsageStats()
    private var latestHistory: List<InputHistory> = emptyList()
    private var latestLexicon: List<LexiconTerm> = emptyList()
    private var latestTypingDictionary: List<TypingDictionaryEntry> = emptyList()
    private var latestOverrides: Map<String, WritingStyle> = emptyMap()
    private var latestKeyboardModes: List<KeyboardModePreference> = emptyList()
    private var latestNineKeySymbols: List<String> = emptyList()
    private var dragHighlight: View? = null
    private var dragSource: View? = null
    private val pagePrimary = Color.rgb(255, 107, 157)
    private val managementLogoBlue = Color.rgb(0, 55, 85)
    private val appVersionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知版本"
    }
    private val settingsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showPage(parentPage(page))
        }
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStatus() }
    private var pendingLogoTheme: KeyboardTheme? = null
    private val pickKeyboardLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val theme = pendingLogoTheme ?: return@registerForActivityResult
        pendingLogoTheme = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val current = container.settings.keyboardLogo()
            val path = copyLogoToPrivateStorage(uri, theme)
            if (path == null) {
                Toast.makeText(this@MainActivity, "无法读取该图片", Toast.LENGTH_SHORT).show()
            } else {
                container.settings.saveKeyboardLogo(
                    current.copy(style = KeyboardLogoStyle.CUSTOM).let {
                        if (theme == KeyboardTheme.DARK) it.copy(darkPath = path) else it.copy(lightPath = path)
                    }
                )
                Toast.makeText(this@MainActivity, "Logo 已保存", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val pickDictionary = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            DictionaryPackManager(this@MainActivity).importLocalDictionary(uri)
                .onSuccess { count ->
                    // Imported entries are an in-memory priority overlay. Reloading
                    // librime only reopens the prebuilt table; it never deploys it.
                    sendBroadcast(Intent(WeikeInputMethodService.ACTION_RELOAD_RIME_BUNDLE).setPackage(packageName))
                    Toast.makeText(this@MainActivity, "已导入 $count 条本地词条", Toast.LENGTH_SHORT).show()
                }
                .onFailure { error -> Toast.makeText(this@MainActivity, error.message ?: "词库导入失败", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = AppContainer(this)
        setContentView(buildShell())
        onBackPressedDispatcher.addCallback(this, settingsBackCallback)
        observeData()
        lifecycleScope.launch { applyKeyboardTheme(container.settings.keyboardTheme()) }
        showPage(Page.ACCOUNT)
    }

    override fun onBackPressed() {
        if (page != Page.ACCOUNT) showPage(parentPage(page)) else super.onBackPressed()
    }

    private fun buildShell(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.weike_background))
        pageHost = FrameLayout(this@MainActivity)
        addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(255, 229, 236))
            }
            addView(bottomNavItem(R.drawable.ic_lucide_house, "首页", Page.ACCOUNT), LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomNavItem(R.drawable.ic_lucide_keyboard, "键盘", Page.TEST), LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomNavItem(R.drawable.ic_lucide_user_round, "我的", Page.PROFILE), LinearLayout.LayoutParams(0, dp(58), 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
    }

    private fun showPage(next: Page) {
        while (pageHost.childCount > 1) pageHost.removeViewAt(0)
        val previous = pageHost.getChildAt(0)
        val previousPage = page
        (previous as? ScrollView)?.let { pageScrollPositions[previousPage] = it.scrollY }
        page = next
        updateBottomNavState()
        val nextView = when (next) {
                Page.ACCOUNT -> buildAccount()
                Page.PROFILE -> buildProfile()
                Page.SETTINGS -> buildSettingsHub()
                Page.CLOUD -> buildCloudConfiguration()
                Page.ABOUT -> buildAbout()
                Page.USAGE_GUIDE -> buildUsageGuide()
                Page.LAYOUT -> buildLayoutDisplay()
                Page.KEY_EFFECTS -> buildKeyEffects()
                Page.AUXILIARY_INPUT -> buildAuxiliaryInput()
                Page.TOOLBAR -> buildToolbarSettings()
                Page.KEYBOARD_MANAGEMENT -> buildKeyboardManagement()
                Page.CLIPBOARD_SETTINGS -> buildClipboardSettings()
                Page.LOCAL_DATA -> buildLocalData()
                Page.OPTIMIZE_INPUT -> buildOptimizeInput()
                Page.PERMISSION_MANAGEMENT -> buildPermissionManagement()
                Page.TEST -> buildTestPage()
            }
        if (previous == null) {
            pageHost.addView(nextView)
        } else {
            previous.animate().cancel()
            nextView.alpha = 0f
            nextView.translationX = dp(20).toFloat()
            pageHost.addView(nextView)
            nextView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            previous.animate()
                .alpha(0f)
                .translationX(-dp(10).toFloat())
                .setDuration(160L)
                .withEndAction {
                    if (previous.parent === pageHost) pageHost.removeView(previous)
                }
                .start()
        }
        (nextView as? ScrollView)?.let { scrollView ->
            scrollView.post { scrollView.scrollTo(0, pageScrollPositions[next] ?: 0) }
        }
        settingsBackCallback.isEnabled = next != Page.ACCOUNT
        updatePermissionStatus()
    }

    private fun parentPage(current: Page): Page = when (current) {
        Page.PROFILE, Page.TEST -> Page.ACCOUNT
        Page.SETTINGS, Page.ABOUT, Page.USAGE_GUIDE -> Page.PROFILE
        Page.CLOUD -> cloudReturnPage
        Page.ACCOUNT -> Page.ACCOUNT
        else -> Page.SETTINGS
    }


    private fun buildAccount(): View = screen {
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(16))
            addView(TextView(this@MainActivity).apply {
                text = "♥"
                textSize = 24f
                setTextColor(pagePrimary)
            })
            addView(TextView(this@MainActivity).apply {
                text = "恋爱键盘"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
                setPadding(dp(8), 0, 0, 0)
            })
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 107, 157), Color.rgb(255, 160, 122))
            ).apply { cornerRadius = dp(24).toFloat() }
            addView(TextView(this@MainActivity).apply {
                text = "让聊天更有爱 💕"
                textSize = 25f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = "AI助你成为恋爱高手，每一句话都充满魅力"
                textSize = 15f
                setTextColor(Color.argb(235, 255, 255, 255))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8)
            })
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))

        addView(section("核心功能"))
        addView(homeFeatureCard(R.drawable.ic_lucide_message_circle_more, "AI智能回复", "粘贴对方消息，一键生成高情商回复", Color.rgb(255, 107, 157)))
        addView(homeFeatureCard(R.drawable.ic_lucide_wand_sparkles, "话术优化", "把你想说的话润色优化，表达更有魅力", Color.rgb(255, 160, 122)), margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
        addView(homeFeatureCard(R.drawable.ic_lucide_zap, "情感分析", "AI读懂TA的情绪和暗示，回复不再尴尬", Color.rgb(255, 71, 87)), margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))

        addView(section("如何使用 · 4步快速上手"))
        addView(card().apply {
            addView(homeStep("1", "启用恋爱键盘", "进入系统输入法设置并启用") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
            addDivider(this)
            addView(homeStep("2", "切换输入法", "在输入框中选择恋爱键盘") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
            addDivider(this)
            addView(homeStep("3", "配置文本模型", "选择 DeepSeek、Kimi 或自定义接口") {
                cloudReturnPage = Page.ACCOUNT
                showPage(Page.CLOUD)
            })
            addDivider(this)
            addView(homeStep("4", "开始使用", "进入键盘栏目，点击试用弹出输入法") { showPage(Page.TEST) })
        })

    }

    private fun buildProfile(): View = screen {
        addView(TextView(this@MainActivity).apply {
            text = "个人中心"
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
            setPadding(0, dp(8), 0, dp(18))
        })
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 107, 157), Color.rgb(255, 160, 122))
            ).apply { cornerRadius = dp(24).toFloat() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo_lianai_app)
                background = roundedBackground(Color.WHITE, 18)
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }, LinearLayout.LayoutParams(dp(66), dp(66)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "恋爱达人"
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "恋爱键盘 · 让每一句话都充满温度"
                    textSize = 13f
                    setTextColor(Color.argb(225, 255, 255, 255))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        addView(section("功能与设置"))
        addView(card().apply {
            addView(actionRow("设置", "键盘、AI、数据与权限") { showPage(Page.SETTINGS) })
            addDivider(this)
            addView(actionRow("键盘效果预览", "打开真实输入测试") { showPage(Page.TEST) })
            addDivider(this)
            addView(actionRow("使用指引", "查看键盘与 AI 功能说明") { showPage(Page.USAGE_GUIDE) })
        })
        addView(section("支持"))
        addView(card().apply {
            addView(actionRow("意见反馈", "反馈渠道正在建设中") {
                Toast.makeText(this@MainActivity, "反馈渠道正在建设中", Toast.LENGTH_SHORT).show()
            })
            addDivider(this)
            addView(actionRow("关于我们", "开发者、微信与随缘打赏") { showPage(Page.ABOUT) })
        })
        addView(section("版本管理"))
        addView(card().apply {
            addView(actionRow("版本管理", "当前版本 $appVersionName") { showVersionManagementDialog() })
        })
    }

    private fun buildLayoutDisplay(): View = screen {
        addView(subpageHeader("布局和显示") { showPage(Page.SETTINGS) })
        val themePreview = KeyboardSettingsPreview(this@MainActivity)
        addView(illustratedOptionCard("外观", themePreview) {
            val themes = KeyboardTheme.entries.toList()
            val themeControl = SegmentedControl(this@MainActivity, themes.map { it.displayName }, 0) { index ->
                lifecycleScope.launch {
                    val theme = themes[index]
                    themePreview.setTheme(theme)
                    container.settings.saveKeyboardTheme(theme)
                    applyKeyboardTheme(theme)
                }
            }
            addView(themeControl)
            lifecycleScope.launch {
                val theme = container.settings.keyboardTheme()
                themePreview.setTheme(theme)
                themeControl.setSelected(themes.indexOf(theme).coerceAtLeast(0))
            }
        })
        val candidatePreview = KeyboardSettingsPreview(this@MainActivity)
        addView(illustratedOptionCard("候选词大小", candidatePreview) {
            val levels = (-3..3).toList()
            val sizeControl = SegmentedControl(this@MainActivity, listOf("12", "14", "16", "默认", "20", "22", "24"), 0) { index ->
                candidatePreview.setCandidateLevel(levels[index])
                lifecycleScope.launch { container.settings.saveCandidateTextSizeLevel(levels[index]) }
            }
            addView(sizeControl)
            lifecycleScope.launch {
                val level = container.settings.candidateTextSizeLevel()
                candidatePreview.setCandidateLevel(level)
                sizeControl.setSelected(level + 3)
            }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildTestPage(): View = screen {
        addView(subpageHeader("测试") { showPage(Page.ACCOUNT) })
        addView(section("测试输入"))
        val input = EditText(this@MainActivity).apply {
            hint = "在这里试试拼音、英文、语音和润色"
            setTextColor(getColor(R.color.weike_text))
            setHintTextColor(getColor(R.color.weike_muted))
            textSize = 17f
            gravity = Gravity.TOP or Gravity.START
            minLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = roundedBackground(getColor(R.color.weike_key), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        addView(card().apply {
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
            addView(primaryButton("试用键盘") {
                val inputMethodManager = getSystemService(InputMethodManager::class.java)
                val currentInputMethod = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                ).orEmpty()
                if (!currentInputMethod.startsWith(packageName)) {
                    input.requestFocus()
                    Toast.makeText(this@MainActivity, "请先选择恋爱键盘，再点击试用", Toast.LENGTH_SHORT).show()
                    inputMethodManager.showInputMethodPicker()
                    return@primaryButton
                }
                input.requestFocus()
                input.setSelection(input.text.length)
                input.post {
                    inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), top = 12))
            addView(formActionButton("清空文本", false) { input.text.clear() }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), top = 10))
        })
    }

    private fun buildKeyEffects(): View = screen {
        addView(subpageHeader("按键效果") { showPage(Page.SETTINGS) })
        addView(illustratedOptionCard("触感强度", operationGuide("选择适合自己的触感强度", "每次按键会按当前强度反馈")) {
            val haptics = HapticStrength.entries.toList()
            val hapticControl = SegmentedControl(this@MainActivity, listOf("无", "系统", "弱", "适中", "较强", "强"), 0) { index ->
                lifecycleScope.launch { container.settings.saveHapticStrength(haptics[index]) }
            }
            addView(hapticControl)
            lifecycleScope.launch { hapticControl.setSelected(haptics.indexOf(container.settings.hapticStrength()).coerceAtLeast(0)) }
        })
        addView(illustratedOptionCard("按键音量", operationGuide("拖动滑块调整按键音量", "拖到最左侧即可静音")) {
            val slider = VolumeSlider(this@MainActivity, 0.7f) { value, completed ->
                if (completed) lifecycleScope.launch { container.settings.saveKeyboardSoundVolume(value) }
            }
            addView(slider)
            lifecycleScope.launch { slider.setValue(container.settings.keyboardSoundVolume()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildAuxiliaryInput(): View = screen {
        addView(subpageHeader("辅助输入") { showPage(Page.SETTINGS) })
        addView(illustratedOptionCard("标点习惯", operationGuide("选择听写与润色结果的标点规则", "设置会在下一次输出时生效")) {
            val punctuation = PunctuationPreference.entries.toList()
            val punctuationControl = SegmentedControl(this@MainActivity, punctuation.map { it.displayName }, 0) { index ->
                lifecycleScope.launch {
                    val preference = punctuation[index]
                    container.settings.savePunctuationPreference(preference)
                }
            }
            addView(punctuationControl)
            lifecycleScope.launch {
                val preference = container.settings.punctuationPreference()
                punctuationControl.setSelected(punctuation.indexOf(preference).coerceAtLeast(0))
            }
        })
        val autoCapitalize = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveEnglishAutoCapitalize(checked) }
        }
        addView(illustratedOptionCard("英文首字母自动大写", operationGuide("开启后，英文句首会自动使用大写字母"), autoCapitalize) {
            lifecycleScope.launch { autoCapitalize.setChecked(container.settings.englishAutoCapitalize()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        val doubleSpace = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveDoubleSpacePeriod(checked) }
        }
        addView(illustratedOptionCard("双击空格输入句号", operationGuide("连续双击文字键盘的空格键", "会输入一个句号并保留正常空格逻辑"), doubleSpace) {
            lifecycleScope.launch { doubleSpace.setChecked(container.settings.doubleSpacePeriod()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        val predictions = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.savePredictionEnabled(checked) }
        }
        addView(illustratedOptionCard("联想词", operationGuide("完成输入后，在候选栏显示下一词建议", "开始输入拼音或英文时会立即恢复正常候选"), predictions) {
            lifecycleScope.launch { predictions.setChecked(container.settings.predictionEnabled()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        val predictionLearning = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.savePredictionLearningEnabled(checked) }
        }
        addView(illustratedOptionCard("本机联想学习", operationGuide("仅记录已确认词与下一词的使用次数", "不会保存完整句子、拼音或可浏览的输入历史"), predictionLearning) {
            lifecycleScope.launch { predictionLearning.setChecked(container.settings.predictionLearningEnabled()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildToolbarSettings(): View = screen {
        addView(subpageHeader("定制输入法") { showPage(Page.SETTINGS) })
        addView(illustratedOptionCard("键盘模式", operationGuide("轻触开关显示或隐藏模式", "长按右侧拖动柄可调整模式顺序")) {
            keyboardModesList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(keyboardModesList)
        })
        renderKeyboardModeControls(latestKeyboardModes)
        addView(illustratedOptionCard("键盘高度", operationGuide("调整主键盘整体高度", "不会改变横屏悬浮键盘的固定比例")) {
            val levels = listOf("更低", "偏低", "默认", "偏高", "更高")
            val control = SegmentedControl(this@MainActivity, levels, 2) { index ->
                lifecycleScope.launch { container.settings.saveKeyboardHeightLevel(index - 2) }
            }
            addView(control)
            lifecycleScope.launch { control.setSelected(container.settings.keyboardHeightLevel() + 2) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        addView(illustratedOptionCard("键盘底部", operationGuide("向上抬高键盘，给底部手势区域留出空间")) {
            val offsets = listOf("0", "8dp", "16dp", "24dp", "32dp")
            val control = SegmentedControl(this@MainActivity, offsets, 0) { index ->
                lifecycleScope.launch { container.settings.saveKeyboardBottomOffsetLevel(index) }
            }
            addView(control)
            lifecycleScope.launch { control.setSelected(container.settings.keyboardBottomOffsetLevel()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        val punctuationShortcuts = BlueToggle(this@MainActivity, false) { enabled ->
            lifecycleScope.launch { container.settings.savePunctuationShortcuts(enabled) }
        }
        addView(illustratedOptionCard("逗号与句号快捷键", operationGuide("在拼音和英文空格键左侧显示逗号和句号", "逗号键长按或上滑可输入句号"), punctuationShortcuts) {
            lifecycleScope.launch { punctuationShortcuts.setChecked(container.settings.punctuationShortcuts()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        val cursorSlider = BlueToggle(this@MainActivity, true) { enabled ->
            lifecycleScope.launch { container.settings.saveCursorSliderEnabled(enabled) }
        }
        addView(illustratedOptionCard("光标滑块", operationGuide("键盘底部显示全宽滑块", "左右拖动可逐字移动光标"), cursorSlider) {
            lifecycleScope.launch { cursorSlider.setChecked(container.settings.cursorSliderEnabled()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        addView(illustratedOptionCard("Logo 定制", operationGuide("选择键盘左上角的品牌 Logo", "自定义模式可分别选择暗色和亮色键盘图片")) {
            val options = KeyboardLogoStyle.entries.toList()
            lateinit var customImageChoices: LinearLayout
            val control = SegmentedControl(this@MainActivity, options.map { it.displayName }, 0) { index ->
                val selectedStyle = options[index]
                customImageChoices.visibility = if (selectedStyle == KeyboardLogoStyle.CUSTOM) View.VISIBLE else View.GONE
                lifecycleScope.launch {
                    val current = container.settings.keyboardLogo()
                    container.settings.saveKeyboardLogo(current.copy(style = selectedStyle))
                }
            }
            addView(control)
            customImageChoices = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(actionRow("选择暗色模式图片", "仅自定义 Logo 时使用") {
                    pendingLogoTheme = KeyboardTheme.DARK
                    pickKeyboardLogo.launch("image/*")
                })
                addDivider(this)
                addView(actionRow("选择亮色模式图片", "仅自定义 Logo 时使用") {
                    pendingLogoTheme = KeyboardTheme.LIGHT
                    pickKeyboardLogo.launch("image/*")
                })
            }
            addView(customImageChoices)
            lifecycleScope.launch {
                val selected = container.settings.keyboardLogo().style
                control.setSelected(options.indexOf(selected).coerceAtLeast(0))
                customImageChoices.visibility = if (selected == KeyboardLogoStyle.CUSTOM) View.VISIBLE else View.GONE
            }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildKeyboardManagement(): View = screen {
        addView(subpageHeader("键盘管理") { showPage(Page.SETTINGS) })
        addView(illustratedOptionCard("每次调出默认模式", operationGuide(
            "选择每次调出输入法时最先显示的模式",
            "未开启的模式不会出现在可选列表中"
        )) {
            val value = TextView(this@MainActivity).apply {
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.weike_text))
                background = roundedBackground(getColor(R.color.weike_key), 13)
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
            }
            fun refresh(current: KeyboardStartupMode) {
                // The settings flow may not have emitted when this page is first
                // opened. Do not temporarily hide valid choices in that window.
                val enabled = latestKeyboardModes.ifEmpty {
                    KeyboardModePreference.entries.toList()
                }.toSet()
                val options = KeyboardStartupMode.entries.filter { option ->
                    option == KeyboardStartupMode.LAST_USED || when (option) {
                        KeyboardStartupMode.VOICE -> KeyboardModePreference.VOICE in enabled
                        KeyboardStartupMode.PINYIN, KeyboardStartupMode.ENGLISH -> KeyboardModePreference.TEXT in enabled
                        KeyboardStartupMode.ASK -> KeyboardModePreference.ASK in enabled
                        KeyboardStartupMode.CLIPBOARD -> KeyboardModePreference.CLIPBOARD in enabled
                        KeyboardStartupMode.LAST_USED -> true
                    }
                }
                value.text = current.displayName
                value.setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setItems(options.map { it.displayName }.toTypedArray()) { _, index ->
                            lifecycleScope.launch { container.settings.saveKeyboardStartupMode(options[index]) }
                            refresh(options[index])
                        }.show()
                }
            }
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            lifecycleScope.launch { refresh(container.settings.keyboardStartupMode()) }
        })
        addView(illustratedOptionCard("中文主键盘", operationGuide("选择 26 键全键盘或九宫格拼音", "切换后在下一次打开键盘时应用")) {
            val layouts = ChineseKeyboardLayout.entries.toList()
            val layoutControl = SegmentedControl(this@MainActivity, layouts.map { it.displayName }, 0) { index ->
                lifecycleScope.launch {
                    val layout = layouts[index]
                    container.settings.saveChineseKeyboardLayout(layout)
                }
            }
            addView(layoutControl)
            lifecycleScope.launch {
                val layout = container.settings.chineseKeyboardLayout()
                layoutControl.setSelected(layouts.indexOf(layout).coerceAtLeast(0))
            }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        addView(illustratedOptionCard("九宫格符号", operationGuide("点击符号可编辑", "长按右侧拖动柄可调整侧边栏顺序")) {
            nineKeySymbolsList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(nineKeySymbolsList)
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        renderNineKeySymbolControls(latestNineKeySymbols)
    }

    private fun buildClipboardSettings(): View = screen {
        addView(subpageHeader("剪贴板") { showPage(Page.SETTINGS) })
        val clipboard = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveClipboardHistoryEnabled(checked) }
        }
        addView(illustratedOptionCard(
            "剪贴板历史",
            operationGuide("复制的内容会显示在这里", "点击内容即可粘贴", "向左滑动可删除"),
            clipboard
        ) {
            lifecycleScope.launch { clipboard.setChecked(container.settings.clipboardHistoryEnabled()) }
        })
        val recentPaste = BlueToggle(this@MainActivity, true) { enabled ->
            lifecycleScope.launch { container.settings.saveRecentClipboardPasteEnabled(enabled) }
        }
        addView(illustratedOptionCard(
            "最近复制快速粘贴",
            operationGuide("复制非敏感内容后的 15 秒内，键盘左上角会显示粘贴按钮", "点击后立即粘贴，不写入剪贴板历史"),
            recentPaste
        ) {
            lifecycleScope.launch { recentPaste.setChecked(container.settings.recentClipboardPasteEnabled()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildLocalData(): View = screen {
        addView(subpageHeader("本机数据") { showPage(Page.SETTINGS) })
        addView(illustratedOptionCard("历史记录", operationGuide("选择自动保留时长", "选择“从不”会立即清除已有历史")) {
            val options = HistoryRetention.entries.toList()
            val labels = listOf("从不", "24小时", "1周", "1个月", "永久")
            val control = SegmentedControl(this@MainActivity, labels, 0) { position ->
                lifecycleScope.launch {
                    val retention = options[position]
                    container.settings.saveHistoryRetention(retention)
                    if (retention == HistoryRetention.NEVER) container.inputHistory.deleteAll()
                }
            }
            addView(control)
            addDivider(this)
            addView(actionRow("删除全部历史记录", "", destructive = true) {
                lifecycleScope.launch { container.inputHistory.deleteAll() }
            })
            lifecycleScope.launch { control.setSelected(options.indexOf(container.settings.historyRetention()).coerceAtLeast(0)) }
        })
        addView(section("最近记录"))
        historyList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(historyList)
        renderHistory(latestHistory)
        addView(illustratedOptionCard("中文离线输入", operationGuide("候选与学习数据只保留在设备本地", "清除学习数据不会删除专业词和打字词典")) {
            addView(actionRow("离线拼音词典", "Rime-Ice 基础包，安装后直接可用") {
                showDictionaryPackDialog()
            })
            addDivider(this)
            addView(actionRow("清除候选学习数据", "") {
                sendBroadcast(Intent(WeikeInputMethodService.ACTION_CLEAR_RIME_LEARNING).setPackage(packageName))
            })
            addDivider(this)
            addView(actionRow("清除联想学习数据", "不会删除专业词、打字词典或历史记录") {
                sendBroadcast(Intent(WeikeInputMethodService.ACTION_CLEAR_PREDICTION_LEARNING).setPackage(packageName))
            })
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildOptimizeInput(): View = screen {
        addView(section("离线词典增强"))
        addView(enhancedDictionaryCard())
        addView(wanxiangGrammarCard(), margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        addView(subpageHeader("优化输入") { showPage(Page.SETTINGS) })
        addView(primaryButton("添加词条") { showAddDictionaryDialog() })
        addView(section("专业词库"))
        lexiconList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(lexiconList)
        renderLexicon(latestLexicon)
        addView(section("打字词典"))
        typingDictionaryList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(typingDictionaryList)
        renderTypingDictionary(latestTypingDictionary)
        addView(section("应用文风"))
        val packageInput = field("应用包名")
        val styles = WritingStyle.entries.toList()
        var selectedStyle = 0
        val styleControl = SegmentedControl(this@MainActivity, styles.map { it.displayName }, selectedStyle) { selectedStyle = it }
        addView(illustratedOptionCard("应用文风", operationGuide("输入应用包名后选择文风", "保存后，该应用的润色会使用指定文风")) {
            addView(packageInput)
            addView(styleControl)
            addView(primaryButton("保存应用文风") {
                lifecycleScope.launch {
                    container.settings.saveOverride(packageInput.text.toString(), styles[selectedStyle])
                    packageInput.text.clear()
                }
            })
        })
        overridesList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(overridesList)
        renderOverrides(latestOverrides)
        val optimizeExpression = BlueToggle(this@MainActivity, false) { checked ->
            lifecycleScope.launch { container.settings.saveExpressionOptimization(checked) }
        }
        addView(illustratedOptionCard("优化表达", operationGuide("开启后，分类和分点表达会自动整理", "仅影响润色，不改动手动输入内容"), optimizeExpression) {
            lifecycleScope.launch { optimizeExpression.setChecked(container.settings.expressionOptimizationEnabled()) }
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
    }

    private fun buildPermissionManagement(): View = screen {
        addView(subpageHeader("权限管理") { showPage(Page.SETTINGS) })
        addView(illustratedOptionCard("输入法与系统权限", operationGuide("依次完成输入法、录音和悬浮窗授权", "系统页面授权后返回此处即可继续使用")) {
            addView(actionRow("启用恋爱键盘", "") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addDivider(this)
            addView(actionRow("切换输入法", "") {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            })
            addDivider(this)
            addView(actionRow("录音权限", "") {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            })
            addDivider(this)
            addView(actionRow("横屏悬浮窗", "") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            })
        })
        microphoneStatus = TextView(this@MainActivity).apply {
            textSize = 15f
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)
        }
        addView(microphoneStatus)
    }

    private fun buildCloudConfiguration(): View = screen {
        addView(subpageHeader("大模型配置") { showPage(cloudReturnPage) })
        addView(section("文本模型"))
        addView(providerConfigurationCard(
            isAsr = false,
            load = { container.settings.cloudApiSettings.first().text },
            loadProvider = { container.settings.textProvider() },
            save = { config, provider -> container.settings.saveTextApi(config, provider) },
            test = { config -> MimoTextPolisher(endpointProvider = { config }).testConnection() }
        ))
        addView(section("帮你回提示词"))
        addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "定义 AI 以谁的角度回复、回复什么内容。该提示词会作为系统提示词发送给文本模型。"
                textSize = 14f
                setTextColor(getColor(R.color.weike_muted))
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            val promptInput = EditText(this@MainActivity).apply {
                hint = "输入帮你回的系统提示词"
                minLines = 8
                maxLines = 14
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(getColor(R.color.weike_text))
                setHintTextColor(getColor(R.color.weike_muted))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.weike_panel))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), getColor(R.color.weike_key))
                }
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)
            }
            addView(promptInput)
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(formActionButton("恢复默认", false) {
                    promptInput.setText(AppSettingsRepository.DEFAULT_REPLY_PROMPT)
                })
                addView(formActionButton("保存提示词", true) {
                    lifecycleScope.launch {
                        runCatching { container.settings.saveReplyPrompt(promptInput.text.toString()) }
                            .onSuccess { Toast.makeText(this@MainActivity, "帮你回提示词已保存", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@MainActivity, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
                    }
                })
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12)
            }
            addView(actions)
            lifecycleScope.launch { promptInput.setText(container.settings.replyPrompt()) }
        })
        addView(section("超会说关系提示词"))
        addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "为每一种聊天对象配置独立的系统提示词。键盘选择关系后，AI会自动使用对应配置。"
                textSize = 14f
                setTextColor(getColor(R.color.weike_muted))
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            val relations = AppSettingsRepository.RELATION_TYPES
            var prompts = AppSettingsRepository.DEFAULT_RELATION_PROMPTS
            var selectedRelation = relations.first()
            val relationSelector = settingsSpinner(relations).apply {
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), top = 12)
            }
            addView(relationSelector)
            val relationPromptInput = EditText(this@MainActivity).apply {
                hint = "输入该关系的系统提示词"
                minLines = 5
                maxLines = 10
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(getColor(R.color.weike_text))
                setHintTextColor(getColor(R.color.weike_muted))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.weike_panel)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), getColor(R.color.weike_key))
                }
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10)
            }
            addView(relationPromptInput)
            relationSelector.onItemSelectedListener = simpleSelection { index ->
                selectedRelation = relations[index]
                relationPromptInput.setText(prompts[selectedRelation] ?: AppSettingsRepository.DEFAULT_RELATION_PROMPTS.getValue(selectedRelation))
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(formActionButton("恢复默认", false) {
                    relationPromptInput.setText(AppSettingsRepository.DEFAULT_RELATION_PROMPTS.getValue(selectedRelation))
                })
                addView(formActionButton("保存当前关系", true) {
                    lifecycleScope.launch {
                        runCatching { container.settings.saveRelationPrompt(selectedRelation, relationPromptInput.text.toString()) }
                            .onSuccess {
                                prompts = container.settings.relationPrompts()
                                Toast.makeText(this@MainActivity, "$selectedRelation 提示词已保存", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(this@MainActivity, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
                    }
                })
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12)
            })
            lifecycleScope.launch {
                prompts = container.settings.relationPrompts()
                relationPromptInput.setText(prompts.getValue(selectedRelation))
            }
        })
        addView(section("其他配置"))
        addView(collapsibleAsrConfiguration())
    }

    private fun collapsibleAsrConfiguration(): View = card().apply {
        setPadding(dp(16), dp(6), dp(16), dp(6))
        val content = providerConfigurationCard(
            isAsr = true,
            load = { container.settings.cloudApiSettings.first().asr },
            loadProvider = { container.settings.asrProvider() },
            save = { config, provider -> container.settings.saveAsrApi(config, provider) },
            test = { config -> RoutedAsrClient(endpointProvider = { config }).testConnection() }
        ).apply {
            visibility = View.GONE
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 4)
        }
        val chevron = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_down)
            setColorFilter(getColor(R.color.weike_muted))
            contentDescription = "展开 ASR 接口配置"
        }
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(8), 0, dp(8))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_mic)
                setColorFilter(getColor(R.color.weike_muted))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(TextView(this@MainActivity).apply {
                text = "ASR 接口（暂时用不到，不用填）"
                textSize = 15f
                setTextColor(getColor(R.color.weike_text))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
            setOnClickListener {
                val expanding = content.visibility != View.VISIBLE
                content.visibility = if (expanding) View.VISIBLE else View.GONE
                chevron.rotation = if (expanding) 180f else 0f
                chevron.contentDescription = if (expanding) "收起 ASR 接口配置" else "展开 ASR 接口配置"
            }
        }
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        addView(content)
    }

    private fun buildSettingsHub(): View = screen {
        addView(subpageHeader("设置") { showPage(Page.PROFILE) })
        addView(section("键盘设置"))
        addView(managementTileRow(
            managementTile("布局和显示", "主题与候选词", R.drawable.ic_lucide_layout_dashboard) { showPage(Page.LAYOUT) },
            managementTile("按键效果", "声音与触感", R.drawable.ic_lucide_zap) { showPage(Page.KEY_EFFECTS) }
        ))
        addView(managementTileRow(
            managementTile("辅助输入", "标点与英文", R.drawable.ic_lucide_sliders_horizontal) { showPage(Page.AUXILIARY_INPUT) },
            managementTile("键盘管理", "布局与模式", R.drawable.ic_lucide_keyboard) { showPage(Page.KEYBOARD_MANAGEMENT) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(156), top = 12))
        addView(section("AI 与数据"))
        addView(managementTileRow(
            managementTile("大模型配置", "接口、模型与提示词", R.drawable.ic_lucide_cloud) {
                cloudReturnPage = Page.SETTINGS
                showPage(Page.CLOUD)
            },
            managementTile("优化输入", "词典与文风", R.drawable.ic_lucide_book_open) { showPage(Page.OPTIMIZE_INPUT) }
        ))
        addView(managementTileRow(
            managementTile("剪贴板", "本机历史", R.drawable.ic_lucide_clipboard) { showPage(Page.CLIPBOARD_SETTINGS) },
            managementTile("本机数据", "历史与学习", R.drawable.ic_lucide_history) { showPage(Page.LOCAL_DATA) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(156), top = 12))
        addView(section("其他"))
        addView(card().apply {
            addView(actionRow("权限管理", "") { showPage(Page.PERMISSION_MANAGEMENT) })
            addDivider(this)
            addView(actionRow("关于与帮助", "") { showPage(Page.ABOUT) })
        })
    }

    private fun bottomNavItem(icon: Int, label: String, target: Page) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        bottomNavItems[target] = this
        setOnClickListener { showPage(target) }
    }

    private fun updateBottomNavState() {
        val selected = when (page) {
            Page.ACCOUNT -> Page.ACCOUNT
            Page.TEST -> Page.TEST
            else -> Page.PROFILE
        }
        bottomNavItems.forEach { (target, item) ->
            val active = target == selected
            val color = if (active) pagePrimary else getColor(R.color.weike_muted)
            (item.getChildAt(0) as? ImageView)?.setColorFilter(color)
            (item.getChildAt(1) as? TextView)?.apply {
                setTextColor(color)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            item.background = if (active) roundedBackground(Color.rgb(255, 240, 245), 16) else null
        }
    }

    private fun homeFeatureCard(icon: Int, title: String, detail: String, color: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(255, 229, 236))
        }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(color)
            background = circleBackground(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 13f
                setTextColor(getColor(R.color.weike_muted))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 4)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun homeStep(number: String, title: String, detail: String, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(TextView(this@MainActivity).apply {
            text = number
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = circleBackground(pagePrimary)
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.weike_text)) })
            addView(TextView(this@MainActivity).apply { text = detail; textSize = 12f; setTextColor(getColor(R.color.weike_muted)) })
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_right)
            setColorFilter(pagePrimary)
            contentDescription = "进入$title"
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        setOnClickListener { action() }
    }

    private fun buildAbout(): View = screen {
        addView(subpageHeader("关于我们") { showPage(Page.PROFILE) })
        addView(section("开发者与交流"))
        addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "开发者：邝广元"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = "下面是我的微信，欢迎添加我为好友，一起沟通交流恋爱键盘的使用体验、建议和想法。"
                textSize = 15f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_muted))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8)
            })
            addView(aboutImage(R.drawable.developer_wechat_qr, "开发者微信二维码"), margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                top = 16
            ))
        })

        addView(section("随缘打赏"))
        addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "如果你觉得这个项目对你有帮助，欢迎随缘打赏。感谢你的支持与鼓励。"
                textSize = 15f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_text))
            })
            addView(aboutImage(R.drawable.donation_wechat_qr, "微信支付打赏二维码"), margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                top = 16
            ))
        })
    }

    private fun aboutImage(drawable: Int, description: String) = ImageView(this).apply {
        setImageResource(drawable)
        contentDescription = description
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = roundedBackground(Color.WHITE, 16)
    }

    private fun showVersionManagementDialog() {
        AlertDialog.Builder(this)
            .setTitle("版本管理")
            .setMessage("当前版本：$appVersionName\n\n后续版本升级将在这里统一管理。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun buildUsageGuide(): View = screen {
        addView(subpageHeader("使用指引") { showPage(Page.ABOUT) })
        addView(section("语音与文本"))
        addView(card().apply {
            addView(usageGuideItem(
                R.drawable.ic_lucide_cloud,
                "配置模型",
                "进入“语音与文本”，选择厂商并填写密钥与模型。语音建议使用 ASR 模型，文本建议使用小规格文本模型。"
            ))
            addDivider(this)
            addView(usageGuideItem(
                R.drawable.ic_lucide_mic,
                "听写与润色",
                "在语音模式短按录音键开始听写；长按录音键开始润色。长按后向上滑到翻译胶囊可翻译，拖到关闭区域可取消录音。"
            ))
        })
        addView(section("文字键盘"))
        addView(card().apply {
            addView(usageGuideItem(
                R.drawable.ic_lucide_keyboard,
                "切换中文与英文",
                "点击右上角模式胶囊中的“拼”或“EN”切换文字键盘；在键盘管理中可选择全键盘拼音或九宫格。"
            ))
            addDivider(this)
            addView(usageGuideItem(
                R.drawable.ic_lucide_sliders_horizontal,
                "快速输入标点",
                "在“定制输入法”开启句号逗号快捷键后，空格左侧的按键点击输入逗号，上滑或长按输入句号。"
            ))
            addDivider(this)
            addView(usageGuideItem(
                R.drawable.ic_lucide_settings_2,
                "横屏键盘",
                "横屏使用悬浮键盘。请先在权限管理中授予悬浮窗权限；返回竖屏或收起键盘后，悬浮键盘会自动关闭。"
            ))
        })
        addView(section("帮你回"))
        addView(card().apply {
            addView(usageGuideItem(
                R.drawable.ic_lucide_wand_sparkles,
                "发出指令",
                "切换到帮你回后按下录音键说出任务。普通提问会先显示回答，可手动插入；修改、润色、翻译等操作会安全替换当前文本。"
            ))
            addDivider(this)
            addView(usageGuideItem(
                R.drawable.ic_lucide_wand_sparkles,
                "可用任务",
                "支持总结、扩写、续写、翻译、改错、整理格式、提取信息、生成回复、润色，以及“把 A 改成 B”等精确替换。"
            ))
        })
    }

    private fun providerConfigurationCard(
        isAsr: Boolean,
        load: suspend () -> ModelEndpointConfig,
        loadProvider: suspend () -> CloudProvider,
        save: suspend (ModelEndpointConfig, CloudProvider) -> Unit,
        test: suspend (ModelEndpointConfig) -> Result<Unit>
    ) = card().apply {
        val url = field("接口地址")
        val apiKey = field("接口密钥").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = field("模型名")
        var selectedProvider = CloudProvider.CUSTOM
        var previousCustomUrl = ""
        val officialEndpoint = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(getColor(R.color.weike_muted))
            setPadding(dp(2), 0, dp(2), dp(8))
            visibility = View.GONE
        }
        val protocolHint = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(getColor(R.color.weike_muted))
            setPadding(dp(2), 0, dp(2), dp(8))
            visibility = if (isAsr) View.VISIBLE else View.GONE
        }
        lateinit var readModelsButton: Button

        fun currentConfig() = ModelEndpointConfig(
            url = url.text.toString(),
            apiKey = apiKey.text.toString(),
            model = model.text.toString(),
            provider = selectedProvider
        )

        fun applyProvider(provider: CloudProvider, replaceWithPreset: Boolean) {
            selectedProvider = provider
            val preset = cloudProviderPreset(provider, isAsr)
            val fixedEndpoint = provider != CloudProvider.CUSTOM && !provider.endpointRequiresUserValue
            url.visibility = if (fixedEndpoint) View.GONE else View.VISIBLE
            officialEndpoint.visibility = if (fixedEndpoint) View.VISIBLE else View.GONE
            if (fixedEndpoint) {
                previousCustomUrl = url.text.toString().takeIf { it.isNotBlank() } ?: previousCustomUrl
                officialEndpoint.text = "官方接口：${preset.url}"
                if (replaceWithPreset) {
                    url.setText(preset.url)
                    model.setText(preset.model)
                }
            } else if (replaceWithPreset) {
                url.setText(if (provider == CloudProvider.CUSTOM) previousCustomUrl else preset.url)
                if (preset.model.isNotBlank()) model.setText(preset.model)
            }
            if (isAsr) {
                protocolHint.text = when (provider.asrProtocol()) {
                    AsrProtocol.MIMO_MULTIMODAL_HTTP -> "协议：MiMo HTTP 多模态语音识别"
                    AsrProtocol.OPENAI_AUDIO_TRANSCRIPTION -> "协议：OpenAI Audio Transcriptions（WAV 上传）"
                    AsrProtocol.DASHSCOPE_REALTIME_WEBSOCKET -> "协议：DashScope 实时 WebSocket 语音识别"
                    AsrProtocol.VOLCENGINE_REALTIME_WEBSOCKET -> "协议：火山引擎专用实时 WebSocket；需 App ID、Access Token 和 Resource ID"
                    AsrProtocol.CUSTOM -> "协议：自定义仅兼容 MiMo HTTP 音频聊天格式；其他协议请使用已适配厂商"
                }
                readModelsButton.visibility = if (provider.asrProtocol() in setOf(
                        AsrProtocol.DASHSCOPE_REALTIME_WEBSOCKET,
                        AsrProtocol.VOLCENGINE_REALTIME_WEBSOCKET
                    )) View.GONE else View.VISIBLE
            }
        }

        val availableProviders = if (isAsr) {
            listOf(
                CloudProvider.XIAOMI_MIMO,
                CloudProvider.XIAOMI_MIMO_PLAN,
                CloudProvider.QWEN,
                CloudProvider.BAILIAN,
                CloudProvider.SILICON_CLOUD,
                CloudProvider.VOLCENGINE,
                CloudProvider.DOUBAO,
                CloudProvider.CUSTOM
            )
        } else {
            CloudProvider.entries.toList()
        }
        val picker = CloudProviderDropdown(this@MainActivity, selectedProvider, availableProviders) { provider ->
            applyProvider(provider, replaceWithPreset = true)
        }

        readModelsButton = formActionButton("读取模型", false) {
            val config = currentConfig()
            val validationError = cloudEndpointKeyValidationError(config)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@formActionButton
            }
            readModelsButton.isEnabled = false
            readModelsButton.text = "读取中"
            lifecycleScope.launch {
                val result = ModelCatalog().list(config)
                readModelsButton.isEnabled = true
                readModelsButton.text = "读取模型"
                result.onSuccess { models ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("选择模型")
                        .setItems(models.toTypedArray()) { _, index -> model.setText(models[index]) }
                        .show()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.message ?: "读取模型失败", Toast.LENGTH_LONG).show()
                }
            }
        }
        lateinit var testButton: Button
        testButton = formActionButton("测试连接", false) {
            val config = currentConfig()
            val validationError = cloudConfigValidationError(config, isAsr)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@formActionButton
            }
            testButton.isEnabled = false
            testButton.text = "测试中"
            lifecycleScope.launch {
                val result = test(config)
                testButton.isEnabled = true
                testButton.text = "测试连接"
                Toast.makeText(
                    this@MainActivity,
                    if (result.isSuccess) "连接成功" else result.exceptionOrNull()?.message ?: "连接失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val saveButton = formActionButton("保存", true) {
            val config = currentConfig()
            val validationError = cloudConfigValidationError(config, isAsr)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@formActionButton
            }
            lifecycleScope.launch {
                save(config, selectedProvider)
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }
        addView(picker, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), bottom = 12))
        addView(officialEndpoint)
        addView(protocolHint)
        addView(url)
        addView(apiKey)
        addView(model)
        addView(readModelsButton, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), bottom = 8))
        addView(LinearLayout(this@MainActivity).apply {
            addView(testButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        lifecycleScope.launch {
            val provider = loadProvider()
            load().also { config ->
                val resolvedProvider = inferCloudProvider(provider, config.url)
                picker.setSelected(resolvedProvider)
                applyProvider(resolvedProvider, replaceWithPreset = false)
                url.setText(config.url)
                apiKey.setText(config.apiKey)
                model.setText(config.model)
            }
        }
    }

    private fun cloudProviderPreset(provider: CloudProvider, isAsr: Boolean): ModelEndpointConfig = ModelEndpointConfig(
        url = provider.endpointPreset,
        model = if (isAsr) when (provider) {
            CloudProvider.XIAOMI_MIMO, CloudProvider.XIAOMI_MIMO_PLAN -> "MiMo-V2.5-ASR"
            CloudProvider.QWEN, CloudProvider.BAILIAN -> "qwen3-asr-flash-realtime"
            CloudProvider.SILICON_CLOUD -> "FunAudioLLM/SenseVoiceSmall"
            CloudProvider.VOLCENGINE, CloudProvider.DOUBAO -> "volc.bigasr.auc"
            else -> provider.defaultTextModel
        } else provider.defaultTextModel,
        provider = provider
    )

    private fun inferCloudProvider(saved: CloudProvider, url: String): CloudProvider = when {
        saved != CloudProvider.CUSTOM -> saved
        url.contains("token-plan-cn.xiaomimimo.com", ignoreCase = true) -> CloudProvider.XIAOMI_MIMO_PLAN
        url.contains("api.xiaomimimo.com", ignoreCase = true) -> CloudProvider.XIAOMI_MIMO
        else -> CloudProvider.CUSTOM
    }

    private fun cloudConfigValidationError(config: ModelEndpointConfig, isAsr: Boolean = false): String? = runCatching {
        require(config.isComplete()) { "请完整填写接口信息" }
        require(config.apiKey.length <= 512 && config.apiKey.none(Char::isWhitespace)) { "接口密钥格式无效" }
        require(config.model.length <= 128 && config.model.none(Char::isWhitespace)) { "模型名称格式无效" }
        if (!isAsr) {
            MimoApiConfig.chatCompletionsEndpoint(config.url)
        } else when (config.provider.asrProtocol()) {
            AsrProtocol.MIMO_MULTIMODAL_HTTP, AsrProtocol.CUSTOM -> MimoApiConfig.chatCompletionsEndpoint(config.url)
            AsrProtocol.OPENAI_AUDIO_TRANSCRIPTION -> MimoApiConfig.audioTranscriptionsEndpoint(config.url)
            AsrProtocol.DASHSCOPE_REALTIME_WEBSOCKET -> MimoApiConfig.dashScopeRealtimeEndpoint(config.url, config.model)
            AsrProtocol.VOLCENGINE_REALTIME_WEBSOCKET -> require(
                config.url.startsWith("https://") || config.url.startsWith("wss://")
            ) { "火山引擎 ASR 地址必须使用 HTTPS 或 WSS" }
        }
    }.exceptionOrNull()?.message

    private fun cloudEndpointKeyValidationError(config: ModelEndpointConfig): String? = runCatching {
        require(config.url.isNotBlank() && config.apiKey.isNotBlank()) { "请先填写接口地址和接口密钥" }
        require(config.apiKey.length <= 512 && config.apiKey.none(Char::isWhitespace)) { "接口密钥格式无效" }
        MimoApiConfig.modelsEndpoint(config.url)
    }.exceptionOrNull()?.message


    private fun subpageHeader(title: String, back: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_left)
            setColorFilter(getColor(R.color.weike_text))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "返回"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(52), dp(64)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(52), dp(64)))
    }

    private fun dictionaryEditor(
        wordHint: String,
        pronunciationHint: String,
        action: String,
        save: suspend (String, String) -> Unit
    ) = card().apply {
        val word = field(wordHint)
        val hint = field(pronunciationHint)
        addView(word)
        addView(hint)
        addView(primaryButton(action) {
            val value = word.text.toString().trim()
            if (value.isNotBlank()) lifecycleScope.launch {
                save(value, hint.text.toString().trim())
                word.text.clear()
                hint.text.clear()
            }
        })
    }

    private fun showAddDictionaryDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val type = settingsSpinner(listOf("专业词库", "打字词典"))
        val word = field("单词或词语")
        val hint = field("拼音或备注（可选）")
        body.addView(type)
        body.addView(word)
        body.addView(hint)
        AlertDialog.Builder(this)
            .setTitle("添加词条")
            .setView(body)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val value = word.text.toString().trim()
                if (value.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    if (type.selectedItemPosition == 0) container.lexicon.upsert(LexiconTerm(value, hint.text.toString().trim()))
                    else container.typingDictionary.upsert(TypingDictionaryEntry(value, hint.text.toString().trim()))
                }
            }
            .show()
    }

    private fun observeData() {
        lifecycleScope.launch {
            container.usageStats.observe().collectLatest {
                latestStats = it ?: UsageStats()
                renderStats(latestStats)
            }
        }
        lifecycleScope.launch {
            container.inputHistory.observeRecent().collectLatest { entries ->
                latestHistory = entries
                renderHistory(entries)
            }
        }
        lifecycleScope.launch {
            container.lexicon.observeAll().collectLatest { entries ->
                latestLexicon = entries
                renderLexicon(entries)
            }
        }
        lifecycleScope.launch {
            container.typingDictionary.observeAll().collectLatest { entries ->
                latestTypingDictionary = entries
                renderTypingDictionary(entries)
            }
        }
        lifecycleScope.launch {
            container.settings.overrides.collectLatest { overrides ->
                latestOverrides = overrides
                renderOverrides(overrides)
            }
        }
        lifecycleScope.launch {
            container.settings.keyboardModes.collectLatest {
                latestKeyboardModes = it
                renderKeyboardModeControls(it)
            }
        }
        lifecycleScope.launch {
            container.settings.nineKeySymbols.collectLatest {
                latestNineKeySymbols = it
                renderNineKeySymbolControls(it)
            }
        }
    }

    private fun renderStats(stats: UsageStats) {
        val minutes = stats.dictationDurationMs / 60_000.0
        val saved = (stats.dictationUnits / 30.0).roundToInt()
        val speed = if (minutes > 0.01) (stats.dictationUnits / minutes).roundToInt() else 0
        statMinutes?.text = "${String.format(java.util.Locale.CHINA, "%.1f", minutes)} min"
        statWords?.text = "${stats.dictationUnits} 字"
        statSaved?.text = "$saved min"
        statSpeed?.text = "$speed 字/分钟"
    }

    private fun renderHistory(entries: List<InputHistory>) {
        val host = historyList ?: return
        host.removeAllViews()
        if (entries.isEmpty()) host.addView(subtitle("暂无保留的历史记录"))
        entries.forEach { entry ->
            val type = runCatching { InputHistoryType.valueOf(entry.type) }.getOrDefault(InputHistoryType.DICTATION)
            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(entry.createdAt))
            val text = if (type == InputHistoryType.QUESTION && entry.question.isNotBlank()) {
                "${type.displayName} · $time\n问：${entry.question}\n答：${entry.content}"
            } else "${type.displayName} · $time\n${entry.content}"
            host.addView(historyCard(text))
        }
    }

    private fun renderLexicon(entries: List<LexiconTerm>) {
        val host = lexiconList ?: return
        host.removeAllViews()
        entries.forEach { entry -> host.addView(itemCard("${entry.term}${if (entry.hint.isBlank()) "" else " · ${entry.hint}"}") {
            lifecycleScope.launch { container.lexicon.delete(entry.term) }
        }) }
    }

    private fun renderTypingDictionary(entries: List<TypingDictionaryEntry>) {
        val host = typingDictionaryList ?: return
        host.removeAllViews()
        entries.forEach { entry -> host.addView(itemCard("${entry.term}${if (entry.hint.isBlank()) "" else " · ${entry.hint}"}") {
            lifecycleScope.launch { container.typingDictionary.delete(entry.term) }
        }) }
    }

    private fun renderOverrides(overrides: Map<String, WritingStyle>) {
        val host = overridesList ?: return
        host.removeAllViews()
        overrides.entries.sortedBy { it.key }.forEach { (name, style) -> host.addView(itemCard("$name · ${style.displayName}") {
            lifecycleScope.launch { container.settings.removeOverride(name) }
        }) }
    }

    private fun renderKeyboardModeControls(order: List<KeyboardModePreference>) {
        val host = keyboardModesList ?: return
        host.removeAllViews()
        host.setOnDragListener { _, event ->
            fun targetIndex(): Int {
                for (index in 0 until order.size) {
                    val child = host.getChildAt(index) ?: continue
                    if (event.y < child.top + child.height / 2f) return index
                }
                return order.size
            }
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    val target = targetIndex().coerceIn(0, (order.size - 1).coerceAtLeast(0))
                    setDragHighlight(host.getChildAt(target))
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    setDragHighlight(null)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val moving = event.localState as? KeyboardModePreference ?: return@setOnDragListener false
                    val next = order.toMutableList()
                    val from = next.indexOf(moving)
                    if (from < 0) return@setOnDragListener false
                    next.removeAt(from)
                    next.add(targetIndex().coerceIn(0, next.size), moving)
                    setDragHighlight(null)
                    lifecycleScope.launch { container.settings.saveKeyboardModes(next) }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragSource?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    dragSource = null
                    setDragHighlight(null)
                    true
                }
                else -> true
            }
        }
        val enabled = order.toSet()
        (order + KeyboardModePreference.entries.filter { it !in enabled }).forEach { preference ->
            host.addView(card(top = 5).apply {
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this@MainActivity).apply {
                    text = preference.displayName
                    textSize = 18f
                    setTextColor(getColor(R.color.weike_text))
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(50), 1f))
                lateinit var toggle: BlueToggle
                toggle = BlueToggle(this@MainActivity, preference in enabled) { checked ->
                    val next = order.toMutableList()
                    if (checked && preference !in next) next += preference
                    if (!checked) next.remove(preference)
                    if (next.isEmpty()) {
                        toggle.setChecked(true)
                        Toast.makeText(this@MainActivity, "至少保留一个键盘模式", Toast.LENGTH_SHORT).show()
                    } else lifecycleScope.launch { container.settings.saveKeyboardModes(next) }
                }
                row.addView(toggle, LinearLayout.LayoutParams(dp(54), dp(34)))
                if (preference in enabled) {
                    row.addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_lucide_grip_vertical)
                        setColorFilter(getColor(R.color.weike_muted))
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setOnLongClickListener {
                            dragSource = this
                            animate().alpha(0.55f).scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                            startDragAndDrop(
                                ClipData.newPlainText("keyboard_mode", preference.name),
                                View.DragShadowBuilder(this),
                                preference,
                                0
                            )
                            true
                        }
                    }, LinearLayout.LayoutParams(dp(44), dp(50)))
                }
                addView(row)
            })
        }
    }

    private fun renderNineKeySymbolControls(symbols: List<String>) {
        val host = nineKeySymbolsList ?: return
        host.removeAllViews()
        host.setOnDragListener { _, event ->
            fun targetIndex(): Int {
                for (index in symbols.indices) {
                    val child = host.getChildAt(index) ?: continue
                    if (event.y < child.top + child.height / 2f) return index
                }
                return symbols.size
            }
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    setDragHighlight(host.getChildAt(targetIndex().coerceIn(0, (symbols.size - 1).coerceAtLeast(0))))
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> { setDragHighlight(null); true }
                DragEvent.ACTION_DROP -> {
                    val moving = event.localState as? String ?: return@setOnDragListener false
                    val next = symbols.toMutableList()
                    val from = next.indexOf(moving)
                    if (from < 0) return@setOnDragListener false
                    next.removeAt(from)
                    next.add(targetIndex().coerceIn(0, next.size), moving)
                    setDragHighlight(null)
                    lifecycleScope.launch { container.settings.saveNineKeySymbols(next) }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragSource?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                    dragSource = null
                    setDragHighlight(null)
                    true
                }
                else -> true
            }
        }
        symbols.forEach { symbol ->
            host.addView(card(top = 5).apply {
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this@MainActivity).apply {
                    text = symbol
                    textSize = 22f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(getColor(R.color.weike_text))
                    setOnClickListener { showNineKeySymbolEditor(symbol) }
                }, LinearLayout.LayoutParams(0, dp(50), 1f))
                row.addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_lucide_grip_vertical)
                    setColorFilter(getColor(R.color.weike_muted))
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnLongClickListener {
                        dragSource = this
                        animate().alpha(0.55f).scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                        startDragAndDrop(ClipData.newPlainText("nine_key_symbol", symbol), View.DragShadowBuilder(this), symbol, 0)
                        true
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(50)))
                addView(row)
            })
        }
        host.addView(primaryButton("添加符号") { showNineKeySymbolEditor() })
    }

    private fun showNineKeySymbolEditor(existing: String? = null) {
        val input = field("输入一个符号").apply { setText(existing.orEmpty()); setSelection(text.length) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加九宫格符号" else "编辑九宫格符号")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) lifecycleScope.launch {
                    val next = latestNineKeySymbols.toMutableList()
                    if (existing != null) next[next.indexOf(existing)] = value else next += value
                    container.settings.saveNineKeySymbols(next)
                }
            }
        if (existing != null) dialog.setNeutralButton("删除") { _, _ ->
            lifecycleScope.launch {
                    container.settings.saveNineKeySymbols(latestNineKeySymbols.filterNot { it == existing })
            }
        }
        dialog.show()
    }

    private fun setDragHighlight(target: View?) {
        if (dragHighlight === target) return
        dragHighlight?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.translationY(0f)?.setDuration(120)?.start()
        dragHighlight = target
        target?.animate()?.alpha(0.82f)?.scaleX(1.015f)?.scaleY(1.015f)?.translationY(dp(2).toFloat())?.setDuration(120)?.start()
    }

    private fun updatePermissionStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphoneStatus?.text = if (granted) "麦克风权限：已授权" else "麦克风权限：未授权"
        microphoneStatus?.setTextColor(getColor(if (granted) R.color.weike_accent else R.color.weike_muted))
    }

    private fun applyKeyboardTheme(theme: KeyboardTheme) {
        val mode = when (theme) {
            KeyboardTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            KeyboardTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            KeyboardTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun managementBrandHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.app_icon)
            background = roundedBackground(Color.TRANSPARENT, 16)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = "恋爱键盘"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10)
        })
        addView(TextView(this@MainActivity).apply {
            text = "本地优先，语音与离线输入"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_muted))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 3)
        })
    }

    private fun managementPreview() = FrameLayout(this).apply {
        background = roundedBackground(Color.rgb(232, 248, 244), 24)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@MainActivity).apply {
            text = "恋爱输入体验"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = "语音、拼音与英文键盘"
            textSize = 14f
            setTextColor(getColor(R.color.weike_muted))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_audio_lines)
            setColorFilter(pagePrimary)
            background = circleBackground(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END))
        addView(keyboardPreviewPlaceholder(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(98), Gravity.BOTTOM))
    }

    private fun keyboardPreviewPlaceholder() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = roundedBackground(Color.argb(92, 255, 255, 255), 16)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        listOf(10, 9, 7).forEachIndexed { row, count ->
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                repeat(count) {
                    addView(View(this@MainActivity).apply {
                        background = roundedBackground(if (row == 2 && it == 0) Color.rgb(205, 230, 222) else Color.WHITE, 5)
                    }, LinearLayout.LayoutParams(0, dp(15), 1f).apply {
                        if (it < count - 1) marginEnd = dp(4)
                    })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)))
        }
    }

    private fun managementTileRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(156), 1f).apply { marginEnd = dp(12) })
        addView(right, LinearLayout.LayoutParams(0, dp(156), 1f))
    }

    private fun managementSingleTile(tile: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tile, LinearLayout.LayoutParams(0, dp(156), 1f).apply { marginEnd = dp(12) })
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(156), 1f))
    }

    private fun managementTile(title: String, detail: String, icon: Int, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(getColor(R.color.weike_panel), 18)
        setPadding(dp(15), dp(14), dp(15), dp(13))
        val header = FrameLayout(this@MainActivity)
        val darkTheme = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        header.addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(if (darkTheme) Color.WHITE else managementLogoBlue)
            background = roundedBackground(if (darkTheme) Color.argb(38, 255, 255, 255) else Color.argb(51, 0, 55, 85), 9)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.START or Gravity.TOP))
        header.addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_right)
            setColorFilter(getColor(R.color.weike_muted))
        }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.CENTER_VERTICAL))
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10)
        })
        addView(TextView(this@MainActivity).apply {
            text = detail
            textSize = 13f
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(getColor(R.color.weike_muted))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 3)
        })
        setOnClickListener { action() }
    }

    private fun copyLogoToPrivateStorage(uri: android.net.Uri, theme: KeyboardTheme): String? {
        return runCatching {
            val target = java.io.File(
                filesDir,
                "keyboard_logo_${if (theme == KeyboardTheme.DARK) "dark" else "light"}.png"
            )
            val input = contentResolver.openInputStream(uri) ?: return@runCatching null
            input.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
            target.absolutePath
        }.getOrNull()
    }

    private fun showDictionaryPackDialog() {
        val manager = DictionaryPackManager(this)
        lifecycleScope.launch {
            val catalogResult = runCatching { manager.fetchCatalog() }
            val packs = catalogResult.getOrDefault(emptyList())
            val active = manager.activeBundleDir()
            val current = active?.let { "\u5f53\u524d\uff1a${it.name}\n\u6765\u6e90\uff1a\u5df2\u4e0b\u8f7d\u5e76\u6821\u9a8c\u7684\u5b8c\u6574\u8bcd\u5178\u5305" }
                ?: "\u5f53\u524d\uff1aRime-Ice \u57fa\u7840\u5305 (8105 + base + others)\n\u6765\u6e90\uff1a\u5185\u7f6e\u9884\u7f16\u8bd1\u8868\uff0c\u5df2\u542f\u7528\uff0c\u65e0\u9700\u9996\u6b21\u7f16\u8bd1"
            val message = if (catalogResult.isSuccess) current else "$current\n\n\u5728\u7ebf\u8bcd\u5178\u6e05\u5355\u6682\u65f6\u4e0d\u53ef\u7528\uff1b\u5f53\u524d\u8f93\u5165\u4e0d\u53d7\u5f71\u54cd\u3002"
            val builder = AlertDialog.Builder(this@MainActivity)
                .setTitle("\u79bb\u7ebf\u8bcd\u5178")
                .setMessage(message)
                .setNeutralButton("\u5bfc\u5165\u672c\u5730\u8bcd\u5178") { _, _ -> pickDictionary.launch("*/*") }
                .setNegativeButton("\u5173\u95ed", null)
            if (packs.isNotEmpty()) {
                val labels = packs.map { "${it.displayName}  ${it.version}" }.toTypedArray()
                builder.setItems(labels) { _, index ->
                    lifecycleScope.launch {
                        manager.downloadAndActivate(packs[index])
                            .onSuccess {
                                sendBroadcast(Intent(WeikeInputMethodService.ACTION_RELOAD_RIME_BUNDLE).setPackage(packageName))
                                Toast.makeText(this@MainActivity, "\u8bcd\u5178\u5305\u5df2\u542f\u7528", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { error ->
                                Toast.makeText(this@MainActivity, error.message ?: "\u8bcd\u5178\u5305\u5b89\u88c5\u5931\u8d25", Toast.LENGTH_LONG).show()
                            }
                    }
                }
            }
            builder.show()
        }
    }

    private fun enhancedDictionaryCard(): View {
        val manager = DictionaryPackManager(this)
        val installed = manager.hasEnhancedDictionary()
        val state = TextView(this).apply {
            text = if (installed) "已启用" else "未下载"
            textSize = 14f
            setTextColor(getColor(if (installed) R.color.weike_text else R.color.weike_muted))
        }
        val progress = downloadProgressView()
        val action = primaryButton(if (installed) "已启用" else "下载并启用") {}
        action.isEnabled = !installed
        action.alpha = if (installed) 0.62f else 1f
        action.setOnClickListener {
            action.isEnabled = false
            progress.visibility = View.VISIBLE
            state.text = "正在下载 0%"
            lifecycleScope.launch {
                manager.downloadAndActivate(DictionaryPackManager.ENHANCED_PACK) { update ->
                    runOnUiThread { updateDownloadProgress(progress, state, update) }
                }.onSuccess {
                    sendBroadcast(Intent(WeikeInputMethodService.ACTION_RELOAD_RIME_BUNDLE).setPackage(packageName))
                    Toast.makeText(this@MainActivity, "增强词典已启用", Toast.LENGTH_SHORT).show()
                    // Rebuild so the dependent Wanxiang card becomes available immediately.
                    showPage(Page.OPTIMIZE_INPUT)
                }.onFailure { error ->
                    progress.visibility = View.GONE
                    state.text = "下载失败：${error.message ?: "请重试"}"
                    action.isEnabled = true
                    Toast.makeText(this@MainActivity, error.message ?: "增强词典下载失败", Toast.LENGTH_LONG).show()
                }
            }
        }
        return illustratedOptionCard(
            "增强词典",
            operationGuide("扩展词汇、腾讯词汇、英文混输与 Emoji", "25 MB，下载后立即切换，不在本机编译")
        ) {
            addView(state, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
            addView(progress)
            addView(action)
        }
    }

    private fun wanxiangGrammarCard(): View {
        val manager = DictionaryPackManager(this)
        val enhancedReady = manager.hasEnhancedDictionary()
        val enabled = manager.isWanxiangEnabled()
        val state = TextView(this).apply {
            text = when {
                enabled -> "已启用"
                enhancedReady -> "未下载"
                else -> "需先启用增强词典"
            }
            textSize = 14f
            setTextColor(getColor(R.color.weike_muted))
        }
        val progress = downloadProgressView()
        val action = primaryButton(if (enabled) "移除长句增强" else "下载长句增强") {}
        action.isEnabled = enhancedReady
        action.alpha = if (enhancedReady) 1f else 0.5f
        action.setOnClickListener {
            if (enabled) {
                manager.disableWanxiang().onSuccess {
                    sendBroadcast(Intent(WeikeInputMethodService.ACTION_RELOAD_RIME_BUNDLE).setPackage(packageName))
                    showPage(Page.OPTIMIZE_INPUT)
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.message ?: "移除失败", Toast.LENGTH_LONG).show()
                }
                return@setOnClickListener
            }
            action.isEnabled = false
            progress.visibility = View.VISIBLE
            state.text = "正在下载 0%"
            lifecycleScope.launch {
                manager.downloadAndEnableWanxiang { update ->
                    runOnUiThread { updateDownloadProgress(progress, state, update) }
                }.onSuccess {
                    sendBroadcast(Intent(WeikeInputMethodService.ACTION_RELOAD_RIME_BUNDLE).setPackage(packageName))
                    progress.visibility = View.GONE
                    state.text = "已启用"
                    action.text = "移除长句增强"
                    action.isEnabled = true
                    Toast.makeText(this@MainActivity, "万象长句增强已启用", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    progress.visibility = View.GONE
                    state.text = "下载失败：${error.message ?: "请重试"}"
                    action.isEnabled = true
                    Toast.makeText(this@MainActivity, error.message ?: "万象模型下载失败", Toast.LENGTH_LONG).show()
                }
            }
        }
        return illustratedOptionCard(
            "万象长句增强",
            operationGuide("改善长句切分、上下文排序与整句候选", "约 400 MB，仅在主动下载后启用")
        ) {
            addView(state, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
            addView(progress)
            addView(action)
        }
    }

    private fun downloadProgressView() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 10_000
        progress = 0
        visibility = View.GONE
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(8), bottom = 12)
    }

    private fun updateDownloadProgress(
        progress: ProgressBar,
        state: TextView,
        update: com.weike.ime.data.DictionaryDownloadProgress
    ) {
        if (update.totalBytes > 0) {
            progress.progress = (update.fraction * progress.max).roundToInt()
            state.text = "正在下载 ${(update.fraction * 100).roundToInt()}%"
        } else {
            state.text = "正在下载 ${(update.downloadedBytes / 1024 / 1024)} MB"
        }
    }

    private fun screen(children: LinearLayout.() -> Unit): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(getColor(R.color.weike_background))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(38), dp(20), dp(28))
            children()
        })
    }

    private fun brandTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
    }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 28, bottom = 10)
    }

    private fun subtitle(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(getColor(R.color.weike_muted))
        setLineSpacing(dp(3).toFloat(), 1f)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8)
        visibility = View.GONE
    }

    private fun card(top: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(getColor(R.color.weike_panel), 24)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = top)
    }

    private fun illustratedOptionCard(
        title: String,
        guide: View? = null,
        trailing: View? = null,
        content: LinearLayout.() -> Unit = {}
    ) = card().apply {
        guide?.let {
            val guideHeight = it.layoutParams?.height?.takeIf { height -> height > 0 }
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
            addView(it, margins(ViewGroup.LayoutParams.MATCH_PARENT, guideHeight, bottom = 12))
        }
        val titleRow = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        trailing?.let { titleRow.addView(it, LinearLayout.LayoutParams(dp(54), dp(34))) }
        addView(titleRow)
        content()
    }

    private fun operationGuide(vararg steps: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))
        addView(View(this@MainActivity).apply {
            background = roundedBackground(Color.rgb(255, 107, 157), 2)
        }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            steps.filter { it.isNotBlank() }.forEachIndexed { index, value ->
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = if (index == 0) 14f else 13f
                    typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (index == 0) getColor(R.color.weike_text) else getColor(R.color.weike_muted))
                    setLineSpacing(dp(2).toFloat(), 1f)
                }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = if (index == steps.lastIndex) 0 else 4))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun usageGuideItem(icon: Int, title: String, detail: String) = LinearLayout(this).apply {
        gravity = Gravity.TOP
        setPadding(0, dp(10), 0, dp(10))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(pagePrimary)
            contentDescription = title
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(13) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 14f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_muted))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 4)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun actionRow(title: String, detail: String, destructive: Boolean = false, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                setTextColor(getColor(if (destructive) android.R.color.holo_red_dark else R.color.weike_text))
            })
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_right)
            setColorFilter(getColor(R.color.weike_muted))
            setPadding(dp(7), dp(12), dp(7), dp(12))
            contentDescription = "进入"
        }, LinearLayout.LayoutParams(dp(34), dp(48)))
        setOnClickListener { action() }
    }

    private fun settingRow(title: String, detail: String, control: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = title; textSize = 18f; setTextColor(getColor(R.color.weike_text)) })
            addView(TextView(this@MainActivity).apply { text = detail; textSize = 13f; setTextColor(getColor(R.color.weike_muted)) })
        }, LinearLayout.LayoutParams(0, dp(62), 1f))
        addView(control, LinearLayout.LayoutParams(dp(150), dp(54)))
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        textSize = 16f
        setTextColor(getColor(R.color.weike_text))
        setHintTextColor(getColor(R.color.weike_muted))
        background = roundedBackground(getColor(R.color.weike_key), 12)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), bottom = 8)
    }

    private fun settingsSpinner(options: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
        setPadding(0, 0, 0, 0)
    }

    private fun historyCard(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextColor(getColor(R.color.weike_text))
        background = roundedBackground(getColor(R.color.weike_panel), 22)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8)
    }

    private fun statsRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(108), 1f))
        addView(right, LinearLayout.LayoutParams(0, dp(108), 1f))
    }

    private fun statCell(label: String, icon: Int, bind: (TextView) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(getColor(R.color.weike_muted))
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        val value = TextView(this@MainActivity).apply {
            text = "0"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }
        bind(value)
        addView(value, margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.weike_muted))
            layoutParams = margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 5)
        })
    }

    private fun itemCard(value: String, remove: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBackground(getColor(R.color.weike_panel), 18)
        setPadding(dp(16), dp(7), dp(8), dp(7))
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(smallButton("删除", remove))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 7)
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(getColor(R.color.weike_text))
        background = roundedBackground(getColor(R.color.weike_key), 12)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { action() }
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = roundedBackground(pagePrimary, 24)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 4)
        setOnClickListener { action() }
    }

    private fun formActionButton(label: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (filled) Color.WHITE else pagePrimary)
        background = roundedBackground(if (filled) pagePrimary else getColor(R.color.weike_key), 18)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { action() }
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(getColor(R.color.weike_key)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun roundedBackground(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun circleBackground(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color)
    }

    private fun margins(width: Int, height: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun simpleSelection(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
