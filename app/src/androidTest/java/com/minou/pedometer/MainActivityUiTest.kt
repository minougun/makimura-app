package com.minou.pedometer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabs_areVisible() {
        composeRule.onNodeWithText("今日").assertIsDisplayed()
        composeRule.onNodeWithText("履歴").assertIsDisplayed()
        composeRule.onNodeWithText("設定").assertIsDisplayed()
    }

    @Test
    fun canOpenHistoryAndSettingsTabs() {
        composeRule.onNodeWithText("履歴").performClick()
        composeRule.onNodeWithText("履歴データはまだありません。日付が変わると自動で保存されます。").assertIsDisplayed()
        composeRule.onNodeWithText("保存先を選んで保存").assertIsDisplayed()
        composeRule.onNodeWithText("CSV共有").assertIsDisplayed()

        composeRule.onNodeWithText("設定").performClick()
        composeRule.onNodeWithText("歩幅推定の設定").assertIsDisplayed()
        composeRule.onNodeWithText("設定を保存").assertIsDisplayed()
    }
}
