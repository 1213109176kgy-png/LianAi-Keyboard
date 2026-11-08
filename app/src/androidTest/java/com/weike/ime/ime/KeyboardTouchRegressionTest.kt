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
        val insertedSymbols = mutableListOf<String>()
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
                "typeEnglish" -> { insertedSymbols += args!![0] as String; null }
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
        fun snapshot(name: String) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
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

    @Test fun superSayComposingCandidatesFitBetweenRelationsAndKeys() = withKeyboard { f ->
        for (level in listOf(-3, 0, 3)) {
            f.view.update(mode = KeyboardMode.VOICE, pinyinNineKey = true, pinyinReady = true,
                pinyinBuffer = "a", spellingOptions = listOf("a"),
                pinyinCandidates = listOf(PinyinCandidate("啊"), PinyinCandidate("阿")), candidateTextSizeLevel = level)
            f.view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            f.view.layout(0, 0, 1080, f.view.measuredHeight)
            f.redraw()
            fun rect(name: String) = f.view.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(f.view) as RectF
            val candidates = rect("candidateStrip")
            val relations = rect("polishRelationViewport")
            val firstKey = f.key("2")
            assertTrue("Candidates must be below relations", candidates.top > relations.bottom)
            assertTrue("Candidates must not overlap keys", candidates.bottom <= firstKey.top + 1)
            assertTrue("No large gap below spelling", candidates.top - relations.bottom < 50 * f.density)
            if (level == 0) f.snapshot("polish-composing-fixed.png")
            f.actionCalls.clear()
            val pick = Triple(0, 20 * f.density, candidates.centerY())
            f.event(MotionEvent.ACTION_DOWN, pick)
            f.event(MotionEvent.ACTION_UP, pick)
            assertTrue("chooseCandidate" in f.actionCalls)
        }
    }

    @Test fun punctuationAndKaomojiCloseButtonsDismissTheirOwnStrips() = withKeyboard { f ->
        for (name in listOf("punctuationStripVisible", "kaomojiStripVisible")) {
            val flag = f.view.javaClass.getDeclaredField(name).apply { isAccessible = true }
            flag.setBoolean(f.view, true)
            f.redraw()
            f.snapshot("$name.png")
            val point = Triple(0, f.view.width - 26 * f.density, 32 * f.density)
            f.event(MotionEvent.ACTION_DOWN, point)
            f.event(MotionEvent.ACTION_UP, point)
            assertEquals(false, flag.getBoolean(f.view))
        }
    }

    @Test fun polishEditorAndResultsOfferFreshDraftActions() = withKeyboard { f ->
        f.view.update(mode = KeyboardMode.VOICE, polishDraft = "准备优化的旧内容", polishCursor = 8)
        f.redraw()
        f.snapshot("compact-polish-editor.png")
        f.actionCalls.clear()
        val clear = Triple(0, f.view.width - 127 * f.density, 125 * f.density)
        f.event(MotionEvent.ACTION_DOWN, clear)
        f.event(MotionEvent.ACTION_UP, clear)
        assertEquals(listOf("clearPolishDraft"), f.actionCalls)
        f.view.update(polishOptions = listOf("第一条", "第二条", "第三条"))
        f.redraw()
        f.snapshot("compact-polish-results.png")
        f.actionCalls.clear()
        val fresh = Triple(0, f.view.width - 102 * f.density, 81 * f.density)
        f.event(MotionEvent.ACTION_DOWN, fresh)
        f.event(MotionEvent.ACTION_UP, fresh)
        assertEquals(listOf("clearPolishDraft"), f.actionCalls)
    }

    @Test fun eachSymbolCategoryDrawsAndTypesItsOwnContentAndReturnsToOrigin() = withKeyboard { f ->
        f.view.update(mode = KeyboardMode.SYMBOLS)
        val page = f.view.javaClass.getDeclaredField("symbolsPage").apply { isAccessible = true }
        for ((index, category) in KeyboardSymbols.pages.withIndex()) {
            page.setInt(f.view, index)
            f.redraw()
            f.snapshot("symbol-category-$index.png")
            val symbol = category.rows.first().first()
            val targets = f.view.javaClass.getDeclaredField("targets").apply { isAccessible = true }.get(f.view) as List<*>
            val target = targets.first { it != null && it.javaClass.getDeclaredField("previewLabel").apply { isAccessible = true }.get(it) == symbol }!!
            val box = target.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(target) as RectF
            f.insertedSymbols.clear()
            f.event(MotionEvent.ACTION_DOWN, f.point(0, box))
            f.event(MotionEvent.ACTION_UP, f.point(0, box))
            assertEquals(listOf(symbol), f.insertedSymbols)
        }
        f.actionCalls.clear()
        val back = Triple(0, 60 * f.density, f.view.height - 30 * f.density)
        f.event(MotionEvent.ACTION_DOWN, back)
        f.event(MotionEvent.ACTION_UP, back)
        assertTrue("toggleSymbols" in f.actionCalls)
        assertTrue("returnToNineKeyPinyin" !in f.actionCalls)
    }

    @Test fun nineKeySymbolsEntryFollowsPinyinOrNumericContext() = withKeyboard { f ->
        val four = f.key("4")
        val point = Triple(0, 30 * f.density, four.centerY())
        val page = f.view.javaClass.getDeclaredField("symbolsPage").apply { isAccessible = true }
        val numberPad = f.view.javaClass.getDeclaredField("nineKeyNumberPad").apply { isAccessible = true }
        for (numeric in listOf(false, true)) {
            numberPad.setBoolean(f.view, numeric)
            f.redraw()
            page.setInt(f.view, 0)
            f.actionCalls.clear()
            f.event(MotionEvent.ACTION_DOWN, point)
            f.event(MotionEvent.ACTION_UP, point)
            assertEquals(if (numeric) KeyboardSymbols.NUMERIC else KeyboardSymbols.CHINESE, page.getInt(f.view))
            assertTrue("toggleSymbols" in f.actionCalls)
            assertEquals(if (numeric) "返回数字" else "返回拼音",
                f.view.javaClass.getDeclaredField("symbolReturnLabel").apply { isAccessible = true }.get(f.view))
        }
    }

    @Test fun nineKeyBottomLeftTextKeyPastesClipboard() = withKeyboard { f ->
        val seven = f.key("7")
        val four = f.key("4")
        val point = Triple(0, seven.centerX(), seven.centerY() + seven.centerY() - four.centerY())
        f.actionCalls.clear()
        f.event(MotionEvent.ACTION_DOWN, point)
        f.event(MotionEvent.ACTION_UP, point)
        assertEquals(listOf("pastePrimaryClipboard"), f.actionCalls)
    }

    @Test fun qwertySymbolsEntryFollowsLanguageInsteadOfLastPage() = withKeyboard { f ->
        val page = f.view.javaClass.getDeclaredField("symbolsPage").apply { isAccessible = true }
        for (mode in listOf(KeyboardMode.ENGLISH, KeyboardMode.PINYIN)) {
            f.view.update(mode = mode, pinyinNineKey = false)
            f.redraw()
            val targets = f.view.javaClass.getDeclaredField("targets").apply { isAccessible = true }.get(f.view) as List<*>
            var number: RectF? = null
            for (target in targets) {
                target!!
                @Suppress("UNCHECKED_CAST")
                val action = target.javaClass.getDeclaredField("action").apply { isAccessible = true }.get(target) as () -> Unit
                f.actionCalls.clear()
                action()
                if ("toggleSymbols" in f.actionCalls) {
                    number = RectF(target.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(target) as RectF)
                    break
                }
            }
            assertTrue(number != null)
            for (oldPage in listOf(0, 1, 2, 3)) {
                page.setInt(f.view, oldPage)
                f.actionCalls.clear()
                f.event(MotionEvent.ACTION_DOWN, f.point(0, number!!))
                f.event(MotionEvent.ACTION_UP, f.point(0, number!!))
                assertEquals(if (mode == KeyboardMode.ENGLISH) KeyboardSymbols.ENGLISH else KeyboardSymbols.CHINESE, page.getInt(f.view))
                assertTrue("toggleSymbols" in f.actionCalls)
            }
        }
    }

    @Test fun longDraftWrapsScrollsAndFollowsInsertionPoint() = withKeyboard { f ->
        val draft = "这是一段较长的草稿，包含中文、English和表情🙂。\n".repeat(40)
        f.view.update(mode = KeyboardMode.VOICE, polishDraft = draft, polishCursor = draft.length)
        f.redraw()
        fun field(name: String) = f.view.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(f.view)
        val layout = field("draftLayout") as android.text.StaticLayout
        val viewport = field("draftViewport") as RectF
        assertTrue(layout.lineCount > 40)
        val endScroll = field("draftScrollY") as Float
        assertTrue(endScroll > 0)
        assertTrue(layout.getLineBottom(layout.getLineForOffset(draft.length)) <= endScroll + viewport.height() + 1)
        f.event(MotionEvent.ACTION_DOWN, f.point(0, viewport))
        f.event(MotionEvent.ACTION_MOVE, Triple(0, viewport.centerX(), viewport.centerY() + 100))
        f.redraw()
        assertTrue((field("draftScrollY") as Float) < endScroll)
        f.event(MotionEvent.ACTION_UP, f.point(0, viewport))
        f.view.update(polishCursor = 0)
        f.redraw()
        assertEquals(0f, field("draftScrollY") as Float, .1f)
        f.actionCalls.clear()
        f.event(MotionEvent.ACTION_DOWN, f.point(0, viewport))
        f.event(MotionEvent.ACTION_UP, f.point(0, viewport))
        assertTrue("setPolishCursor" in f.actionCalls)
    }

    @Test fun resultFooterRetryAndKnowledgeShareBoundsAndRemainIndependentlyTappable() = withKeyboard { f ->
        f.view.update(mode = KeyboardMode.ASK, replyOptions = listOf("先休息一下", "辛苦啦", "陪你聊一会儿"))
        f.redraw()
        val footer = f.view.javaClass.getDeclaredField("knowledgeFooterBounds").apply { isAccessible = true }.get(f.view) as RectF
        val targets = f.view.javaClass.getDeclaredField("targets").apply { isAccessible = true }.get(f.view) as List<*>
        var retry: RectF? = null
        for (target in targets) {
            target!!
            @Suppress("UNCHECKED_CAST")
            val action = target.javaClass.getDeclaredField("action").apply { isAccessible = true }.get(target) as () -> Unit
            f.actionCalls.clear(); action()
            if ("regenerateReplyOptions" in f.actionCalls) retry = RectF(target.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(target) as RectF)
        }
        assertTrue(retry != null)
        assertEquals(retry!!.top, footer.top, .1f)
        assertEquals(retry!!.bottom, footer.bottom, .1f)
        for ((box, expected) in listOf(retry!! to "regenerateReplyOptions", footer to "chooseKnowledge")) {
            f.actionCalls.clear()
            f.event(MotionEvent.ACTION_DOWN, f.point(0, box)); f.event(MotionEvent.ACTION_UP, f.point(0, box))
            assertTrue(expected in f.actionCalls)
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
