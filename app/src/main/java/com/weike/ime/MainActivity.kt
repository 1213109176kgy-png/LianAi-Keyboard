package com.weike.ime

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.weike.ime.ui.SubscriptionViews
import com.weike.ime.ui.ModelConfigurationViews
import com.weike.ime.network.ModelThinking
import com.weike.ime.data.PendingPurchase
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
import com.weike.ime.network.UpdateClient
import com.weike.ime.network.UserAuthClient
import com.weike.ime.network.SubscriptionClient
import com.weike.ime.network.SubscriptionPlan
import com.weike.ime.network.SubscriptionApiException
import java.io.File
import kotlinx.coroutines.Job
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class Page {
        ACCOUNT, PROFILE, SUBSCRIPTION, SETTINGS, CLOUD, ABOUT, USAGE_GUIDE,
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
            label.textSize = 14f
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
                textSize = 14f
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
                textSize = 14f
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
    /** Set when a newer release is known; drives the profile hint and the update dialog. */
    private var pendingUpdate: UpdateClient.UpdateInfo? = null
    private var versionRowDetail: TextView? = null
    private val userAuthClient by lazy { UserAuthClient() }
    private val subscriptionClient by lazy { SubscriptionClient() }
    private var orderPollJob: Job? = null
    private var purchaseBusy = false
    private var paymentStatusText: TextView? = null
    private val pagePrimary: Int get() = getColor(R.color.weike_accent)
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
            val path = withContext(Dispatchers.IO) { copyLogoToPrivateStorage(uri, theme) }
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
        // Silent daily check; never blocks or prompts on its own.
        lifecycleScope.launch { fetchLatestUpdate(silent = true) }
        showPage(openPageFromIntent(intent) ?: if (pendingPurchase() != null || isPaymentReturn(intent)) Page.SUBSCRIPTION else Page.ACCOUNT)
        maybeRequestMicPermission()
    }

    /** 首次打开引导授权麦克风：语音输入（录音转文字）依赖录音权限。 */
    private fun maybeRequestMicPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val preferences = getSharedPreferences("onboarding", MODE_PRIVATE)
        val asked = preferences.getBoolean(KEY_MIC_PERMISSION_ASKED, false)
        if (granted || asked) return
        preferences.edit().putBoolean(KEY_MIC_PERMISSION_ASKED, true).apply()
        themedDialog("开启麦克风权限", "用于键盘语音输入") { body, dialog ->
            body.addView(TextView(this).apply {
                text = "按住键盘上的麦克风说话，即可把语音实时转成文字。\n\n录音仅用于即时识别，不保存任何录音文件。"
                textSize = 13f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_text))
            })
            body.addView(compactFormActionButton("开启麦克风", true) {
                dialog.dismiss()
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), top = 14))
            body.addView(compactFormActionButton("稍后再说", false) { dialog.dismiss() },
                margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), top = 8))
        }.show()
    }

    /** IME setup-guide buttons jump straight to the subscription/model pages. */
    private fun openPageFromIntent(intent: Intent?): Page? = when (intent?.getStringExtra("model_setup_destination") ?: intent?.getStringExtra(EXTRA_OPEN_PAGE)) {
        "vip", "SUBSCRIPTION" -> Page.SUBSCRIPTION
        "models", "CLOUD" -> Page.CLOUD
        else -> null
    }

    override fun onResume() {
        super.onResume()
        // Coming back from IME settings / the picker: if the keyboard is now
        // enabled and selected, the setup guide is obsolete — close it.
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        if (selected.startsWith(packageName) &&
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        ) {
            keyboardSetupDialog?.takeIf { it.isShowing }?.dismiss()
        }
        keyboardSetupDialog = null
        pendingPurchase()?.takeIf { it.orderId.isNotBlank() }?.let { pending ->
            container.userSession.token()?.let { startOrderPolling(pending.orderId, it) }
        }
    }

    override fun onPause() {
        orderPollJob?.cancel()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPageFromIntent(intent)?.let {
            showPage(it)
            return
        }
        if (isPaymentReturn(intent)) {
            // The browser/deep link carries no proof of payment. Query only
            // the persisted order belonging to the currently signed-in user.
            showPage(Page.SUBSCRIPTION)
            pendingPurchase()?.takeIf { it.orderId.isNotBlank() }?.let { pending ->
                container.userSession.token()?.let { startOrderPolling(pending.orderId, it) }
            }
        }
    }

    private fun isPaymentReturn(intent: Intent?): Boolean =
        intent?.data?.let { it.scheme == "lianai" && it.host == "payment-return" } == true

    private fun pendingPurchase(): PendingPurchase? =
        container.pendingPurchase.read(container.userSession.user()?.id)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            hideKeyboardWhenTouchingOutsideFocusedInput(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun hideKeyboardWhenTouchingOutsideFocusedInput(event: MotionEvent) {
        val focusedInput = currentFocus as? EditText ?: return
        val inputBounds = Rect()
        focusedInput.getGlobalVisibleRect(inputBounds)
        if (inputBounds.contains(event.rawX.toInt(), event.rawY.toInt())) return
        focusedInput.clearFocus()
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(focusedInput.windowToken, 0)
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
                setColor(getColor(R.color.weike_panel))
                setStroke(dp(1), getColor(R.color.weike_key))
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
                Page.SUBSCRIPTION -> buildSubscriptionPage()
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
        Page.SUBSCRIPTION -> Page.PROFILE
        Page.SETTINGS, Page.ABOUT, Page.USAGE_GUIDE -> Page.PROFILE
        Page.CLOUD -> cloudReturnPage
        Page.ACCOUNT -> Page.ACCOUNT
        else -> Page.SETTINGS
    }


    private fun buildAccount(): View = screen {
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(16))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo_lianai_app)
                contentDescription = "恋爱键盘 Logo"
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = roundedBackground(Color.WHITE, 12)
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(TextView(this@MainActivity).apply {
                text = "恋爱键盘"
                textSize = 14f
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
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = "AI助你成为恋爱高手，每一句话都充满魅力"
                textSize = 12f
                setTextColor(Color.argb(235, 255, 255, 255))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8)
            })
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))

        addView(section("核心功能"))
        // Icon trio follows one warm-rose palette: brand pink, brand orange,
        // rose red — no stray coral/saturated hues.
        addView(homeFeatureCard(R.drawable.ic_lucide_message_circle_more, "AI智能回复", "粘贴对方消息，一键生成高情商回复", Color.rgb(255, 107, 157)))
        addView(homeFeatureCard(R.drawable.ic_lucide_wand_sparkles, "话术优化", "把你想说的话润色优化，表达更有魅力", Color.rgb(255, 160, 122)), margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
        addView(homeFeatureCard(R.drawable.ic_lucide_zap, "情感分析", "AI读懂TA的情绪和暗示，回复不再尴尬", Color.rgb(231, 84, 128)), margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))

        addView(section("如何使用 · 4步快速上手"))
        addView(card().apply {
            addView(homeStep("1", "启用恋爱键盘", "进入系统输入法设置并启用") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
            addDivider(this)
            addView(homeStep("2", "切换输入法", "在输入框中选择恋爱键盘") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
            addDivider(this)
            addView(homeStep("3", "配置AI大模型", "购买套餐免配置，或填自己的模型接口") {
                cloudReturnPage = Page.ACCOUNT
                showPage(Page.CLOUD)
            })
            addDivider(this)
            addView(homeStep("4", "开始使用", "进入键盘栏目，点击试用弹出输入法") { showPage(Page.TEST) })
        })

    }

    private fun buildProfile(): View = screen {
        val sessionUser = container.userSession.user()
        addView(TextView(this@MainActivity).apply {
            text = "个人中心"
            gravity = Gravity.CENTER
            textSize = 14f
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
            // The hero card doubles as the login/logout entry: tapping it when
            // logged out opens the auth dialog, when logged in it offers logout.
            setOnClickListener {
                if (sessionUser == null) showLoginDialog()
                else confirmLogout()
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo_lianai_app)
                background = roundedBackground(Color.WHITE, 18)
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }, LinearLayout.LayoutParams(dp(66), dp(66)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = if (sessionUser != null) sessionUser.maskedPhone else "未登录"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                val heroSubtitle = TextView(this@MainActivity).apply {
                    text = if (sessionUser != null) "未购买套餐" else "点击登录或注册"
                    textSize = 12f
                    setTextColor(Color.argb(225, 255, 255, 255))
                }
                addView(heroSubtitle)
                // Refresh the second line with the live subscription once it loads.
                if (sessionUser != null) {
                    lifecycleScope.launch {
                        val token = container.userSession.token() ?: return@launch
                        subscriptionClient.subscription(token).onSuccess { status ->
                            if (status.active) {
                                val expire = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                                    .format(java.util.Date(status.expiresAt))
                                heroSubtitle.text = "${status.planName} · $expire 到期"
                            }
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_chevron_right)
                setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
        })

        addView(section("订阅"))
        addView(card().apply {
            addView(
                actionRow(
                    "订阅套餐",
                    if (sessionUser != null) "查看套餐与订阅状态" else "购买套餐需先登录",
                    icon = R.drawable.ic_lucide_zap
                ) { showPage(Page.SUBSCRIPTION) }
            )
        })
        addView(section("功能与设置"))
        addView(card().apply {
            addView(actionRow("设置", "键盘、AI、数据与权限", icon = R.drawable.ic_lucide_sliders_horizontal) { showPage(Page.SETTINGS) })
            addDivider(this)
            addView(actionRow("键盘效果预览", "进入试用页体验完整键盘", icon = R.drawable.ic_lucide_keyboard) { showPage(Page.TEST) })
            addDivider(this)
            addView(actionRow("使用指引", "查看键盘与 AI 功能说明", icon = R.drawable.ic_lucide_book_open) { showPage(Page.USAGE_GUIDE) })
        })
        addView(section("支持"))
        addView(card().apply {
            addView(actionRow("意见反馈", "扫码添加微信，反馈问题与建议", icon = R.drawable.ic_lucide_message_circle_more) {
                showFeedbackDialog()
            })
            addDivider(this)
            addView(actionRow("关于我们", "开发者、微信与随缘打赏", icon = R.drawable.ic_lucide_house) { showPage(Page.ABOUT) })
            addDivider(this)
            val row = actionRow("检查更新", versionManagementHint(), icon = R.drawable.ic_lucide_history) {
                showVersionManagementDialog()
            }
            // actionRow renders [title, detail] inside its first child column.
            versionRowDetail = (row.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView
            addView(row)
        })
        addView(View(this@MainActivity).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)) })
    }

    private fun buildSubscriptionPage(): View = screen {
        val views = SubscriptionViews(this@MainActivity)
        addView(views.header { showPage(Page.PROFILE) })
        val statusHost = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(statusHost)
        statusHost.addView(views.loading("正在获取订阅状态…"))

        paymentStatusText = null
        pendingPurchase()?.let { pending ->
            val (pendingCard, statusText) = views.pendingCard(pending,
                onResume = { resumePurchase(pending) },
                onQuery = { container.userSession.token()?.let { startOrderPolling(pending.orderId, it) } })
            paymentStatusText = statusText
            addView(pendingCard, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
        }
        addView(views.section())
        val plansHost = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(plansHost)
        plansHost.addView(views.loading("正在加载套餐…"))

        lifecycleScope.launch {
            val token = container.userSession.token()
            val statusResult = token?.let { subscriptionClient.subscription(it) }
            statusHost.removeAllViews()
            statusHost.addView(views.statusCard(statusResult?.getOrNull(), token != null, statusResult?.isFailure == true))

            val plansResult = subscriptionClient.plans()
            val plans = plansResult.getOrDefault(emptyList())
            val alipayEnabled = subscriptionClient.alipayEnabled().getOrDefault(false)
            plansHost.removeAllViews()
            if (plans.isEmpty()) {
                plansHost.addView(views.empty(if (plansResult.isFailure) "套餐加载失败，请稍后重试" else "暂无可购买的套餐") {
                    showPage(Page.SUBSCRIPTION)
                })
                return@launch
            }
            plans.forEachIndexed { index, plan ->
                plansHost.addView(views.planCard(plan, alipayEnabled) { startPurchase(plan) },
                    margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = if (index > 0) 12 else 0))
            }
        }
    }

    private fun startPurchase(plan: SubscriptionPlan) {
        if (purchaseBusy) return
        val token = container.userSession.token()
        if (token == null) {
            Toast.makeText(this, "请先登录后再购买", Toast.LENGTH_SHORT).show()
            showLoginDialog(onSuccess = { showPage(Page.SUBSCRIPTION) })
            return
        }
        SubscriptionViews(this).showConfirmation(plan) { createOrderAndPay(plan, token) }
    }

    private fun createOrderAndPay(plan: SubscriptionPlan, token: String, retryPurchase: PendingPurchase? = null) {
        if (purchaseBusy) return
        val userId = container.userSession.user()?.id ?: return
        // A newly selected plan always starts a new order. Only an explicit
        // retry of a request with no response reuses its idempotency key.
        val pending = retryPurchase?.takeIf { it.userId == userId && it.plan.id == plan.id }
            ?: PendingPurchase(userId, UUID.randomUUID().toString(), plan)
        orderPollJob?.cancel()
        container.pendingPurchase.save(pending)
        purchaseBusy = true
        lifecycleScope.launch {
            try {
                subscriptionClient.createOrder(token, pending.plan.id, pending.requestId)
                    .onSuccess { order ->
                        if (order.free || order.status == "paid") {
                            container.pendingPurchase.clear()
                            Toast.makeText(this@MainActivity, "「${pending.plan.name}」已开通", Toast.LENGTH_LONG).show()
                            showPage(Page.SUBSCRIPTION)
                        } else if (order.status in setOf("expired", "closed")) {
                            container.pendingPurchase.clear()
                            Toast.makeText(this@MainActivity, "订单已关闭，请重新购买", Toast.LENGTH_LONG).show()
                            showPage(Page.SUBSCRIPTION)
                        } else {
                            val current = pending.copy(orderId = order.orderId, payUrl = order.payUrl.orEmpty())
                            container.pendingPurchase.save(current)
                            showPage(Page.SUBSCRIPTION)
                            openPayment(current.payUrl)
                        }
                    }.onFailure { error ->
                        // A definitive client error did not create an order.
                        // Network/server failures retain the idempotency key for retry.
                        if (error is SubscriptionApiException && error.statusCode in 400..499) container.pendingPurchase.clear()
                        Toast.makeText(this@MainActivity, error.message ?: "下单失败，请重试", Toast.LENGTH_LONG).show()
                        showPage(Page.SUBSCRIPTION)
                    }
            } finally { purchaseBusy = false }
        }
    }

    private fun openPayment(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host !in setOf("openapi.alipay.com", "openapi.alipaydev.com")) {
            Toast.makeText(this, "支付地址无效，请查询订单或联系管理员", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, "无法打开支付页面，可以稍后继续付款", Toast.LENGTH_LONG).show() }
    }

    private fun resumePurchase(pending: PendingPurchase) {
        val token = container.userSession.token() ?: return
        if (pending.orderId.isBlank()) {
            createOrderAndPay(pending.plan, token, pending)
            return
        }
        if (purchaseBusy) return
        purchaseBusy = true
        orderPollJob?.cancel()
        lifecycleScope.launch {
            try {
                subscriptionClient.order(token, pending.orderId)
                    .onSuccess { order ->
                        if (!handlePaymentStatus(order.status)) {
                            order.payUrl?.let { openPayment(it) }
                                ?: Toast.makeText(this@MainActivity, "订单已超时，请查询付款结果", Toast.LENGTH_LONG).show()
                        }
                    }.onFailure { error ->
                        paymentStatusText?.text = error.message ?: "订单查询失败，请重试"
                        Toast.makeText(this@MainActivity, error.message ?: "订单查询失败，请重试", Toast.LENGTH_LONG).show()
                    }
            } finally { purchaseBusy = false }
        }
    }

    private fun handlePaymentStatus(status: String): Boolean {
        if (status !in setOf("paid", "expired", "closed")) return false
        orderPollJob?.cancel()
        container.pendingPurchase.clear()
        Toast.makeText(this, when (status) {
            "paid" -> "支付成功，套餐已开通"
            "closed" -> "订单已关闭，可重新购买"
            else -> "订单已超时，可重新购买；若已付款，请联系管理员核实"
        }, Toast.LENGTH_LONG).show()
        showPage(Page.SUBSCRIPTION)
        return true
    }

    private fun startOrderPolling(orderId: String, token: String) {
        orderPollJob?.cancel()
        orderPollJob = lifecycleScope.launch {
            repeat(80) { attempt ->
                if (attempt > 0) delay(3000)
                val current = pendingPurchase()
                if (current?.orderId != orderId) return@launch
                val result = subscriptionClient.order(token, orderId)
                val order = result.getOrNull()
                if (order != null) {
                    if (handlePaymentStatus(order.status)) return@launch
                    paymentStatusText?.text = "尚未确认付款。已付款请稍候，也可点击「查询结果」。"
                } else {
                    val error = result.exceptionOrNull()
                    paymentStatusText?.text = error?.message ?: "网络异常，请稍后查询"
                    if (error is SubscriptionApiException && error.statusCode == 404) {
                        container.pendingPurchase.clear()
                        Toast.makeText(this@MainActivity, "订单不存在，请重新购买", Toast.LENGTH_LONG).show()
                        showPage(Page.SUBSCRIPTION)
                        return@launch
                    }
                    if (error is SubscriptionApiException && error.statusCode == 401) {
                        Toast.makeText(this@MainActivity, "登录已过期，请重新登录后查询订单", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
            }
            paymentStatusText?.text = "暂未确认到账。可以继续付款或手动查询结果。"
        }
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
        addView(subpageHeader("键盘试用") { showPage(Page.ACCOUNT) })
        addView(section("体验输入"))
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        fun keyboardReady(): Boolean {
            val enabledHere = inputMethodManager.enabledInputMethodList.any { it.packageName == packageName }
            if (!enabledHere) {
                showKeyboardSetupDialog(notEnabled = true)
                return false
            }
            val selected = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ).orEmpty().startsWith(packageName)
            if (!selected) {
                showKeyboardSetupDialog(notEnabled = false)
                return false
            }
            return true
        }
        val input = EditText(this@MainActivity).apply {
            hint = "在这里试试拼音、英文、语音和润色"
            setTextColor(getColor(R.color.weike_text))
            setHintTextColor(getColor(R.color.weike_muted))
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            minLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = roundedBackground(getColor(R.color.weike_key), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            // Tap is gated: an un-enabled/unselected IME must not pop a bare
            // system keyboard (or the system picker) before the guided dialog.
            // Touch-level interception also covers taps on an already-focused
            // field, where EditText never fires onClick.
            showSoftInputOnFocus = false
            setOnClickListener {
                if (keyboardReady()) {
                    requestFocus()
                    setSelection(text.length)
                    inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    requestFocus()
                }
            }
            setOnTouchListener { view, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP && !keyboardReady()) {
                    view.performClick()
                    return@setOnTouchListener true
                }
                false
            }
        }
        addView(card().apply {
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
            addView(primaryButton("试用键盘") {
                if (!keyboardReady()) return@primaryButton
                input.requestFocus()
                input.setSelection(input.text.length)
                input.post {
                    inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), top = 12))
            addView(formActionButton("清空文本", false) { input.text.clear() }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), top = 10))
        })
    }

    /** Guided setup dialog for the trial page: distinguishes "not enabled in
     *  system" from "enabled but not currently selected". Dismisses itself in
     *  onResume once the user has enabled/selected the IME and come back. */
    private var keyboardSetupDialog: android.app.Dialog? = null

    private fun showKeyboardSetupDialog(notEnabled: Boolean) {
        keyboardSetupDialog?.dismiss()
        keyboardSetupDialog = themedDialog(
            if (notEnabled) "先启用恋爱键盘" else "切换到恋爱键盘",
            if (notEnabled) "体验 AI 键盘前，需要先在系统中启用"
            else "当前使用的是其他输入法，切换后即可体验"
        ) { body, dialog ->
            body.addView(TextView(this).apply {
                text = if (notEnabled)
                    "第 1 步：点击下方按钮，进入系统输入法设置\n第 2 步：打开「恋爱键盘」开关\n第 3 步：返回本页，点击「试用键盘」开始体验"
                else
                    "第 1 步：点击下方按钮，弹出输入法选择器\n第 2 步：选择「恋爱键盘」\n第 3 步：返回本页，点击「试用键盘」开始体验"
                textSize = 13f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_text))
            })
            body.addView(compactFormActionButton(if (notEnabled) "去启用" else "选择恋爱键盘", true) {
                dialog.dismiss()
                if (notEnabled) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                } else {
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), top = 14))
            body.addView(compactFormActionButton("稍后再说", false) { dialog.dismiss() },
                margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), top = 8))
        }.also { it.show() }
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
            val options = listOf(KeyboardLogoStyle.VERTICK, KeyboardLogoStyle.CUSTOM)
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
                textSize = 14f
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
        addView(illustratedOptionCard("九宫格符号", operationGuide("点击符号可编辑", "长按右侧拖动柄可调整符号顺序", "九宫格逗号键的弹出面板会按此顺序显示")) {
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
        addView(subpageHeader("优化输入") { showPage(Page.SETTINGS) })
        addView(section("离线词典增强"))
        addView(enhancedDictionaryCard())
        addView(wanxiangGrammarCard(), margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
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
            textSize = 12f
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 14)
        }
        addView(microphoneStatus)
    }

    private val modelViews by lazy { ModelConfigurationViews(this) }

    private fun modelConfigurationButton(label: String, filled: Boolean, action: () -> Unit) =
        modelViews.button(label, filled, action = action)

    private fun buildUserModelControls(host: LinearLayout) {
        val repository = container.settings.userModels
        val profiles = repository.all()
        host.addView(cloudSection("自定义模型"))

        // 双 Tab：多模态模型（文本+截图识别共用）与 ASR 模型（语音转文字）。
        val multimodalHost = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val asrHost = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        host.addView(SegmentedControl(this@MainActivity, listOf("多模态模型", "ASR 模型"), 0) { index ->
            multimodalHost.visibility = if (index == 0) View.VISIBLE else View.GONE
            asrHost.visibility = if (index == 1) View.VISIBLE else View.GONE
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
        host.addView(multimodalHost)
        host.addView(asrHost)

        // Tab 1：多模态模型——单模型自动服务帮你回/截图识别/超会说。
        multimodalHost.addView(card().apply {
            if (profiles.isEmpty()) {
                // 空态直接内联表单：免去「点按钮 → 弹窗」的中转，填完即保存。
                addView(cloudHelpText("填写服务商与接口参数。保存后自动用于帮你回、截图识别回复和超会说。"))
                val inlineFooter = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                addView(providerConfigurationCard(
                    isAsr = false,
                    load = { ModelEndpointConfig() },
                    loadProvider = { CloudProvider.CUSTOM },
                    save = { config, provider ->
                        // 名称自动按「厂商 · 模型」生成，少填一项。
                        val finalName = "${provider.displayName} · ${config.model.ifBlank { "自定义模型" }}"
                        val profile = repository.save(null, finalName, config.copy(provider = provider))
                        // 单模型语义：自动绑定全部业务，无需用户逐个选择。
                        com.weike.ime.data.UserModelSlot.entries.forEach { slot ->
                            repository.select(slot, profile.id)
                        }
                    },
                    test = { config -> MimoTextPolisher(endpointProvider = { config }).testConnection() },
                    actionsHost = inlineFooter
                ).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                })
                addView(inlineFooter)
            } else {
                addView(cloudHelpText("此模型同时用于帮你回、截图识别回复和超会说。点击可修改参数或删除。"))
                profiles.forEach { profile ->
                    addView(actionRow(profile.name, "${profile.endpoint.provider.displayName} · ${profile.endpoint.model}") { showUserModelEditor(profile) })
                }
            }
        })

        // Tab 2：ASR 模型——语音输入（录音转文字）专用，固定小米 MiMo。
        val asrKeyInput = modelViews.field("输入小米 MiMo API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        }
        lifecycleScope.launch {
            val asr = container.settings.cloudApiSettings.first().asr
            if (asr.provider == CloudProvider.XIAOMI_MIMO && asr.apiKey.isNotBlank()) {
                asrKeyInput.setText(asr.apiKey)
                asrKeyInput.setSelection(asr.apiKey.length)
            }
        }
        asrHost.addView(card().apply {
            addView(cloudHelpText("语音输入（录音转文字）使用小米 MiMo 语音识别。前往小米 MiMo 开放平台创建 API Key 后填入即可。"))
            addView(asrKeyInput, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
            addView(modelConfigurationButton("保存", true) {
                val key = asrKeyInput.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this@MainActivity, "请填写 API Key", Toast.LENGTH_SHORT).show()
                    return@modelConfigurationButton
                }
                lifecycleScope.launch {
                    container.settings.saveAsrApi(
                        ModelEndpointConfig(
                            url = "https://api.xiaomimimo.com/v1",
                            apiKey = key,
                            model = "mimo-v2.5-asr",
                            provider = CloudProvider.XIAOMI_MIMO
                        ),
                        CloudProvider.XIAOMI_MIMO
                    )
                    Toast.makeText(this@MainActivity, "ASR 模型已保存，语音输入已就绪", Toast.LENGTH_SHORT).show()
                }
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 12))
        })
    }

    private fun showUserModelEditor(profile: com.weike.ime.data.UserModelProfile?) {
        val name = modelViews.field("如：我的 DeepSeek").apply { setText(profile?.name.orEmpty()) }
        lateinit var dialog: android.app.Dialog
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val contents = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modelViews.label("配置名称"))
            addView(name)
            addView(providerConfigurationCard(isAsr = false,
                load = { profile?.endpoint ?: ModelEndpointConfig() },
                loadProvider = { profile?.endpoint?.provider ?: CloudProvider.CUSTOM },
                save = { config, provider ->
                    container.settings.userModels.save(profile?.id, name.text.toString(), config.copy(provider = provider))
                    // 第一个模型保存后自动绑定全部业务，避免「配置了却不生效」的困惑。
                    if (profile == null && container.settings.userModels.all().size == 1) {
                        val saved = container.settings.userModels.all().first()
                        com.weike.ime.data.UserModelSlot.entries.forEach { slot ->
                            container.settings.userModels.select(slot, saved.id)
                        }
                    }
                    dialog.dismiss(); showPage(Page.CLOUD)
                },
                test = { config -> MimoTextPolisher(endpointProvider = { config }).testConnection() },
                actionsHost = footer).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                })
            if (profile != null) footer.addView(modelViews.button("删除模型", destructive = true) {
                lateinit var confirmation: android.app.Dialog
                val actions = LinearLayout(this@MainActivity).apply {
                    addView(modelViews.button("取消") { confirmation.dismiss() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
                    addView(modelViews.button("确认删除", filled = true, destructive = true) {
                        container.settings.userModels.remove(profile.id)
                        confirmation.dismiss()
                        dialog.dismiss()
                        showPage(Page.CLOUD)
                        Toast.makeText(this@MainActivity, "模型已删除", Toast.LENGTH_SHORT).show()
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                confirmation = modelViews.sheet("删除模型？", "确定删除「${profile.name}」吗？删除后，关联业务将恢复为未配置，可重新添加并选择。", View(this@MainActivity), actions)
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 12))
        }
        dialog = modelViews.sheet(if (profile == null) "添加大模型" else "编辑大模型", "填写模型参数，保存后可为各项 AI 功能单独选择。", contents, footer)
    }

    private fun buildCloudConfiguration(): View = screen {
        addView(cloudSubpageHeader("大模型配置") { showPage(cloudReturnPage) })
        addView(cloudSection("AI 大模型套餐"))
        val modelsHost = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val customChevron = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_down)
            setColorFilter(getColor(R.color.weike_muted))
        }

        fun applyCustomExpanded(expanded: Boolean) {
            customChevron.animate().rotation(if (expanded) 180f else 0f).setDuration(150).start()
            modelsHost.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        addView(card(top = 12).apply {
            addView(cloudHelpText("开通 VIP 后使用后台为各项功能配置的官方模型，无需填写接口参数。"))
            addView(modelConfigurationButton("查看订阅套餐", true) { showPage(Page.SUBSCRIPTION) },
                margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 12))
            // 折叠入口：不想订阅的用户展开自定义模型配置。
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8)
                setPadding(0, dp(12), 0, dp(2))
                addView(TextView(this@MainActivity).apply {
                    text = "不想订阅？使用自定义模型"
                    textSize = 13f
                    setTextColor(pagePrimary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(customChevron, LinearLayout.LayoutParams(dp(20), dp(20)))
                setOnClickListener {
                    val expanded = modelsHost.visibility == View.VISIBLE
                    applyCustomExpanded(!expanded)
                }
            })
        })
        addView(modelsHost)
        lifecycleScope.launch {
            container.settings.cloudApiSettings.first() // 迁移旧版自配模型，保留原参数和密钥。
            buildUserModelControls(modelsHost)
            // 配置过自定义模型的用户进入页面自动展开。
            if (container.settings.userModels.all().isNotEmpty()) applyCustomExpanded(true)
        }
        addView(cloudSection("帮你回提示词"))
        addView(card().apply {
            addView(cloudHelpText("定义 AI 以谁的角度回复、回复什么内容。该提示词会作为系统提示词发送给文本模型。"))
            val promptInput = promptTextField("输入帮你回的系统提示词", minLines = 4, maxLines = 6, top = 12)
            addView(promptInput)
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(modelConfigurationButton("恢复默认", false) {
                    promptInput.setText(AppSettingsRepository.DEFAULT_REPLY_PROMPT)
                })
                addView(modelConfigurationButton("保存提示词", true) {
                    lifecycleScope.launch {
                        runCatching { container.settings.saveReplyPrompt(promptInput.text.toString()) }
                            .onSuccess {
                                promptInput.setText(container.settings.replyPrompt())
                                Toast.makeText(this@MainActivity, "帮你回提示词已保存", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(this@MainActivity, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
                    }
                })
                for (index in 0 until childCount) {
                    getChildAt(index).layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (index == 0) marginEnd = dp(10)
                    }
                }
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 12)
            }
            addView(actions)
            lifecycleScope.launch { promptInput.setText(container.settings.replyPrompt()) }
        })
        addView(cloudSection("超会说关系提示词"))
        addView(card().apply {
            addView(cloudHelpText("为每一种聊天对象配置独立的系统提示词。键盘选择关系后，AI会自动使用对应配置。"))
            val relations = AppSettingsRepository.RELATION_TYPES
            var prompts = AppSettingsRepository.DEFAULT_RELATION_PROMPTS
            var selectedRelation = relations.first()
            val relationPromptInput = promptTextField("输入该关系的系统提示词", minLines = 3, maxLines = 6, top = 10)
            lateinit var relationSelector: Button
            relationSelector = modelConfigurationButton(selectedRelation, false) {
                modelViews.choose("选择聊天关系", "为不同聊天对象配置独立的提示词。", relations, relations.indexOf(selectedRelation)) { index ->
                    selectedRelation = relations[index]
                    relationSelector.text = selectedRelation
                    relationPromptInput.setText(prompts[selectedRelation] ?: AppSettingsRepository.DEFAULT_RELATION_PROMPTS.getValue(selectedRelation))
                }
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.weike_text))
                val chevron = getDrawable(R.drawable.ic_lucide_chevron_down)?.mutate()?.apply {
                    setTint(getColor(R.color.weike_muted))
                    setBounds(0, 0, dp(20), dp(20))
                }
                setCompoundDrawablesRelative(null, null, chevron, null)
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 10)
            }
            addView(relationSelector)
            addView(relationPromptInput)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(modelConfigurationButton("恢复默认", false) {
                    relationPromptInput.setText(AppSettingsRepository.DEFAULT_RELATION_PROMPTS.getValue(selectedRelation))
                })
                addView(modelConfigurationButton("保存当前关系", true) {
                    lifecycleScope.launch {
                        runCatching { container.settings.saveRelationPrompt(selectedRelation, relationPromptInput.text.toString()) }
                            .onSuccess {
                                prompts = container.settings.relationPrompts()
                                relationPromptInput.setText(prompts.getValue(selectedRelation))
                                Toast.makeText(this@MainActivity, "$selectedRelation 提示词已保存", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(this@MainActivity, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
                    }
                })
                for (index in 0 until childCount) {
                    getChildAt(index).layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (index == 0) marginEnd = dp(10)
                    }
                }
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 12)
            })
            lifecycleScope.launch {
                prompts = container.settings.relationPrompts()
                relationPromptInput.setText(prompts.getValue(selectedRelation))
            }
        })
    }

    private fun buildSettingsHub(): View = screen {
        addView(settingsHubHeader("设置") { showPage(Page.PROFILE) })
        addView(settingsHubSection("键盘设置"))
        addView(managementTileRow(
            managementTile("布局和显示", "主题与候选词", R.drawable.ic_lucide_layout_dashboard) { showPage(Page.LAYOUT) },
            managementTile("按键效果", "声音与触感", R.drawable.ic_lucide_zap) { showPage(Page.KEY_EFFECTS) }
        ))
        addView(managementTileRow(
            managementTile("辅助输入", "标点与英文", R.drawable.ic_lucide_sliders_horizontal) { showPage(Page.AUXILIARY_INPUT) },
            managementTile("键盘管理", "布局与模式", R.drawable.ic_lucide_keyboard) { showPage(Page.KEYBOARD_MANAGEMENT) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), top = 10))
        addView(settingsHubSection("AI 与数据"))
        addView(managementTileRow(
            managementTile("大模型配置", "套餐或自定义接口", R.drawable.ic_lucide_cloud) {
                cloudReturnPage = Page.SETTINGS
                showPage(Page.CLOUD)
            },
            managementTile("优化输入", "词典与文风", R.drawable.ic_lucide_wand_sparkles) { showPage(Page.OPTIMIZE_INPUT) }
        ))
        addView(managementTileRow(
            managementTile("剪贴板", "本机历史", R.drawable.ic_lucide_clipboard_list) { showPage(Page.CLIPBOARD_SETTINGS) },
            managementTile("本机数据", "历史与学习", R.drawable.ic_lucide_history) { showPage(Page.LOCAL_DATA) }
        ), margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), top = 10))
        addView(settingsHubSection("其他"))
        addView(managementTileRow(
            managementTile("权限管理", "输入法、录音与悬浮窗", R.drawable.ic_lucide_shield_check) { showPage(Page.PERMISSION_MANAGEMENT) },
            managementTile("关于与帮助", "开发者与使用指引", R.drawable.ic_lucide_circle_help) { showPage(Page.ABOUT) }
        ))
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
            item.background = if (active) roundedBackground(getColor(R.color.weike_key), 16) else null
        }
    }

    private fun homeFeatureCard(icon: Int, title: String, detail: String, color: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.weike_panel))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), getColor(R.color.weike_key))
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
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 12f
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
            addView(TextView(this@MainActivity).apply { text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.weike_text)) })
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
        addView(card().apply {
            addView(actionRow("版本信息", "当前版本 $appVersionName（${localVersionCode() % 1000}）") {
                showVersionManagementDialog()
            })
            addDivider(this)
            addView(actionRow("隐私政策", "数据如何采集与保存") { showPrivacyPolicyDialog() })
        })
        addView(section("开发者与交流"))
        addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "开发者：邝广元"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = "下面是我的微信，欢迎添加我为好友，一起沟通交流恋爱键盘的使用体验、建议和想法。"
                textSize = 12f
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
                textSize = 12f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = "微信收款码"
                textSize = 12f
                setTextColor(getColor(R.color.weike_muted))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8)
            })
            addView(aboutImage(R.drawable.donation_wechat_qr, "微信支付打赏二维码"), margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                top = 16
            ))
        })
        addView(View(this@MainActivity).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)) })
    }

    private fun showPrivacyPolicyDialog() {
        themedDialog("隐私政策", "恋爱键盘如何处理你的数据") { body, _ ->
            body.addView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = PRIVACY_POLICY_TEXT
                    textSize = 13f
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setTextColor(getColor(R.color.weike_text))
                })
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        }.show()
    }

    private fun aboutImage(drawable: Int, description: String) = ImageView(this).apply {
        setImageResource(drawable)
        contentDescription = description
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = roundedBackground(getColor(R.color.weike_panel), 16)
    }

    /** Update manifests carry internal build channels ("1.5.4-cdn"); users
     *  should only ever see the plain version number. */
    private fun displayVersionName(raw: String): String = raw.substringBefore('-').trim()

    private fun versionManagementHint(): String {
        val newer = pendingUpdate?.versionName
            ?: getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE).getString(KEY_PENDING_VERSION, null)
        return newer?.let { "新版本 ${displayVersionName(it)} 已发布，点击查看" } ?: "当前版本 $appVersionName"
    }

    private fun refreshVersionRowHint() {
        versionRowDetail?.text = versionManagementHint()
    }

    private fun localVersionCode(): Long = packageManager.getPackageInfo(packageName, 0).longVersionCode

    /** One silent check per day; the dialog's manual button always runs a fresh check. */
    private suspend fun fetchLatestUpdate(silent: Boolean): Result<UpdateClient.UpdateInfo?> {
        val preferences = getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE)
        if (silent &&
            System.currentTimeMillis() - preferences.getLong(KEY_LAST_CHECK_AT, 0L) < UPDATE_CHECK_INTERVAL_MS
        ) {
            refreshVersionRowHint()
            return Result.success(pendingUpdate)
        }
        val result = UpdateClient().checkLatest().map { info ->
            val update = info.takeIf { it.versionCode > localVersionCode() }
            pendingUpdate = update
            preferences.edit().apply {
                putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
                if (update == null) remove(KEY_PENDING_VERSION) else putString(KEY_PENDING_VERSION, update.versionName)
            }.apply()
            update
        }
        refreshVersionRowHint()
        return result
    }

    private fun showLoginDialog(onSuccess: (() -> Unit)? = null) {
        var registerMode = false
        var busy = false
        val radius = dp(22).toFloat()

        fun fieldBackground(focused: Boolean = false) = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), if (focused) pagePrimary else Color.rgb(240, 226, 231))
        }

        val phoneInput = EditText(this).apply {
            hint = "手机号"
            inputType = InputType.TYPE_CLASS_PHONE
            maxLines = 1
            textSize = 15f
            setTextColor(getColor(R.color.weike_text))
            setHintTextColor(getColor(R.color.weike_muted))
            background = fieldBackground()
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val passwordInput = EditText(this).apply {
            hint = "密码（8~64 位）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
            textSize = 15f
            setTextColor(getColor(R.color.weike_text))
            setHintTextColor(getColor(R.color.weike_muted))
            background = fieldBackground()
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(255, 71, 87))
            setPadding(dp(4), dp(10), dp(4), 0)
            visibility = View.GONE
        }
        val actionButton = compactFormActionButton("登录", true) { }
        val modeSwitch = TextView(this).apply {
            textSize = 13f
            setTextColor(pagePrimary)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(16), dp(4), dp(4))
        }

        val titleView = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        phoneInput.setOnFocusChangeListener { _, hasFocus -> phoneInput.background = fieldBackground(hasFocus) }
        passwordInput.setOnFocusChangeListener { _, hasFocus -> passwordInput.background = fieldBackground(hasFocus) }
        val subtitleView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.argb(225, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val closeButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_x)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "关闭"
        }
        val header = android.widget.FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 107, 157), Color.rgb(255, 160, 122))
            ).apply {
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(52), dp(18))
                addView(titleView)
                addView(subtitleView)
            }
            addView(
                textColumn,
                android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                closeButton,
                android.widget.FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(10)
                    marginEnd = dp(10)
                }
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(phoneInput, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
            addView(passwordInput, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statusView)
            addView(actionButton, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), top = 10))
            addView(modeSwitch, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(body)
        }

        val dialog = android.app.Dialog(this)
        fun applyMode() {
            titleView.text = if (registerMode) "注册账号" else "登录账号"
            subtitleView.text = if (registerMode) "注册成功后将自动登录，无需再次输入" else "购买订阅套餐前需要先登录"
            actionButton.text = if (registerMode) "注册并登录" else "登录"
            modeSwitch.text = if (registerMode) "已有账号？直接登录" else "还没有账号？免费注册"
        }
        actionButton.setOnClickListener {
            if (busy) return@setOnClickListener
            val phone = phoneInput.text.toString().trim()
            val password = passwordInput.text.toString()
            val localError = when {
                !Regex("^1\\d{10}$").matches(phone) -> "请输入正确的 11 位手机号"
                password.length < 8 -> "密码至少 8 位"
                else -> null
            }
            if (localError != null) {
                statusView.visibility = View.VISIBLE
                statusView.text = localError
                return@setOnClickListener
            }
            busy = true
            actionButton.isEnabled = false
            actionButton.text = if (registerMode) "注册中..." else "登录中..."
            statusView.visibility = View.GONE
            lifecycleScope.launch {
                val result = if (registerMode) userAuthClient.register(phone, password)
                else userAuthClient.login(phone, password)
                result.onSuccess { session ->
                    container.userSession.save(session.token, session.user)
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "已登录 ${session.user.maskedPhone}", Toast.LENGTH_SHORT).show()
                    (onSuccess ?: { showPage(Page.PROFILE) }).invoke()
                }.onFailure { error ->
                    busy = false
                    actionButton.isEnabled = true
                    actionButton.text = if (registerMode) "注册并登录" else "登录"
                    statusView.visibility = View.VISIBLE
                    statusView.text = error.message ?: "网络不可用，请稍后重试"
                }
            }
        }
        modeSwitch.setOnClickListener {
            registerMode = !registerMode
            statusView.visibility = View.GONE
            applyMode()
        }
        closeButton.setOnClickListener { dialog.dismiss() }

        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(android.widget.FrameLayout(this).apply {
            setPadding(dp(26), 0, dp(26), 0)
            addView(card, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }
        applyMode()
        dialog.show()
    }

    /** Feedback entry: the WeChat QR code is the live channel; scanning it
     *  reaches the developer directly. */
    private fun showFeedbackDialog() {
        val dialog = android.app.Dialog(this)
        val radius = dp(22).toFloat()
        fun headerDrawable() = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(255, 107, 157), Color.rgb(255, 160, 122))
        ).apply { cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) }

        val titleView = TextView(this).apply {
            text = "意见反馈"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val subtitleView = TextView(this).apply {
            text = "扫码添加微信，直接反馈问题和建议"
            textSize = 12f
            setTextColor(Color.argb(225, 255, 255, 255))
        }
        val closeButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_x)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "关闭"
        }
        val header = android.widget.FrameLayout(this).apply {
            background = headerDrawable()
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(subtitleView)
            })
            addView(
                closeButton,
                android.widget.FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(10)
                    marginEnd = dp(10)
                }
            )
        }
        val qr = ImageView(this).apply {
            setImageResource(R.drawable.developer_wechat_qr)
            contentDescription = "开发者微信二维码"
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(qr, LinearLayout.LayoutParams(dp(210), dp(210)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(TextView(this@MainActivity).apply {
                text = "扫一扫上方二维码，添加开发者微信\n使用体验、问题和建议都欢迎"
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(getColor(R.color.weike_muted))
                setPadding(0, dp(12), 0, dp(4))
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(body)
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(android.widget.FrameLayout(this).apply {
            setPadding(dp(26), 0, dp(26), 0)
            addView(card, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** Branded dialog shell (gradient header + white body) shared by
     *  login/feedback/update so system AlertDialogs stop breaking the theme. */
    private fun themedDialog(title: String, subtitle: String? = null, buildBody: (LinearLayout, android.app.Dialog) -> Unit): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        val radius = dp(22).toFloat()
        val header = android.widget.FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 107, 157), Color.rgb(255, 160, 122))
            ).apply { cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                subtitle?.let {
                    addView(TextView(this@MainActivity).apply {
                        text = it
                        textSize = 12f
                        setTextColor(Color.argb(225, 255, 255, 255))
                    })
                }
            })
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_x)
                setColorFilter(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "关闭"
            }, android.widget.FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = dp(10)
            })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        header.getChildAt(1).setOnClickListener { dialog.dismiss() }
        buildBody(body, dialog)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(body)
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(android.widget.FrameLayout(this).apply {
            setPadding(dp(26), 0, dp(26), 0)
            addView(card, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun confirmLogout() {
        themedDialog("退出登录？", "本机登录状态将被清除") { body, dialog ->
            body.addView(TextView(this).apply {
                text = "订阅权益不受影响，下次购买前重新登录即可。"
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_text))
            })
            body.addView(compactFormActionButton("退出登录", true) {
                dialog.dismiss()
                performLogout()
            }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), top = 14))
            body.addView(compactFormActionButton("取消", false) { dialog.dismiss() },
                margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), top = 8))
        }.show()
    }

    private fun performLogout() {
        val token = container.userSession.token() ?: return
        lifecycleScope.launch {
            runCatching { userAuthClient.logout(token) }
            orderPollJob?.cancel()
            container.pendingPurchase.clear()
            container.userSession.clear()
            Toast.makeText(this@MainActivity, "已退出登录", Toast.LENGTH_SHORT).show()
            showPage(Page.PROFILE)
        }
    }

    private fun showVersionManagementDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val status = TextView(this).apply {
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(getColor(R.color.weike_text))
        }
        val notes = TextView(this).apply {
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(getColor(R.color.weike_muted))
            visibility = View.GONE
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10_000
            progress = 0
            visibility = View.GONE
        }
        val primary = compactFormActionButton("检查更新", true) { }
        var downloadedFile: File? = null

        fun renderUpdate(update: UpdateClient.UpdateInfo) {
            status.text = "当前版本：$appVersionName\n发现新版本：${displayVersionName(update.versionName)}"
            val userNotes = update.notes.lineSequence()
                .filterNot { it.contains("CDN", true) || it.contains("分发") || it.contains("验证版本") }
                .joinToString("\n").trim()
            if (userNotes.isNotBlank()) {
                notes.text = userNotes.take(600)
                notes.visibility = View.VISIBLE
            }
        }

        fun installApk(file: File) {
            if (!packageManager.canRequestPackageInstalls()) {
                status.text = "当前版本：$appVersionName\n下载完成。请先允许本应用安装未知应用，授权后回来再次点击按钮。"
                primary.isEnabled = true
                primary.text = "去授权"
                primary.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                }
                return
            }
            status.text = "当前版本：$appVersionName\n下载完成，请在系统安装界面确认更新。"
            runCatching {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }.onFailure { Toast.makeText(this@MainActivity, "无法启动系统安装器", Toast.LENGTH_SHORT).show() }
        }

        fun bindDownload(update: UpdateClient.UpdateInfo) {
            primary.isEnabled = true
            primary.text = "下载并安装"
            primary.setOnClickListener {
                val existing = downloadedFile
                if (existing?.isFile == true) {
                    installApk(existing)
                    return@setOnClickListener
                }
                primary.isEnabled = false
                primary.text = "下载中..."
                progress.visibility = View.VISIBLE
                progress.progress = 0
                status.text = "当前版本：$appVersionName\n正在下载 ${update.versionName}..."
                val target = File(getExternalFilesDir("updates"), "lianai-${update.versionName}.apk")
                lifecycleScope.launch {
                    UpdateClient().downloadApk(update, target) { p ->
                        runOnUiThread {
                            progress.progress = (p.fraction * 10_000).roundToInt()
                            status.text = "当前版本：$appVersionName\n正在下载 ${(p.fraction * 100).roundToInt()}%"
                        }
                    }.onSuccess { file ->
                        downloadedFile = file
                        progress.visibility = View.GONE
                        installApk(file)
                    }.onFailure { error ->
                        progress.visibility = View.GONE
                        status.text = "当前版本：$appVersionName\n\n下载失败：${error.message ?: "网络不可用"}"
                        bindDownload(update)
                    }
                }
            }
        }

        fun bindCheck() {
            primary.isEnabled = true
            primary.text = "检查更新"
            primary.setOnClickListener {
                primary.isEnabled = false
                primary.text = "检查中..."
                status.text = "当前版本：$appVersionName\n\n正在检查更新..."
                lifecycleScope.launch {
                    fetchLatestUpdate(silent = false)
                        .onSuccess { update ->
                            if (update == null) {
                                notes.visibility = View.GONE
                                status.text = "当前版本：$appVersionName\n\n已是最新版本。"
                                bindCheck()
                            } else {
                                downloadedFile = null
                                progress.visibility = View.GONE
                                renderUpdate(update)
                                bindDownload(update)
                            }
                        }
                        .onFailure { error ->
                            notes.visibility = View.GONE
                            status.text = "当前版本：$appVersionName\n\n检查失败：${error.message ?: "网络不可用"}"
                            bindCheck()
                        }
                }
            }
        }

        val known = pendingUpdate
        if (known == null) {
            status.text = "当前版本：$appVersionName\n\n点击“检查更新”获取最新版本信息。"
            bindCheck()
        } else {
            renderUpdate(known)
            bindDownload(known)
        }
        content.addView(status, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 6, bottom = 10))
        content.addView(notes, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
        content.addView(progress, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(8), bottom = 12))
        content.addView(primary, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        // Themed shell matching the login dialog instead of the system
        // AlertDialog, whose default styling clashed with the pink theme.
        val dialog = android.app.Dialog(this)
        val radius = dp(22).toFloat()
        val header = android.widget.FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 107, 157), Color.rgb(255, 160, 122))
            ).apply { cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(TextView(this@MainActivity).apply {
                text = "软件更新"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_x)
                setColorFilter(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "关闭"
            }, android.widget.FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = dp(10)
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
                }
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(content)
            })
        }
        header.getChildAt(1).setOnClickListener { dialog.dismiss() }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(android.widget.FrameLayout(this).apply {
            setPadding(dp(26), 0, dp(26), 0)
            addView(card, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun buildUsageGuide(): View = screen {
        addView(subpageHeader("使用指引") { showPage(Page.PROFILE) })
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
                "在“设置 → 标点与英文”开启句号逗号快捷键后，空格左侧的按键点击输入逗号，上滑或长按输入句号；九宫格的「，。？!」键展开标点条，选择其他标点时自动替换预插的逗号。"
            ))
            addDivider(this)
            addView(usageGuideItem(
                R.drawable.ic_lucide_keyboard,
                "颜文字与符号",
                "九宫格右侧的“^_^”键弹出颜文字条，点选即上屏；符号键盘共三页，第三页收录【】《》等中文符号，翻页键循环切换。"
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
        test: suspend (ModelEndpointConfig) -> Result<Unit>,
        actionsHost: LinearLayout? = null
    ) = card().apply {
        val url = modelViews.field("https://api.example.com/v1")
        val apiKey = modelViews.field("输入 API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = modelViews.field("输入或读取模型名称")
        val thinkingSwitch = android.widget.Switch(this@MainActivity).apply {
            contentDescription = "思考模式"
            buttonTintList = android.content.res.ColorStateList.valueOf(pagePrimary)
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pagePrimary, getColor(R.color.weike_muted)))
            trackTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.weike_key))
        }
        val thinkingHint = cloudHelpText("")
        val thinkingControls = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "思考模式"
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(getColor(R.color.weike_text))
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(thinkingSwitch, LinearLayout.LayoutParams(dp(54), dp(48)))
            })
            addView(thinkingHint)
            visibility = if (isAsr) View.GONE else View.VISIBLE
        }
        val urlLabel = modelViews.label("接口地址")
        var selectedProvider = CloudProvider.CUSTOM
        var previousCustomUrl = ""
        val officialEndpoint = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(getColor(R.color.weike_muted))
            setPadding(dp(2), 0, dp(2), dp(8))
            visibility = View.GONE
        }
        val protocolHint = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(getColor(R.color.weike_muted))
            setPadding(dp(2), 0, dp(2), dp(8))
            visibility = if (isAsr) View.VISIBLE else View.GONE
        }
        lateinit var readModelsButton: Button

        fun currentConfig() = ModelEndpointConfig(
            url = url.text.toString(),
            apiKey = apiKey.text.toString(),
            model = model.text.toString(),
            provider = selectedProvider,
            thinkingEnabled = thinkingSwitch.isChecked
        )

        fun refreshThinkingControl() {
            val control = ModelThinking.control(currentConfig())
            thinkingSwitch.isEnabled = control == ModelThinking.Control.THINKING
            if (control != ModelThinking.Control.THINKING) thinkingSwitch.isChecked = control == ModelThinking.Control.REQUIRED
            thinkingHint.text = when (control) {
                ModelThinking.Control.THINKING -> "默认关闭，直接生成回复；开启后可能增加等待时间和用量。"
                ModelThinking.Control.REQUIRED -> "此模型固定开启思考，服务商不支持关闭。"
                ModelThinking.Control.UNSUPPORTED -> "此模型暂未适配思考开关，使用服务商默认行为。"
            }
        }

        fun applyProvider(provider: CloudProvider, replaceWithPreset: Boolean) {
            selectedProvider = provider
            val preset = cloudProviderPreset(provider, isAsr)
            val fixedEndpoint = provider != CloudProvider.CUSTOM && !provider.endpointRequiresUserValue
            url.visibility = if (fixedEndpoint) View.GONE else View.VISIBLE
            urlLabel.visibility = url.visibility
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
            refreshThinkingControl()
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
            // 文本场景收敛到主流五家 + 自定义模型（可选回，作为默认项）。
            listOf(
                CloudProvider.CUSTOM,
                CloudProvider.ZHIPU,
                CloudProvider.QWEN,
                CloudProvider.DEEPSEEK,
                CloudProvider.DOUBAO,
                CloudProvider.HUNYUAN
            )
        }
        val picker = CloudProviderDropdown(this@MainActivity, selectedProvider, availableProviders) { provider ->
            applyProvider(provider, replaceWithPreset = true)
        }

        readModelsButton = modelConfigurationButton("读取模型", false) {
            val config = currentConfig()
            val validationError = cloudEndpointKeyValidationError(config)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@modelConfigurationButton
            }
            readModelsButton.isEnabled = false
            readModelsButton.text = "读取中"
            lifecycleScope.launch {
                val result = ModelCatalog().list(config)
                readModelsButton.isEnabled = true
                readModelsButton.text = "读取模型"
                result.onSuccess { models ->
                    if (models.isEmpty()) {
                        Toast.makeText(this@MainActivity, "该接口暂未返回可选模型，可手动填写模型名", Toast.LENGTH_LONG).show()
                    } else {
                        modelViews.choose("选择模型", "以下模型来自当前服务商，点击即可填入。", models, models.indexOf(model.text.toString())) { index ->
                            model.setText(models[index])
                        }
                    }
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.message ?: "读取模型失败", Toast.LENGTH_LONG).show()
                }
            }
        }
        lateinit var testButton: Button
        testButton = modelConfigurationButton("测试连接", false) {
            val config = currentConfig()
            val validationError = cloudConfigValidationError(config, isAsr)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@modelConfigurationButton
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
        lateinit var saveButton: Button
        saveButton = modelConfigurationButton("保存配置", true) {
            val config = currentConfig()
            val validationError = cloudConfigValidationError(config, isAsr)
            if (validationError != null) {
                Toast.makeText(this@MainActivity, validationError, Toast.LENGTH_SHORT).show()
                return@modelConfigurationButton
            }
            saveButton.isEnabled = false
            saveButton.text = "保存中…"
            lifecycleScope.launch {
                try {
                    save(config, selectedProvider)
                    Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Toast.makeText(this@MainActivity, error.message ?: "保存失败，请重试", Toast.LENGTH_LONG).show()
                } finally {
                    saveButton.isEnabled = true
                    saveButton.text = "保存配置"
                }
            }
        }
        addView(modelViews.label("模型服务商"))
        addView(picker, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), bottom = 10))
        addView(officialEndpoint)
        addView(protocolHint)
        addView(urlLabel)
        addView(url)
        addView(modelViews.label("API Key"))
        addView(apiKey)
        addView(modelViews.label("模型名称"))
        // Keep the upstream inline catalog action and the shared editor's fixed footer.
        addView(FrameLayout(this@MainActivity).apply {
            model.setPadding(dp(14), 0, dp(106), 0)
            addView(model, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(readModelsButton, FrameLayout.LayoutParams(dp(88), dp(36), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(6)
            })
        }, margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), bottom = 8))
        addView(thinkingControls, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 14))
        (actionsHost ?: this).addView(LinearLayout(this@MainActivity).apply {
            addView(testButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        val thinkingWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshThinkingControl() }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        model.addTextChangedListener(thinkingWatcher)
        url.addTextChangedListener(thinkingWatcher)
        lifecycleScope.launch {
            val provider = loadProvider()
            load().also { config ->
                val resolvedProvider = inferCloudProvider(provider, config.url)
                picker.setSelected(resolvedProvider)
                applyProvider(resolvedProvider, replaceWithPreset = false)
                url.setText(config.url)
                apiKey.setText(config.apiKey)
                model.setText(config.model)
                thinkingSwitch.isChecked = config.thinkingEnabled
                refreshThinkingControl()
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
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(52), dp(64)))
    }

    private fun settingsHubHeader(title: String, back: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_left)
            setColorFilter(getColor(R.color.weike_text))
            setPadding(dp(13), dp(13), dp(13), dp(13))
            contentDescription = "返回"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(48), dp(58)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(48), dp(58)))
    }

    private fun cloudSubpageHeader(title: String, back: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_left)
            setColorFilter(getColor(R.color.weike_text))
            setPadding(dp(13), dp(13), dp(13), dp(13))
            contentDescription = "返回"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(48), dp(58)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(48), dp(58)))
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

    private fun showAddDictionaryDialog(defaultType: Int = 0) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val type = settingsSpinner(listOf("专业词库", "打字词典"))
        type.setSelection(defaultType)
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
        if (entries.isEmpty()) host.addView(illustratedOptionCard("尚未添加自定义专业词", operationGuide("用于人名、公司名和行业术语，辅助拼音候选及 AI 润色", "这里只显示你添加的词条，内置离线词典不在此列出")) { addView(primaryButton("添加专业词") { showAddDictionaryDialog() }) })
        entries.forEach { entry -> host.addView(itemCard("${entry.term}${if (entry.hint.isBlank()) "" else " · ${entry.hint}"}") {
            lifecycleScope.launch { container.lexicon.delete(entry.term) }
        }) }
    }

    private fun renderTypingDictionary(entries: List<TypingDictionaryEntry>) {
        val host = typingDictionaryList ?: return
        host.removeAllViews()
        if (entries.isEmpty()) host.addView(illustratedOptionCard("尚未添加自定义打字词", operationGuide("用于常用词、短语及英文词，辅助输入候选", "保存自己的常用表达，无需下载基础词典")) { addView(primaryButton("添加打字词") { showAddDictionaryDialog(1) }) })
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
                    textSize = 14f
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
        symbols.forEachIndexed { index, symbol ->
            // Compact 48dp row instead of a full card per symbol; drag index
            // math below walks host children, so rows stay direct children.
            host.addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(getColor(R.color.weike_panel), 14)
                setPadding(dp(12), 0, dp(6), 0)
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = if (index == 0) 0 else 4)
                addView(TextView(this@MainActivity).apply {
                    text = symbol
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(getColor(R.color.weike_text))
                    setOnClickListener { showNineKeySymbolEditor(symbol) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_lucide_grip_vertical)
                    setColorFilter(getColor(R.color.weike_muted))
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnLongClickListener {
                        dragSource = this
                        animate().alpha(0.55f).scaleX(0.92f).scaleY(0.92f)?.setDuration(120)?.start()
                        startDragAndDrop(ClipData.newPlainText("nine_key_symbol", symbol), View.DragShadowBuilder(this), symbol, 0)
                        true
                    }
                }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
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
            setImageResource(R.drawable.logo_lianai_app)
            background = roundedBackground(Color.TRANSPARENT, 16)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = "恋爱键盘"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_text))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10)
        })
        addView(TextView(this@MainActivity).apply {
            text = "本地优先，语音与离线输入"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.weike_muted))
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 3)
        })
    }

    private fun managementPreview() = FrameLayout(this).apply {
        background = roundedBackground(getColor(R.color.weike_preview), 24)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@MainActivity).apply {
            text = "恋爱输入体验"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = "语音、拼音与英文键盘"
            textSize = 12f
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
        background = roundedBackground(getColor(R.color.weike_panel), 16)
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
        addView(left, LinearLayout.LayoutParams(0, dp(120), 1f).apply { marginEnd = dp(10) })
        addView(right, LinearLayout.LayoutParams(0, dp(120), 1f))
    }

    private fun managementSingleTile(tile: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tile, LinearLayout.LayoutParams(0, dp(120), 1f).apply { marginEnd = dp(10) })
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(120), 1f))
    }

    private fun managementTile(title: String, detail: String, icon: Int, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(getColor(R.color.weike_panel), 16)
        setPadding(dp(13), dp(11), dp(12), dp(10))
        val header = FrameLayout(this@MainActivity)
        val darkTheme = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        header.addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            setColorFilter(if (darkTheme) Color.WHITE else pagePrimary)
            // Circular badge to match the Profile rows and home cards; every
            // icon container in the app shares one shape language.
            background = circleBackground(if (darkTheme) Color.argb(36, 255, 255, 255) else Color.argb(34, 255, 107, 157))
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.START or Gravity.TOP))
        header.addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_right)
            setColorFilter(getColor(R.color.weike_muted))
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.END or Gravity.CENTER_VERTICAL))
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
            includeFontPadding = false
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8)
        })
        addView(TextView(this@MainActivity).apply {
            text = detail
            textSize = 12f
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(getColor(R.color.weike_muted))
            includeFontPadding = false
            layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 4)
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
            text = if (installed) "已启用 · 内置增强词典，离线可用" else "未启用"
            textSize = 14f
            setTextColor(getColor(if (installed) R.color.weike_text else R.color.weike_muted))
        }
        val progress = downloadProgressView()
        val action = primaryButton(if (installed) "已启用" else "下载并启用") {}
        if (installed) {
            // The bundle ships inside the APK; a permanently disabled button
            // only invites "re-download" questions, so hide it entirely.
            action.visibility = View.GONE
        }
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
            operationGuide("扩展词汇、腾讯词汇、英文混输与 Emoji", "随安装包内置，首次使用自动启用，无需联网下载")
        ) {
            addView(state, margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
            addView(progress)
            if (!installed) addView(action)
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
                lifecycleScope.launch {
                    // Removing the model deletes a ~400 MB file; keep it off the main thread.
                    val result = withContext(Dispatchers.IO) { manager.disableWanxiang() }
                    result.onSuccess {
                        sendBroadcast(Intent(WeikeInputMethodService.ACTION_RELOAD_RIME_BUNDLE).setPackage(packageName))
                        showPage(Page.OPTIMIZE_INPUT)
                    }.onFailure { error ->
                        Toast.makeText(this@MainActivity, error.message ?: "移除失败", Toast.LENGTH_LONG).show()
                    }
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
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
    }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 28, bottom = 10)
    }

    private fun cloudSection(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 20, bottom = 8)
    }

    private fun settingsHubSection(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.weike_text))
        includeFontPadding = false
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 20, bottom = 9)
    }

    private fun cloudHelpText(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(getColor(R.color.weike_muted))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun promptTextField(hint: String, minLines: Int, maxLines: Int, top: Int) = EditText(this).apply {
        this.hint = hint
        this.minLines = minLines
        this.maxLines = maxLines
        textSize = 12f
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(getColor(R.color.weike_text))
        setHintTextColor(getColor(R.color.weike_muted))
        setLineSpacing(dp(2).toFloat(), 1f)
        background = GradientDrawable().apply {
            setColor(getColor(R.color.weike_panel))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), getColor(R.color.weike_key))
        }
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = top)
    }

    private fun subtitle(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
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
            textSize = 14f
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
                    textSize = if (index == 0) 14f else 12f
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
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.weike_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 12f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(getColor(R.color.weike_muted))
                layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 4)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun actionRow(title: String, detail: String, destructive: Boolean = false, icon: Int? = null, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        if (icon != null) {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon)
                setColorFilter(pagePrimary)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(26, 255, 107, 157))
                }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                setTextColor(getColor(if (destructive) android.R.color.holo_red_dark else R.color.weike_text))
            })
            if (detail.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = detail
                    textSize = 12f
                    setTextColor(getColor(R.color.weike_muted))
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            addView(TextView(this@MainActivity).apply { text = title; textSize = 14f; setTextColor(getColor(R.color.weike_text)) })
            addView(TextView(this@MainActivity).apply { text = detail; textSize = 12f; setTextColor(getColor(R.color.weike_muted)) })
        }, LinearLayout.LayoutParams(0, dp(62), 1f))
        addView(control, LinearLayout.LayoutParams(dp(150), dp(54)))
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        textSize = 14f
        setTextColor(getColor(R.color.weike_text))
        setHintTextColor(getColor(R.color.weike_muted))
        background = roundedBackground(getColor(R.color.weike_key), 12)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), bottom = 8)
    }

    private fun compactField(hint: String) = field(hint).apply {
        textSize = 14f
        setPadding(dp(11), 0, dp(11), 0)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), bottom = 7)
    }

    private fun settingsSpinner(options: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
        setPadding(0, 0, 0, 0)
    }

    private fun historyCard(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
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
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.weike_text))
        }
        bind(value)
        addView(value, margins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
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
            textSize = 14f
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
        textSize = 14f
        setTextColor(Color.WHITE)
        background = roundedBackground(pagePrimary, 24)
        layoutParams = margins(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), top = 4)
        setOnClickListener { action() }
    }

    private fun formActionButton(label: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (filled) Color.WHITE else pagePrimary)
        background = roundedBackground(if (filled) pagePrimary else getColor(R.color.weike_key), 18)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { action() }
    }

    private fun compactFormActionButton(label: String, filled: Boolean, action: () -> Unit) =
        formActionButton(label, filled, action).apply {
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = 0
            minimumHeight = 0
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

    companion object {
        const val UPDATE_PREFERENCES = "update_check"
        const val EXTRA_OPEN_PAGE = "open_page"
        const val ONBOARDING_PREFERENCES = "onboarding"
        const val KEY_MIC_PERMISSION_ASKED = "mic_permission_asked"
        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val KEY_PENDING_VERSION = "pending_version"
        const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val PRIVACY_POLICY_TEXT = "恋爱键盘默认不收集遥测或崩溃分析数据。\n\n" +
            "· 录音只在内存中存在，结束后会发送到用户配置的 MiMo ASR 服务；应用不保存录音文件。\n" +
            "· 听写、润色和问答历史默认不保存。用户开启历史后，内容只保存在设备本地，并可随时删除。\n" +
            "· 剪贴板历史默认关闭。开启后最多保留 20 条 24 小时内的非敏感、非超长文本，可一键清空。\n" +
            "· 云端接口密钥保存在 Android Keystore 加密保护的本地存储中，不写入日志或网络请求正文。\n" +
            "· 文本模型支持 OpenAI Chat Completions 兼容接口；ASR 仅保证 MiMo 官方兼容格式。用户应仅配置自己信任的服务商。"
    }
}
