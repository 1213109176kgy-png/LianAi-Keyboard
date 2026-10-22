package com.weike.ime.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.weike.ime.R

/** Shared controls for the model library, editor and selection dialogs. */
class ModelConfigurationViews(private val context: Context) {
    private val primary = Color.rgb(255, 107, 157)
    private val soft = context.getColor(R.color.weike_key)
    private val surface = context.getColor(R.color.weike_panel)
    private val ink = context.getColor(R.color.weike_text)
    private val muted = context.getColor(R.color.weike_muted)
    private val danger = Color.rgb(228, 80, 110)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        border?.let { setStroke(dp(1), it) }
    }

    fun button(label: String, filled: Boolean = false, destructive: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        includeFontPadding = false
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(14), 0, dp(14), 0)
        val color = if (destructive) danger else primary
        val textColor = if (filled) Color.WHITE else color
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf((textColor and 0x00ffffff) or 0x66000000, textColor)))
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(soft))
            addState(intArrayOf(), RippleDrawable(ColorStateList.valueOf(0x22cd356a), shape(if (filled) color else soft), null))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        setOnClickListener { action() }
    }

    fun field(hint: String) = EditText(context).apply {
        this.hint = hint
        textSize = 14f
        setSingleLine(true)
        setTextColor(ink)
        setHintTextColor(muted)
        setPadding(dp(14), 0, dp(14), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(surface, primary))
            addState(intArrayOf(), shape(surface, soft))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(6) }
    }

    fun label(value: String) = TextView(context).apply {
        text = value
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(muted)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
    }

    fun sheet(title: String, subtitle: String, content: View, footer: View? = null, prepareWindow: ((Window) -> Unit)? = null): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * .85f).toInt()
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = shape(surface).apply { cornerRadius = dp(24).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        panel.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_lucide_x)
                setColorFilter(muted)
                contentDescription = "关闭弹窗"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(0x22cd356a), shape(soft), null)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        panel.addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(12) })
        panel.addView(ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer?.let {
            panel.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            })
        }
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            prepareWindow?.invoke(this)
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(.42f)
        }
        return dialog
    }

    fun modelAccessDialog(message: String, prepareWindow: (Window) -> Unit, onVip: () -> Unit, onModels: () -> Unit): Dialog {
        lateinit var dialog: Dialog
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fun option(icon: Int, title: String, detail: String) {
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(surface, soft)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    addView(ImageView(context).apply {
                        setImageResource(icon)
                        setColorFilter(primary)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        background = shape(soft)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(context).apply {
                            text = title
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(ink)
                        })
                        addView(TextView(context).apply {
                            text = detail
                            textSize = 12f
                            setTextColor(muted)
                            setLineSpacing(dp(2).toFloat(), 1f)
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            option(R.drawable.ic_lucide_wand_sparkles, "VIP 官方模型", "使用已配置的官方模型，无需填写接口参数。")
            option(R.drawable.ic_lucide_cloud, "使用自己的模型", "添加模型接口与 API Key，为对应功能选择模型。")
        }
        val actions = LinearLayout(context).apply {
            addView(button("去配置模型") { dialog.dismiss(); onModels() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
            addView(button("开通 VIP", filled = true) { dialog.dismiss(); onVip() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        dialog = sheet("选择 AI 使用方式", message, options, actions, prepareWindow)
        return dialog
    }

    fun choose(title: String, subtitle: String, labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        lateinit var dialog: Dialog
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            labels.forEachIndexed { index, label ->
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(58)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = RippleDrawable(ColorStateList.valueOf(0x22cd356a), shape(if (index == selected) soft else surface, soft), null)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val parts = label.split('\n', limit = 2)
                        addView(TextView(context).apply {
                            text = parts.first()
                            textSize = 14f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(if (index == selected) primary else ink)
                            setLineSpacing(dp(2).toFloat(), 1f)
                        })
                        parts.getOrNull(1)?.let { detail ->
                            addView(TextView(context).apply {
                                text = detail
                                textSize = 12f
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setTextColor(muted)
                            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(context).apply {
                        text = if (index == selected) "✓" else "○"
                        textSize = 18f
                        gravity = Gravity.END
                        setTextColor(if (index == selected) primary else muted)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(28), dp(26)))
                    isSelected = index == selected
                    setOnClickListener { dialog.dismiss(); onSelected(index) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        dialog = sheet(title, subtitle, options)
    }
}
