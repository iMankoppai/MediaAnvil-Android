package com.imankoppai.mediaanvil

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.imankoppai.mediaanvil.ui.BottomNavigation
import com.imankoppai.mediaanvil.ui.MainTab
import com.imankoppai.mediaanvil.ui.UiTestTags
import com.imankoppai.mediaanvil.ui.theme.MediaAnvilTheme
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION") // The v1 rule remains compatible with StateRestorationTester.
class MainNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mainTabs_switchBetweenLocalPlayerPages() {
        composeRule.setContent {
            var tab by rememberSaveable { mutableStateOf(MainTab.Media) }
            MediaAnvilTheme(darkTheme = false) {
                BottomNavigation(tab = tab, onSelect = { tab = it }, largeBar = false)
            }
        }

        composeRule.onNodeWithTag(UiTestTags.MediaTab).assertIsSelected()
        composeRule.onNodeWithTag(UiTestTags.PlayerTab).performClick()
        composeRule.onNodeWithTag(UiTestTags.PlayerTab).assertIsSelected()
        composeRule.onNodeWithTag(UiTestTags.MediaTab).assertIsNotSelected()
        composeRule.onNodeWithTag(UiTestTags.SettingsTab).performClick()
        composeRule.onNodeWithTag(UiTestTags.SettingsTab).assertIsSelected()
    }

    @Test
    fun selectedTab_survivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            var tab by rememberSaveable { mutableStateOf(MainTab.Media) }
            MediaAnvilTheme(darkTheme = false) {
                BottomNavigation(tab = tab, onSelect = { tab = it }, largeBar = true)
            }
        }

        composeRule.onNodeWithTag(UiTestTags.PlayerTab).performClick()
        composeRule.onNodeWithTag(UiTestTags.PlayerTab).assertIsSelected()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(UiTestTags.PlayerTab).assertIsSelected()
        composeRule.onNodeWithTag(UiTestTags.MediaTab).assertIsNotSelected()
    }
}
