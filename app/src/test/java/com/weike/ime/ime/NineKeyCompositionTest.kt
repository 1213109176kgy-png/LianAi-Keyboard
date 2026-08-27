package com.weike.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyCompositionTest {
    @Test fun exactCandidateReadingIsDisplayed() {
        assertEquals("ni", NineKeyComposition.display("64", "64", listOf("ni", "mi")))
    }

    @Test fun partialCandidateReadingNeverDisappears() {
        assertEquals("n", NineKeyComposition.display("6", "6", listOf("ni")))
    }

    @Test fun unmatchedCodeRemainsVisible() {
        assertEquals("111", NineKeyComposition.display("111", "111", emptyList()))
        assertTrue(NineKeyComposition.display("74264543", "74264543", emptyList()).isNotBlank())
    }

    @Test fun unmatchedSuffixDoesNotReplaceReadableComposition() {
        assertEquals(
            "shijie99",
            NineKeyComposition.display(
                raw = "74454399",
                preedit = "74454399",
                candidateReadings = emptyList(),
                previousRaw = "744543",
                previousDisplay = "shijie"
            )
        )
    }
}
