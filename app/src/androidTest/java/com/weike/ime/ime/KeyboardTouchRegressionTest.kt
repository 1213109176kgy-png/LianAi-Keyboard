package com.weike.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android MotionEvents against the actual drawn nine-key view. */
@RunWith(AndroidJUnit4::class)
class KeyboardTouchRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Fixture(val sensitive: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val codes = mutableListOf<String>()
        val actionCalls = mutableListOf<String>()
        val editor = EditText(context).apply {
            inputType = if (sensitive) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
        }
        val sound = KeyboardSoundPlayer(context)
        val actions = Proxy.newProxyInstance(KeyboardActions::class.java.classLoader,
            arrayOf(KeyboardActions::class.java)) { _, method, args ->
            actionCalls += method.name
            when (method.name) {
                "typePinyin" -> { codes += args!![0] as String; editor.append(args[0] as String); null }
                "canBackspace" -> true
                else -> null
            }
        } as KeyboardActions
        val view = WeikeKeyboardView(context, actions, sound)
        val density = context.resources.displayMetrics.density
        val downTime = SystemClock.uptimeMillis()
        var tick = downTime
        init {
            view.update(mode = KeyboardMode.PINYIN, pinyinNineKey = true,
                sensitive = sensitive, keyboardSoundVolume = 0f)
            val height = (440 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, height)
            redraw()
        }
        fun redraw() {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        fun key(code: String): RectF {
            val field = view.javaClass.getDeclaredField("targets").apply { isAccessible = true }
            for (target in field.get(view) as List<*>) {
                target!!
                val type = target.javaClass
                if (!type.getDeclaredField("typingKey").apply { isAccessible = true }.getBoolean(target)) continue
                @Suppress("UNCHECKED_CAST")
                val action = type.getDeclaredField("action").apply { isAccessible = true }.get(target) as () -> Unit
                action()
                val matches = codes.last() == code
                codes.clear(); editor.setText("")
                if (matches) return RectF(type.getDeclaredField("box").apply { isAccessible = true }.get(target) as RectF)
            }
            error("Missing nine-key $code")
        }
        fun event(action: Int, vararg points: Triple<Int, Float, Float>) {
            tick += 8
            val properties = points.map { (id, _, _) -> MotionEvent.PointerProperties().apply {
                this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coords = points.map { (_, x, y) -> MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = .1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, tick, action, points.size, properties, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
        fun point(id: Int, key: RectF) = Triple(id, key.centerX(), key.centerY())
    }

    private fun withKeyboard(sensitive: Boolean = false, test: (Fixture) -> Unit) {
        instrumentation.runOnMainSync {
            val fixture = Fixture(sensitive)
            try { test(fixture) } finally { fixture.sound.release() }
        }
    }

    @Test fun aiTabsKeepKeyboardTabHeightAndFooterDoesNotStealVoiceOrLanguageTouches() = withKeyboard { f ->
        val tabs = f.view.javaClass.getDeclaredField("modeTabs").apply { isAccessible = true }
        val originalTabHeight = (tabs.get(f.view) as RectF).height()
        for (mode in listOf(KeyboardMode.ASK, KeyboardMode.VOICE)) {
            f.view.update(mode = mode, pinyinNineKey = true)
            f.view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            f.view.layout(0, 0, 1080, f.view.measuredHeight); f.redraw()
            assertEquals(originalTabHeight, (tabs.get(f.view) as RectF).height(), .1f)
            val space = f.view.javaClass.getDeclaredField("knowledgeFooterBounds").apply { isAccessible = true }.get(f.view) as RectF?
            val footerY = space?.centerY() ?: (f.view.height - 30f * f.density)
            f.actionCalls.clear()
            f.event(MotionEvent.ACTION_DOWN, Triple(0, f.view.width / 2f, footerY))
            f.event(MotionEvent.ACTION_UP, Triple(0, f.view.width / 2f, footerY))
            assertTrue("Footer should open knowledge selection", "chooseKnowledge" in f.actionCalls)
            if (mode == KeyboardMode.VOICE) {
                for ((x, expected) in listOf(40f * f.density to "switchInputMethod", (f.view.width - 40f * f.density) to "toggleDictation")) {
                    f.actionCalls.clear()
                    f.event(MotionEvent.ACTION_DOWN, Triple(0, x, footerY)); f.event(MotionEvent.ACTION_UP, Triple(0, x, footerY))
                    assertTrue("Bottom icon must remain tappable: $expected", expected in f.actionCalls)
                    assertTrue("Knowledge target must not overlap bottom icons", "chooseKnowledge" !in f.actionCalls)
                }
            }
        }
    }

    @Test fun slightDriftPastPaintedEdgeStillTypesInNormalAndPasswordEditors() {
        for (sensitive in listOf(false, true)) withKeyboard(sensitive) { f ->
            val key = f.key("2")
            f.event(MotionEvent.ACTION_DOWN, Triple(0, key.right - f.density, key.centerY()))
            val drift = Triple(0, key.right + 3f * f.density, key.centerY())
            f.event(MotionEvent.ACTION_MOVE, drift)
            f.event(MotionEvent.ACTION_UP, drift)
            assertEquals(listOf("2"), f.codes)
            assertEquals("2", f.editor.text.toString())
        }
    }

    @Test fun nearEdgeTapInVisualGutterTypesNearestKey() = withKeyboard { f ->
        val key = f.key("2")
        val gutter = Triple(0, key.right + 2f * f.density, key.centerY())
        f.event(MotionEvent.ACTION_DOWN, gutter)
        f.event(MotionEvent.ACTION_UP, gutter)
        assertEquals(listOf("2"), f.codes)
    }

    @Test fun spellingRowTransitionsDoNotResizeTheImeOrMoveKeys() = withKeyboard { f ->
        fun measure() {
            f.view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            f.view.layout(0, 0, 1080, f.view.measuredHeight)
            f.redraw()
        }
        measure()
        val emptyHeight = f.view.height
        val emptyKey = f.key("2")
        assertTrue("Idle T9 header must not reserve a blank 40dp spelling band",
            emptyKey.top <= 70f * f.density)
        repeat(20) {
            f.view.update(pinyinBuffer = "ni", spellingOptions = listOf("ni", "mi"))
            measure()
            assertEquals(emptyHeight, f.view.height)
            assertEquals(emptyKey, f.key("2"))
            f.view.update(pinyinBuffer = "", spellingOptions = emptyList())
            measure()
            assertEquals(emptyHeight, f.view.height)
            assertEquals(emptyKey, f.key("2"))
        }
    }

    @Test fun alternatingOverlappingFingersSurviveIndexChangesAndSmallDrift() = withKeyboard { f ->
        val a = f.key("2"); val b = f.key("3")
        repeat(60) {
            val first = f.point(7, a)
            val second = Triple(12, b.left - 2f * f.density, b.centerY())
            f.event(MotionEvent.ACTION_DOWN, first)
            f.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
            f.event(MotionEvent.ACTION_MOVE, first, second)
            f.event(MotionEvent.ACTION_POINTER_UP, first, second)
            // Pointer 12 becomes index zero after pointer 7 lifts.
            f.event(MotionEvent.ACTION_MOVE, second)
            f.event(MotionEvent.ACTION_UP, second)
            f.redraw()
        }
        assertEquals("23".repeat(60), f.codes.joinToString(""))
    }

    @Test fun secondFingerCanLiftBeforeFirstWithoutLosingEitherKey() = withKeyboard { f ->
        val a = f.point(3, f.key("2")); val b = f.point(9, f.key("3"))
        f.event(MotionEvent.ACTION_DOWN, a)
        f.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, b)
        f.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, b)
        f.event(MotionEvent.ACTION_UP, a)
        assertEquals(listOf("3", "2"), f.codes)
    }

    @Test fun clearDragOutsideAndSystemCancelDoNotInsertThenNextTapWorks() = withKeyboard { f ->
        val key = f.key("2"); val point = f.point(0, key)
        f.event(MotionEvent.ACTION_DOWN, point)
        f.event(MotionEvent.ACTION_MOVE, Triple(0, key.right + 20f * f.density, key.centerY()))
        f.event(MotionEvent.ACTION_UP, point)
        f.event(MotionEvent.ACTION_DOWN, point)
        f.event(MotionEvent.ACTION_CANCEL, point)
        assertEquals(emptyList<String>(), f.codes)
        repeat(100) {
            f.event(MotionEvent.ACTION_DOWN, point)
            f.event(MotionEvent.ACTION_UP, point)
        }
        assertEquals("2".repeat(100), f.codes.joinToString(""))
    }
}
