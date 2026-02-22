package com.vibe.talkingtimer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CalloutPhraseBuilderTest {
    @Test
    fun buildsSecondsOnlyPhrase() {
        assertEquals(listOf("n_10", "seconds"), CalloutPhraseBuilder.buildClipTokens(10_000L))
    }

    @Test
    fun buildsMinuteAndSecondPhrase() {
        assertEquals(
            listOf("n_1", "minute", "n_30", "seconds"),
            CalloutPhraseBuilder.buildClipTokens(90_000L),
        )
    }
}
