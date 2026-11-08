package com.weike.ime.ime

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardSymbolsTest {
    @Test fun commonPagesContainScenarioEssentials() {
        fun symbols(page: Int) = KeyboardSymbols.pages[page].rows.flatten().toSet()
        assertTrue(symbols(KeyboardSymbols.NUMERIC).containsAll(listOf(".", "+", "-", "×", "÷", "=", "%", "/", ":", "¥")))
        assertTrue(symbols(KeyboardSymbols.ENGLISH).containsAll(listOf("@", ".", "_", "-", "/", ":", "?", "!", "'", "\"")))
        assertTrue(symbols(KeyboardSymbols.CHINESE).containsAll(listOf("，", "。", "？", "！", "、", "：", "；", "“", "”", "（", "）", "《", "》")))
        assertTrue("，" !in symbols(KeyboardSymbols.ENGLISH))
        assertTrue("☆" !in symbols(KeyboardSymbols.CHINESE))
    }

    @Test fun everyPageFitsTheGridAndHasNoDuplicateKey() {
        for (page in KeyboardSymbols.pages) {
            assertEquals(3, page.rows.size)
            assertTrue(page.rows.all { it.size == 10 && it.all(String::isNotEmpty) })
            assertEquals(30, page.rows.flatten().distinct().size)
        }
    }
}
