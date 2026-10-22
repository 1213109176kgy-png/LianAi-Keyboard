package com.weike.ime.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.weike.ime.R
import com.weike.ime.data.PendingPurchase
import com.weike.ime.network.SubscriptionPlan
import com.weike.ime.network.SubscriptionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** The subscription feature's native views, sharing one palette and type scale. */
class SubscriptionViews(private val context: Context) {
    private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val surface = context.getColor(R.color.weike_panel)
    private val ink = context.getColor(R.color.weike_text)
    private val muted = if (night) Color.rgb(216, 191, 201) else Color.rgb(120, 101, 111)
    private val accent = if (night) Color.rgb(255, 157, 191) else Color.rgb(219, 52, 110)
    private val primary = Color.rgb(255, 107, 157)
    private val soft = if (night) Color.rgb(68, 43, 55) else Color.rgb(255, 240, 245)
    private val border = if (night) Color.rgb(99, 65, 80) else Color.rgb(242, 221, 230)
    // Status tones follow the brand palette; green/blue variants read as a
    // different design system next to the pink theme.
    private val success = accent
    private val successSoft = soft

    fun header(onBack: () -> Unit) = row().apply {
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lucide_chevron_left)
            setColorFilter(ink)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "返回"
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = ripple(Color.TRANSPARENT, 16)
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(44), dp(52)))
        addView(text("订阅套餐", 18f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(52), 1f))
        addView(View(context), LinearLayout.LayoutParams(dp(44), 1))
    }

    fun loading(label: String) = row().apply {
        setPadding(dp(18), dp(20), dp(18), dp(20))
        background = shape(surface, 22, border)
        addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(accent) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(text(label, 13f, color = muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
    }

    fun section() = column().apply {
        setPadding(dp(2), dp(22), dp(2), dp(12))
        addView(text("选择适合你的套餐", 19f, true))
        addView(text("按需开通，付款确认后生效", 12f, color = muted), lp(top = 6))
    }

    fun statusCard(status: SubscriptionStatus?, loggedIn: Boolean, failed: Boolean) = panel().apply {
        val active = status?.active == true
        val heading = when {
            active -> status!!.planName
            !loggedIn -> "登录后开通套餐"
            failed -> "订阅状态暂未获取"
            else -> "还没有生效的套餐"
        }
        addView(row().apply {
            addView(icon(R.drawable.ic_lucide_wand_sparkles, accent, 20).apply {
                background = shape(soft, 14)
                setPadding(dp(11), dp(11), dp(11), dp(11))
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(column().apply {
                addView(text("我的订阅", 11f, color = muted))
                addView(text(heading, 18f, true).apply { maxLines = 2 }, lp(top = 5))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            if (active) addView(pill("生效中", success, successSoft))
        })
        if (active) {
            addView(divider(), lp(top = 16, height = 1))
            addView(row().apply {
                addView(text("剩余 ${status!!.daysLeft} 天", 12f, color = muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(quota(status.dailyQuota), 12f, true, accent))
            }, lp(top = 12))
            addView(text("到期日期  ${formatDate(status!!.expiresAt)}", 11f, color = muted), lp(top = 7))
        } else {
            addView(text(if (failed) "请稍后重新进入，或在付款后查询订单结果。" else "使用官方 AI 通道，让每次表达更轻松。", 12f, color = muted), lp(top = 12))
        }
    }

    fun pendingCard(pending: PendingPurchase, onResume: () -> Unit, onQuery: () -> Unit): Pair<LinearLayout, TextView> {
        val status = text("可继续付款或查询结果，也可直接选择其他套餐。", 12f, color = muted)
        val card = panel().apply {
            addView(row().apply {
                addView(text(pending.plan.name, 14f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pill("待确认", accent, soft))
            })
            addView(status, lp(top = 9))
            addView(row().apply {
                addView(action("继续付款", false, onClick = onResume), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (pending.orderId.isNotBlank()) addView(action("查询结果", false, onClick = onQuery), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            }, lp(top = 12))
        }
        return card to status
    }

    fun planCard(plan: SubscriptionPlan, alipayEnabled: Boolean, onClick: () -> Unit) = panel().apply {
        val free = plan.priceFen == 0L
        val enabled = free || alipayEnabled
        addView(row().apply {
            gravity = Gravity.TOP
            addView(text(plan.name, 17f, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), dp(10), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(price(plan, 28f))
        })
        if (plan.description.isNotBlank()) addView(text(plan.description, 12f, color = muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(top = 7))
        addView(row().apply {
            addView(column().apply {
                addView(benefit(R.drawable.ic_lucide_clock_3, "${plan.periodDays} 天有效"))
                addView(benefit(R.drawable.ic_lucide_wand_sparkles, quota(plan.dailyQuota)), lp(top = 7))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(action(if (free) "免费领取" else if (enabled) "支付宝开通" else "暂未开放", !free, enabled, onClick).apply {
                contentDescription = if (free) "领取${plan.name}" else "开通${plan.name}，${plan.priceYuan}元"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        }, lp(top = 16))
    }

    fun empty(title: String, onRetry: (() -> Unit)? = null) = panel().apply {
        addView(text(title, 14f, true))
        if (onRetry != null) addView(action("重新加载", false, onClick = onRetry), lp(top = 12))
    }

    fun showConfirmation(plan: SubscriptionPlan, onConfirm: () -> Unit) {
        val free = plan.priceFen == 0L
        val dialog = Dialog(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = column().apply {
            setPadding(dp(22), dp(22), dp(22), dp(10))
            addView(row().apply {
                addView(column().apply {
                    addView(text(if (free) "确认领取" else "确认开通", 21f, true))
                    addView(text("核对套餐信息后继续", 12f, color = muted), lp(top = 6))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_lucide_x)
                    setColorFilter(muted)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = ripple(soft, 22)
                    contentDescription = "关闭开通确认"
                    isFocusable = true
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            addView(row().apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = shape(soft, 18)
                addView(icon(R.drawable.ic_lucide_wand_sparkles, accent, 23), LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(column().apply {
                    addView(text(plan.name, 17f, true))
                    if (plan.description.isNotBlank()) addView(text(plan.description, 12f, color = muted), lp(top = 5))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            }, lp(top = 22))
            addView(summaryRow("套餐有效期", "${plan.periodDays} 天"), lp(top = 21))
            addView(summaryRow("每日 AI 额度", if (plan.dailyQuota == 0) "不限次数" else "${plan.dailyQuota} 次"), lp(top = 16))
            addView(summaryRow(if (free) "领取方式" else "支付方式", if (free) "免费领取" else "支付宝", accent), lp(top = 16))
            addView(divider(), lp(top = 21, height = 1))
            addView(row().apply {
                addView(text(if (free) "本次领取" else "应付金额", 14f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(price(plan, 32f))
            }, lp(top = 17))
            addView(text(if (free) "每个账号每套餐可领取一次。" else "付款确认后自动生效，已有有效期会顺延。", 11f, color = muted).apply {
                gravity = Gravity.CENTER
            }, lp(top = 13))
            addView(action(if (free) "确认领取" else "确认并支付 ¥${plan.priceYuan}", true) {
                dialog.dismiss()
                onConfirm()
            }.apply { textSize = 15f; minimumHeight = dp(50) }, lp(top = 16))
            addView(action(if (free) "暂不领取" else "暂不开通", false) { dialog.dismiss() }.apply {
                background = ripple(Color.TRANSPARENT, 14)
                setTextColor(muted)
            }, lp(top = 5))
        }
        val maxHeight = (context.resources.displayMetrics.heightPixels * .84f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            background = shape(surface, 26)
            clipToOutline = true
            isFillViewport = false
            addView(body)
        }
        dialog.setContentView(FrameLayout(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.42f)
            setLayout(min(context.resources.displayMetrics.widthPixels, dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun panel() = column().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = shape(surface, 22, border)
    }

    private fun summaryRow(label: String, value: String, valueColor: Int = ink) = row().apply {
        addView(text(label, 13f, color = muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(value, 14f, true, valueColor).apply { gravity = Gravity.END })
    }

    private fun benefit(resource: Int, label: String) = row().apply {
        addView(icon(resource, muted, 13), LinearLayout.LayoutParams(dp(14), dp(14)))
        addView(text(label, 11.5f, color = muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
    }

    private fun price(plan: SubscriptionPlan, size: Float) = text(if (plan.priceFen == 0L) "免费" else "¥${plan.priceYuan}", size, true, if (plan.priceFen == 0L) success else accent).apply {
        gravity = Gravity.END
        maxLines = 1
        if (plan.priceFen > 0L) text = SpannableString(text).apply {
            setSpan(RelativeSizeSpan(.54f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun action(label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        minWidth = 0; minimumWidth = 0
        minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isEnabled = enabled
        setTextColor(if (!enabled) muted else if (filled) Color.WHITE else accent)
        backgroundTintList = null
        background = ripple(if (!enabled || !filled) soft else primary, 14)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = ink) = TextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun pill(label: String, color: Int, fill: Int) = text(label, 10.5f, true, color).apply {
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = shape(fill, 8)
    }

    private fun icon(resource: Int, color: Int, size: Int) = ImageView(context).apply {
        setImageResource(resource)
        setColorFilter(color)
        minimumWidth = dp(size); minimumHeight = dp(size)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun divider() = View(context).apply { setBackgroundColor(border) }
    private fun quota(amount: Int) = if (amount == 0) "每日不限量" else "每日 $amount 次"
    private fun formatDate(time: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(time))
    private fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun ripple(fill: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x1ACD356A), shape(fill, radius), shape(Color.WHITE, radius))
    private fun lp(top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height > 0) dp(height) else height).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}
