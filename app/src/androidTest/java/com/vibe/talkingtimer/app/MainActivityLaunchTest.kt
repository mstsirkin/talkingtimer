package com.vibe.talkingtimer.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertExists
import org.junit.Rule
import org.junit.Test

class MainActivityLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsTitle() {
        composeRule.onNodeWithText("Talking Timer").assertExists()
    }
}
